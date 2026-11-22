package com.shiyu.sime.ime;

import com.shiyu.sime.ime.engine.DecodeResult;
import com.shiyu.sime.ime.engine.LearnedSentenceStore;
import com.shiyu.sime.ime.engine.ContextualSelectionMemory;
import com.shiyu.sime.ime.data.QuickPhraseStore;
import com.shiyu.sime.ime.engine.Decoder;
import com.shiyu.sime.ime.PinyinUtil;
import com.shiyu.sime.ime.keyboard.KeyType;
import com.shiyu.sime.ime.keyboard.SimeKey;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Input logic core. Owns the single {@link InputState} instance, dispatches
 * key and candidate events, calls into the {@link Decoder}, and notifies the
 * IME service via {@link Listener} and the UI via {@link StateObserver}.
 *
 * <p>The behaviour here is the source of truth for Android input semantics
 * and is intentionally aligned with {@code Linux/fcitx5/src/sime.cc}. See
 * {@code private/SimeAndroidRefactor.md} sections 4 and 5 for the rules.
 */
public class InputKernel {

    /**
     * Extra full-sentence alternatives to request from the decoder per
     * keystroke (Layer 1). Layer 2 single-syllable / single-char
     * alternatives are always returned in full. Mirrors Linux fcitx5
     * default {@code nbest=0}.
     */
    private static final int EXTRA_SENTENCES = 0;

    /** Number of predictions / completions to request. */
    private static final int PREDICTION_LIMIT = 10;

    /** Side effects requested by the kernel, forwarded to the IME service. */
    public interface Listener {
        /** Commit finalized text to the input connection. */
        void onCommitText(String text);

        /** Commit text and run its learning transaction after a successful host commit. */
        default void onCommitText(String text, Runnable confirmLearning) {
            onCommitText(text);
            if (confirmLearning != null) confirmLearning.run();
        }

        /** Delete {@code count} characters before the cursor (passthrough). */
        void onDeleteBefore(int count);

        /** Ask the host to execute the default enter action. */
        void onSendEnter();

        /** Optional composing text preview (may be empty). */
        void onSetComposingText(String preedit);

        /** Text-editing panel action: cursor move, select, cut/copy/paste... */
        default void onEditAction(String action) {}
    }

    /** UI subscribes to immutable snapshots. */
    public interface StateObserver {
        void onStateChanged(Snapshot snapshot);
    }

    /** Immutable snapshot of all UI-visible state. */
    public static final class Snapshot {
        public final InputState state;
        public final List<DecodeResult> candidates;
        public final List<PinyinAlt> pinyinAlts;
        public final String topUnits;
        public final KeyboardMode mode;
        public final ChineseLayout chineseLayout;
        public final String englishBuffer;
        /** True while the candidate strip is showing the T9 "1 key"
         *  punctuation picker. Treated by the UI like prediction mode
         *  (right-edge button becomes "×" to dismiss). */
        public final boolean inPunctuationPicker;

        public Snapshot(InputState state, List<DecodeResult> candidates,
                 List<PinyinAlt> pinyinAlts, String topUnits,
                 KeyboardMode mode, ChineseLayout chineseLayout,
                 String englishBuffer) {
            this(state, candidates, pinyinAlts, topUnits, mode,
                    chineseLayout, englishBuffer, false);
        }

        public Snapshot(InputState state, List<DecodeResult> candidates,
                 List<PinyinAlt> pinyinAlts, String topUnits,
                 KeyboardMode mode, ChineseLayout chineseLayout,
                 String englishBuffer, boolean inPunctuationPicker) {
            this.state = state;
            this.candidates = candidates;
            this.pinyinAlts = pinyinAlts;
            this.topUnits = topUnits;
            this.mode = mode;
            this.chineseLayout = chineseLayout;
            this.englishBuffer = englishBuffer;
            this.inPunctuationPicker = inPunctuationPicker;
        }
    }

    private final Decoder decoder;
    private final InputState state = new InputState();
    private final Runner engineRunner;
    private final Runner mainRunner;

    private interface Runner {
        void post(Runnable r);
        void quitSafely();
    }

    private static final class HandlerRunner implements Runner {
        private final Handler handler;
        private final HandlerThread thread;

        HandlerRunner(Handler handler, HandlerThread thread) {
            this.handler = handler;
            this.thread = thread;
        }

        @Override
        public void post(Runnable r) {
            handler.post(r);
        }

        @Override
        public void quitSafely() {
            if (thread != null) thread.quitSafely();
        }
    }

    private static final class DirectRunner implements Runner {
        @Override
        public void post(Runnable r) {
            r.run();
        }

        @Override
        public void quitSafely() {
            // no-op
        }
    }

    /** Persisted preference; the settings UI will expose it separately. */
    private boolean autoSpaceEnglishWords = true;
    /** Tracks the committed prediction context, not arbitrary host-app text. */
    private boolean previousCommitEndsWithAsciiWord = false;

    private KeyboardMode mode = KeyboardMode.CHINESE;
    private KeyboardMode previousMode = KeyboardMode.CHINESE;

    /**
     * Helper that used to also include ADD_PHRASE; kept here so the
     * existing call sites stay terse. Compose-state is now tracked in
     * {@link com.shiyu.sime.ime.InputView#composing} independent of
     * kernel mode (so language toggle from CHINESE↔ENGLISH during
     * composing doesn't dismiss the overlay).
     */
    private boolean isChineseLike() {
        return mode == KeyboardMode.CHINESE;
    }
    private ChineseLayout chineseLayout = ChineseLayout.QWERTY;
    private boolean predictionEnabled = true;
    private boolean traditionalEnabled = false;
    private com.shiyu.sime.ime.data.TraditionalConverter tradConverter;
    /** Set true while the user is in a password / OTP / "no personalized
     *  learning" field. Suppresses prediction without changing the user's
     *  saved pref — flips back automatically when the field changes. */
    private boolean privateField = false;

    private List<DecodeResult> candidates = Collections.emptyList();
    private List<QuickPhraseStore.Entry> personalDictionary = Collections.emptyList();

    /** Replace the user dictionary snapshot without blocking the UI thread. */
    public void setPersonalDictionary(List<QuickPhraseStore.Entry> entries) {
        List<QuickPhraseStore.Entry> copy = entries == null
                ? Collections.emptyList() : new ArrayList<>(entries);
        engineRunner.post(() -> personalDictionary = copy);
    }
    /**
     * True when the candidate list is showing T9 "1 key" punctuation
     * options instead of decoded hanzi. Picking commits the punctuation
     * directly; any other key dismisses the picker first.
     */
    private boolean inPunctuationPicker = false;

    /** Punctuation shown by the T9 "1 key" picker, in display order. */
    private static final String[] T9_NUM_PUNCTUATION = {
            "@", "#", "*", "～", "(", ")", "——", "“", "”", "……"
    };
    private List<PinyinAlt> pinyinAlts = Collections.emptyList();
    /** Segmented pinyin string of the current top candidate, for preedit. */
    private String topUnits = "";

    /** Token IDs of each selection, parallel with state.selections. */
    private final List<int[]> selectionTokenIds = new ArrayList<>();

    /** English keyboard buffer (not yet committed). */
    private final StringBuilder englishBuffer = new StringBuilder();

    /**
     * A possible segmentation of the leading digits into pinyin letters.
     * Shown in the T9 left strip while input is active.
     */
    public static final class PinyinAlt {
        /** Display string without separators, e.g. {@code "ni"}. */
        public final String letters;
        /** Segmented string with {@code '} separators, e.g. {@code "ni'hao"}. */
        public final String units;
        /** How many digits this alternative consumes from the digit region. */
        public final int digitCount;

        public PinyinAlt(String letters, String units, int digitCount) {
            this.letters = letters;
            this.units = units;
            this.digitCount = digitCount;
        }
    }

    private Listener listener;
    private StateObserver observer;

    public InputKernel(Decoder decoder) {
        this.decoder = decoder;
        HandlerThread engineThread = new HandlerThread("sime-engine");
        engineThread.start();
        this.engineRunner = new HandlerRunner(
                new Handler(engineThread.getLooper()), engineThread);
        this.mainRunner = new HandlerRunner(
                new Handler(Looper.getMainLooper()), null);
        state.maxContextIds = decoder.contextSize();
    }

    InputKernel(Decoder decoder, boolean synchronous) {
        this.decoder = decoder;
        if (synchronous) {
            this.engineRunner = new DirectRunner();
            this.mainRunner = new DirectRunner();
        } else {
            HandlerThread engineThread = new HandlerThread("sime-engine");
            engineThread.start();
            this.engineRunner = new HandlerRunner(
                    new Handler(engineThread.getLooper()), engineThread);
            this.mainRunner = new HandlerRunner(
                    new Handler(Looper.getMainLooper()), null);
        }
        state.maxContextIds = decoder.contextSize();
    }

    // ===== Lifecycle =====

    public void attach(Listener l, StateObserver o) {
        this.listener = l;
        this.observer = o;
    }

    public void detach() {
        this.listener = null;
        this.observer = null;
        engineRunner.post(decoder::flushUserSentence);
        engineRunner.quitSafely();
    }

    public void onStartInput() {
        engineRunner.post(this::resetAll);
    }

    public void onFinishInput() {
        engineRunner.post(() -> {
            resetAll();
            // Persist any pending learns at session end so we don't lose
            // them if the IME process is reaped without onDestroy.
            decoder.flushUserSentence();
        });
    }

    // ===== State accessors =====

    /** Only for creating initial snapshot before engine thread starts. */
    public ChineseLayout getInitialChineseLayout() { return chineseLayout; }

    public void setChineseLayout(ChineseLayout l) {
        engineRunner.post(() -> { this.chineseLayout = l; });
    }

    public void setPredictionEnabled(boolean enabled) {
        engineRunner.post(() -> {
            this.predictionEnabled = enabled;
            if (!enabled && state.predicting) {
                exitPrediction();
                publish();
            }
        });
    }

    public void setTraditionalEnabled(boolean enabled) {
        engineRunner.post(() -> {
            this.traditionalEnabled = enabled;
            // Re-publish with trad-mapped candidate text (or simp if off).
            publish();
        });
    }

    /** Kept for compatibility; automatic English word spacing is removed. */
    public void setAutoSpaceEnglishWords(boolean enabled) {
        engineRunner.post(() -> this.autoSpaceEnglishWords = enabled);
    }

    /** Public hook for the candidates bar's "×" button: drop the
     *  current prediction strip without committing anything. */
    public void dismissPredictions() {
        engineRunner.post(() -> {
            if (state.predicting) {
                exitPrediction();
                publish();
            }
        });
    }

    /** Same idea for the T9 "1 key" punctuation strip. */
    public void dismissPunctuationPickerPublic() {
        engineRunner.post(() -> {
            if (inPunctuationPicker) {
                dismissPunctuationPicker(/*publish=*/true);
            }
        });
    }

    public void setPrivateField(boolean privateField) {
        engineRunner.post(() -> {
            if (this.privateField == privateField) return;
            this.privateField = privateField;
            // Bail out of any active prediction when entering a sensitive
            // field; restore on the next non-sensitive focus naturally
            // (predictions are demand-shown).
            if (privateField && state.predicting) {
                exitPrediction();
                publish();
            }
        });
    }

    public void setTraditionalConverter(
            com.shiyu.sime.ime.data.TraditionalConverter c) {
        this.tradConverter = c;
    }

    /**
     * Commit raw text without changing the keyboard mode. Used by the
     * personal dictionary / clipboard panels and other panels
     * that emit literal characters.
     */
    public void commitTextRaw(String text) {
        if (text == null || text.isEmpty()) return;
        engineRunner.post(() -> fireCommitText(text));
    }

    /**
     * Commit text picked from a sub-panel (quick-phrase / clipboard /
     * other panels). Flushes any in-flight composition the same way a
     * language switch does, commits the picked text verbatim, and
     * returns to the mode the user came from.
     */
    public void commitPanelText(String text) {
        if (text == null || text.isEmpty()) return;
        engineRunner.post(() -> {
            // Flush any current Chinese composition to keep state clean.
            if (isChineseLike() && !state.buffer.isEmpty()) {
                String committed = state.committedText();
                String tail = !candidates.isEmpty()
                        ? tradTextOf(candidates.get(0))
                        : state.remaining();
                flushPendingContextChoices();
                fireCommitText(committed + tail);
                resetInput();
            }
            if (mode == KeyboardMode.ENGLISH && englishBuffer.length() > 0) {
                commitEnglishBuffer();
            }
            fireCommitText(text);
            switchModeInternal(previousMode);
        });
    }

    // ===== Key dispatch (posts to engine thread) =====

    public void onKey(SimeKey key) {
        if (key == null) return;
        pendingKeys.incrementAndGet();
        engineRunner.post(() -> {
            pendingKeys.decrementAndGet();
            onKeyInternal(key);
        });
    }

    /** Re-run decoding after the native engine finishes asynchronous startup. */
    public void onEngineReady() {
        engineRunner.post(() -> {
            state.maxContextIds = decoder.contextSize();
            if (isChineseLike() && !state.buffer.isEmpty()) {
                redecodeAndPublish();
            }
        });
    }

    /** Unified candidate pick — routes by mode on the engine thread. */
    public void onCandidatePick(int index) {
        engineRunner.post(() -> {
            flushStaleDecode();
            if (mode == KeyboardMode.ENGLISH) {
                onEnglishCompletionPickInternal(index);
            } else {
                onHanziCandidatePickInternal(index);
            }
        });
    }

    /** Actions of the candidate long-press menu. */
    public enum CandidateAction { MOVE_UP, MOVE_DOWN, BLOCK, MOVE_FIRST, MOVE_LAST }

    private com.shiyu.sime.ime.engine.CandidateTuning tuning;
    private LearnedSentenceStore learnedSentenceStore;
    private ContextualSelectionMemory contextualSelectionMemory;

    /** A manually chosen segment held until the completed composition is committed. */
    private static final class PendingContextChoice {
        final String pinyin;
        final String text;
        final int[] context;
        final int[] tokenIds;
        PendingContextChoice(String pinyin, String text, int[] context, int[] tokenIds) {
            this.pinyin = pinyin;
            this.text = text;
            this.context = context;
            this.tokenIds = tokenIds == null ? new int[0] : tokenIds.clone();
        }
    }
    private final List<PendingContextChoice> pendingContextChoices = new ArrayList<>();
    /** Learning writes are attached to the next successful explicit text commit. */
    private final List<Runnable> pendingLearningActions = new ArrayList<>();

    // ===== Coalescing of rapid typing =====
    /**
     * Keys posted to the engine thread but not started yet. When more keys
     * are already queued behind the one being handled, decoding for it is
     * pointless (its candidates would be replaced a few milliseconds later),
     * so it is skipped; only the last key of a burst decodes and publishes.
     */
    private final java.util.concurrent.atomic.AtomicInteger pendingKeys =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Engine thread only: the state changed but its decode was skipped. */
    private boolean decodeStale = false;

    /** Make sure candidates match the state before anything reads them. */
    private void flushStaleDecode() {
        if (!decodeStale) return;
        decodeStale = false;
        redecodeAndPublish();
    }

    private void redecodeAndPublishCoalesced() {
        if (pendingKeys.get() > 0) {
            decodeStale = true;
            return;
        }
        decodeStale = false;
        redecodeAndPublish();
    }


    /** Rules store used by the long-press menu (null disables the menu). */
    public void setTuning(com.shiyu.sime.ime.engine.CandidateTuning t) {
        engineRunner.post(() -> this.tuning = t);
    }

    /** Install the persistent store for complete sentences selected by the user. */
    public void setLearnedSentenceStore(LearnedSentenceStore store) {
        engineRunner.post(() -> this.learnedSentenceStore = store);
    }

    /** Install the independent memory for manually selected words and context. */
    public void setContextualSelectionMemory(ContextualSelectionMemory memory) {
        engineRunner.post(() -> this.contextualSelectionMemory = memory);
    }

    /** Whether the candidate at the current strip can be reordered. */
    public boolean canTuneCandidates() {
        return tuning != null && mode == KeyboardMode.CHINESE
                && !state.predicting && !inPunctuationPicker
                && !state.buffer.isEmpty();
    }

    /** Candidate long-press menu entry point (升序/降序/屏蔽/首位/末尾). */
    public void onCandidateAction(int index, CandidateAction action) {
        engineRunner.post(() -> {
            flushStaleDecode();
            if (!canTuneCandidates()) return;
            if (index < 0 || index >= candidates.size()) return;
            List<DecodeResult> view = new ArrayList<>(candidates);
            switch (action) {
                case MOVE_UP:    tuning.moveUp(view, index); break;
                case MOVE_DOWN:  tuning.moveDown(view, index); break;
                case MOVE_FIRST: tuning.moveToFirst(view, index); break;
                case MOVE_LAST:  tuning.moveToLast(view, index); break;
                case BLOCK:      tuning.block(view.get(index).text); break;
            }
            redecodeAndPublish();
        });
    }

    /** English mode: tap on the highlighted "current word" cell —
     *  commit the buffer literally as if user pressed space. */
    public void onEnglishLiteralCommit() {
        engineRunner.post(() -> {
            flushStaleDecode();
            if (mode == KeyboardMode.ENGLISH && englishBuffer.length() > 0) {
                commitEnglishBuffer();
            }
        });
    }

    private void onKeyInternal(SimeKey key) {
        if (key.type != KeyType.LETTER && key.type != KeyType.DIGIT
                && key.type != KeyType.SEPARATOR) {
            flushStaleDecode();
        }
        if (inPunctuationPicker
                && key.type != KeyType.NUM_PUNCTUATION
                && key.type != KeyType.BACKSPACE) {
            // Backspace edits the host text but must leave the 1-key
            // punctuation candidates active so the user can still pick
            // a symbol immediately afterward.
            dismissPunctuationPicker(/*publish=*/true);
        }
        switch (key.type) {
            case LETTER:          handleLetter(key.ch); break;
            case DIRECT_LETTER:   fireCommitText(String.valueOf(key.ch)); break;
            case DIGIT:           handleDigit(key.ch); break;
            case SEPARATOR:       handleSeparator(); break;
            case SPACE:           handleSpace(); break;
            case ENTER:           handleEnter(); break;
            case BACKSPACE:       handleBackspace(); break;
            case CLEAR:           resetAll(); break;
            case PUNCTUATION:     handlePunctuation(key.text); break;
            case NUM_PUNCTUATION: showPunctuationPicker(); break;
            case TO_NUMBER:       switchModeInternal(KeyboardMode.NUMBER); break;
            case TO_SYMBOL:       switchModeInternal(KeyboardMode.SYMBOL); break;
            case TO_BACK:         switchModeInternal(previousMode); break;
            case TOGGLE_LANG:     handleToggleLang(); break;
            case EDIT:            fireEditAction(key.text); break;
        }
    }

    // ===== Listener fire methods (post to main thread) =====

    private void fireEditAction(String action) {
        if (action == null) return;
        mainRunner.post(() -> { if (listener != null) listener.onEditAction(action); });
    }

    private void fireCommitText(String text) {
        decoder.onUserCommit(text);
        final List<Runnable> actions = new ArrayList<>(pendingLearningActions);
        pendingLearningActions.clear();
        Runnable confirmLearning = actions.isEmpty() ? null : () -> engineRunner.post(() -> {
            for (Runnable action : actions) action.run();
            decoder.flushUserSentence();
        });
        mainRunner.post(() -> {
            if (listener != null) listener.onCommitText(text, confirmLearning);
        });
    }
    private void fireDeleteBefore(int count) {
        mainRunner.post(() -> { if (listener != null) listener.onDeleteBefore(count); });
    }
    private void fireSendEnter() {
        mainRunner.post(() -> { if (listener != null) listener.onSendEnter(); });
    }
    private void fireSetComposingText(String text) {
        mainRunner.post(() -> { if (listener != null) listener.onSetComposingText(text); });
    }

    private void showPunctuationPicker() {
        // The T9 1-key picker is a toggle: tapping 1 again closes it.
        if (inPunctuationPicker) {
            dismissPunctuationPicker(/*publish=*/true);
            return;
        }
        if (!isChineseLike()) {
            fireCommitText(T9_NUM_PUNCTUATION[0]);
            return;
        }
        // T9: while composing, the 1 key inserts a separator instead
        // of opening the punctuation picker (mirrors Linux sime.cc).
        if (chineseLayout == ChineseLayout.T9 && !state.buffer.isEmpty()) {
            handleSeparator();
            return;
        }
        java.util.ArrayList<DecodeResult> out =
                new java.util.ArrayList<>(T9_NUM_PUNCTUATION.length);
        for (String p : T9_NUM_PUNCTUATION) {
            out.add(new DecodeResult(p, "", 0));
        }
        candidates = out;
        topUnits = "";
        pinyinAlts = Collections.emptyList();
        inPunctuationPicker = true;
        publish();
    }

    private void dismissPunctuationPicker(boolean publish) {
        if (!inPunctuationPicker) return;
        inPunctuationPicker = false;
        candidates = Collections.emptyList();
        if (publish) publish();
    }

    // ===== Letter / digit =====

    private void handleLetter(char c) {
        if (mode == KeyboardMode.ENGLISH) {
            exitPrediction();
            englishBuffer.append(c);
            fireSetComposingText(englishBuffer.toString());
            refreshEnglishCompletions();
            return;
        }
        if (!isChineseLike()) {
            fireCommitText(String.valueOf(c));
            return;
        }
        // QWERTY or T9 fallback letter: insert at lettersEnd, extend the
        // letter region. For QWERTY lettersEnd == buffer.length() so this
        // is equivalent to append.
        String buf = state.buffer;
        state.buffer = buf.substring(0, state.lettersEnd)
                + c
                + buf.substring(state.lettersEnd);
        state.lettersEnd += 1;
        state.cursor = state.lettersEnd;
        redecodeAndPublishCoalesced();
    }

    private void handleDigit(char c) {
        if (!isChineseLike() || chineseLayout != ChineseLayout.T9) {
            fireCommitText(String.valueOf(c));
            return;
        }
        // T9 digit: append to the end of the buffer (after any letters).
        state.buffer = state.buffer + c;
        state.cursor = state.buffer.length();
        redecodeAndPublishCoalesced();
    }

    private void handleSeparator() {
        if (mode == KeyboardMode.ENGLISH) {
            // Apostrophe in English mode: append to buffer (e.g. "don't")
            englishBuffer.append('\'');
            fireSetComposingText(englishBuffer.toString());
            refreshEnglishCompletions();
            return;
        }
        if (!isChineseLike()) {
            fireCommitText("'");
            return;
        }
        // Mirror Linux sime.cc L493: only respond when buffer is non-empty.
        if (state.buffer.isEmpty()) return;
        // Collapse consecutive separators — two '\'' in a row would just
        // grow the decoder net without changing any segmentation.
        if (state.buffer.charAt(state.buffer.length() - 1) == '\'') return;
        // T9: append '\'' to the digit tail as a hard syllable boundary.
        // It stays literal in the buffer and is honored by the C++
        // decoder (InitNumNet / DecodeNumSentence).
        // QWERTY: insert at lettersEnd (which equals buffer length for
        // pure letter input), keeping the old behavior.
        if (chineseLayout == ChineseLayout.T9
                && state.lettersEnd < state.buffer.length()) {
            state.buffer = state.buffer + '\'';
            state.cursor = state.buffer.length();
        } else {
            String buf = state.buffer;
            state.buffer = buf.substring(0, state.lettersEnd)
                    + '\''
                    + buf.substring(state.lettersEnd);
            state.lettersEnd += 1;
            state.cursor = state.lettersEnd;
        }
        redecodeAndPublishCoalesced();
    }

    // ===== Space / Enter =====

    private void handleSpace() {
        if (mode == KeyboardMode.ENGLISH) {
            if (englishBuffer.length() > 0) {
                // Commit the word only; no trailing space is added.
                commitEnglishBuffer();
            } else {
                fireCommitText(" ");
            }
            return;
        }
        if (state.predicting) {
            if (!candidates.isEmpty()) {
                onPredictionPickInternal(0);
            }
            return;
        }
        if (state.buffer.isEmpty()) {
            fireCommitText(" ");
            return;
        }
        if (!candidates.isEmpty()) {
            onHanziCandidatePickInternal(0);
        }
    }

    private void handleEnter() {
        if (mode == KeyboardMode.ENGLISH) {
            if (englishBuffer.length() > 0) {
                // Confirm the word only; no trailing space is added.
                commitEnglishBuffer();
            } else {
                fireSendEnter();
            }
            return;
        }
        if (state.predicting) {
            exitPrediction();
            publish();
            fireSendEnter();
            return;
        }
        if (state.buffer.isEmpty()) {
            fireSendEnter();
            return;
        }
        // Commit what the user actually sees in the preedit:
        //   already-picked hanzi + the decoded pinyin letters.
        // For T9 input "743663" this commits "shenme" (the displayed
        // letters) instead of the raw digits, but for "94'26" it
        // commits "xi'an" — the user-typed separator is preserved
        // while decoder-inserted separators are dropped.
        String tail;
        if (chineseLayout == ChineseLayout.T9
                && topUnits != null && !topUnits.isEmpty()) {
            tail = mergeUnitsWithRawSeparators(state.remaining(), topUnits);
        } else {
            tail = state.remaining();
        }
        String out = state.committedText() + tail;
        flushPendingContextChoices();
        fireCommitText(out);
        resetAll();
    }

    /**
     * Walk {@code units} (decoder-segmented pinyin, e.g. "shen'me" or
     * "xi'an") in lockstep with {@code raw} (the user's actual buffer
     * — digits + any user-typed separators). Letters from units are
     * always emitted, but a {@code '} from units is kept only when
     * the corresponding position in raw also has a {@code '}. Decoder-
     * inserted separators are dropped.
     */
    private static String mergeUnitsWithRawSeparators(String raw, String units) {
        StringBuilder sb = new StringBuilder(units.length());
        int bi = 0;
        for (int ui = 0; ui < units.length(); ui++) {
            char u = units.charAt(ui);
            if (u == '\'') {
                if (bi < raw.length() && raw.charAt(bi) == '\'') {
                    sb.append('\'');
                    bi++;
                }
            } else {
                sb.append(u);
                if (bi < raw.length() && raw.charAt(bi) != '\'') {
                    bi++;
                }
            }
        }
        return sb.toString();
    }

    // ===== Backspace =====

    private void handleBackspace() {
        if (mode == KeyboardMode.ENGLISH) {
            if (englishBuffer.length() > 0) {
                englishBuffer.deleteCharAt(englishBuffer.length() - 1);
                fireSetComposingText(englishBuffer.toString());
                if (englishBuffer.length() > 0) {
                    refreshEnglishCompletions();
                } else {
                    candidates = Collections.emptyList();
                    publish();
                }
            } else {
                fireDeleteBefore(1);
            }
            return;
        }
        if (state.predicting) {
            exitPrediction();
            publish();
            return;
        }
        if (state.buffer.isEmpty() && state.selections.isEmpty()) {
            fireDeleteBefore(1);
            return;
        }

        InputAction top = state.undoStack.peek();
        if (top != null) {
            // An action-level undo is always preferred to raw byte delete.
            state.cancel();
            if (top.type == InputAction.Type.HANZI_PICK) {
                removeLastPendingContextChoice();
                if (!selectionTokenIds.isEmpty()) {
                    selectionTokenIds.remove(selectionTokenIds.size() - 1);
                }
            }
        } else if (!state.buffer.isEmpty()) {
            // No action on the stack — drop the last char of buffer. If
            // it was inside the letter region, shrink lettersEnd.
            int len = state.buffer.length();
            state.buffer = state.buffer.substring(0, len - 1);
            if (state.lettersEnd > state.buffer.length()) {
                state.lettersEnd = state.buffer.length();
            }
            if (state.cursor > state.buffer.length()) {
                state.cursor = state.buffer.length();
            }
        } else if (!state.selections.isEmpty()) {
            // Degenerate fallback: pop a selection.
            state.selections.remove(state.selections.size() - 1);
            removeLastPendingContextChoice();
            if (!selectionTokenIds.isEmpty()) {
                selectionTokenIds.remove(selectionTokenIds.size() - 1);
            }
        }

        if (state.isEmpty()) {
            resetAll();
        } else {
            redecodeAndPublish();
        }
    }

    // ===== Punctuation =====

    private void handlePunctuation(String text) {
        if (text == null || text.isEmpty()) return;
        if (mode == KeyboardMode.ENGLISH && englishBuffer.length() > 0) {
            commitEnglishBuffer();
        }
        if (isChineseLike() && !state.buffer.isEmpty()
                && !candidates.isEmpty()) {
            // Commit first candidate before the punctuation so the
            // in-flight composition isn't lost.
            commitFirstCandidateInline();
        }
        exitPrediction();
        state.clearContext();
        previousCommitEndsWithAsciiWord = false;
        fireCommitText(text);
        publish();
    }

    private void commitFirstCandidateInline() {
        DecodeResult c = candidates.get(0);
        state.select(tradTextOf(c), c.units, c.consumed);
        if (state.fullySelected()) {
            String out = state.committedText();
            learnCompletedSentence(out);
            flushPendingContextChoices();
            queueNativeLearning(state.contextIdsArray(), c.tokenIds);
            fireCommitText(out);
            resetAll();
        } else {
            // Flush the committed portion so the app sees it immediately.
            flushPendingContextChoices();
            fireCommitText(state.committedText());
            // Keep only the remaining raw tail as a fresh state.
            String rem = state.remaining();
            int letterTail = Math.max(0, state.lettersEnd - state.selectedLength());
            state.reset();
            state.buffer = rem;
            state.lettersEnd = letterTail;
            state.cursor = rem.length();
            redecodeAndPublish();
        }
    }

    // ===== Candidate picks =====

    /**
     * Apply a pinyin letter substitution over the current digit region.
     * {@code letters} will replace a leading chunk of digits of length
     * {@code digits.length()} starting at {@link InputState#lettersEnd}.
     */
    public void onPinyinCandidatePick(String digits, String letters, boolean fallback) {
        if (digits == null || letters == null) return;
        engineRunner.post(() -> {
            if (!isChineseLike()) return;
            state.applyLetterPick(digits, letters, fallback);
            redecodeAndPublish();
        });
    }

    public void onPinyinAltPick(int index) {
        engineRunner.post(() -> {
            flushStaleDecode();
            if (index < 0 || index >= pinyinAlts.size()) return;
            if (!isChineseLike()) return;
            PinyinAlt alt = pinyinAlts.get(index);
            while (state.lettersEnd < state.buffer.length()
                    && state.buffer.charAt(state.lettersEnd) == '\'') {
                state.lettersEnd++;
            }
            int start = state.lettersEnd;
            int end = Math.min(start + alt.digitCount, state.buffer.length());
            if (end <= start) return;
            String digits = state.buffer.substring(start, end);
            state.applyLetterPick(digits, alt.letters, false);
            redecodeAndPublish();
        });
    }

    public void onFallbackLetterPick(char letter) {
        engineRunner.post(() -> {
            if (!isChineseLike()) return;
            while (state.lettersEnd < state.buffer.length()
                    && state.buffer.charAt(state.lettersEnd) == '\'') {
                state.lettersEnd++;
            }
            int start = state.lettersEnd;
            if (start >= state.buffer.length()) return;
            String digits = state.buffer.substring(start, start + 1);
            state.applyLetterPick(digits, String.valueOf(letter), true);
            redecodeAndPublish();
        });
    }

    private void onHanziCandidatePickInternal(int index) {
        if (index < 0 || index >= candidates.size()) return;
        if (state.predicting) {
            onPredictionPickInternal(index);
            return;
        }
        DecodeResult c = candidates.get(index);
        if (inPunctuationPicker) {
            fireCommitText(c.text);
            dismissPunctuationPicker(/*publish=*/true);
            return;
        }
        // A manual candidate tap is a positive learning signal. Capture the
        // preceding token context before adding this selection, so the same
        // preference can be recognized when a later sentence reaches it.
        if (!privateField && contextualSelectionMemory != null) {
            pendingContextChoices.add(new PendingContextChoice(
                    c.units, c.text, currentLearningContext(), c.tokenIds));
        }
        state.select(tradTextOf(c), c.units, c.consumed);
        selectionTokenIds.add(c.tokenIds);
        if (state.fullySelected()) {
            String out = state.committedText();
            learnCompletedSentence(out);
            flushPendingContextChoices();
            // Queue each selection in turn so the engine's per-call context
            // matches what the user actually saw at pick time.
            for (int[] selTokens : selectionTokenIds) {
                queueNativeLearning(state.contextIdsArray(), selTokens);
                state.pushContext(selTokens);
            }
            fireCommitText(out);
            previousCommitEndsWithAsciiWord = endsWithAsciiWord(out);
            resetInput();
            showPredictions(false);
        } else {
            redecodeAndPublish();
        }
    }

    private void onPredictionPickInternal(int index) {
        if (index < 0 || index >= candidates.size()) return;
        DecodeResult c = candidates.get(index);
        queueNativeLearning(state.contextIdsArray(), c.tokenIds);
        state.pushContext(c.tokenIds);
        commitPredictionText(tradTextOf(c));
        showPredictions(false);
    }

    /**
     * Commit a picked prediction / completion exactly as it is. No space
     * is added before or after it (the old automatic English word spacing
     * was removed on request); the user types a space when they want one.
     */
    private void commitPredictionText(String text) {
        fireCommitText(text);
        previousCommitEndsWithAsciiWord = endsWithAsciiWord(text);
    }

    private void onEnglishCompletionPickInternal(int index) {
        if (index < 0 || index >= candidates.size()) return;
        DecodeResult c = candidates.get(index);
        state.pushContext(c.tokenIds);
        englishBuffer.setLength(0);
        // English completion is for English buffer — trad doesn't apply
        // (English text doesn't get converted), but call through anyway
        // for consistency.
        commitPredictionText(tradTextOf(c));
        fireSetComposingText("");
        showPredictions(true);
    }

    // ===== Mode switching =====

    public void switchMode(KeyboardMode next) {
        engineRunner.post(() -> switchModeInternal(next));
    }

    private void switchModeInternal(KeyboardMode next) {
        if (next == mode) return;
        // Only remember "real" input modes as previousMode, so TO_BACK from
        // NUMBER/SYMBOL/SETTINGS/QUICK_PHRASE/CLIPBOARD returns to CHINESE or ENGLISH.
        if (mode != KeyboardMode.NUMBER
                && mode != KeyboardMode.SYMBOL
                && mode != KeyboardMode.SETTINGS
                && mode != KeyboardMode.QUICK_PHRASE
                && mode != KeyboardMode.CLIPBOARD
                && mode != KeyboardMode.TEXT_EDIT) {
            this.previousMode = mode;
        }
        // Flush English buffer when leaving ENGLISH mode.
        if (mode == KeyboardMode.ENGLISH && englishBuffer.length() > 0) {
            commitEnglishBuffer();
        }
        this.mode = next;
        // Do not touch InputState — resuming CHINESE should continue the
        // in-flight input. ENGLISH / NUMBER / SYMBOL / SETTINGS don't run
        // the decoder.
        if (next == KeyboardMode.CHINESE) {
            redecodeAndPublish();
        } else {
            // Clear candidate list so the bar shows idle state.
            candidates = Collections.emptyList();
            topUnits = "";
            publish();
        }
    }

    public void switchChineseLayout(ChineseLayout layout) {
        engineRunner.post(() -> {
            if (layout == chineseLayout) return;
            this.chineseLayout = layout;
            if (isChineseLike()) redecodeAndPublish();
        });
    }

    private void handleToggleLang() {
        KeyboardMode target = (mode == KeyboardMode.ENGLISH)
                ? KeyboardMode.CHINESE
                : KeyboardMode.ENGLISH;
        // Flush any in-flight composition when leaving CHINESE.
        if (isChineseLike() && !state.buffer.isEmpty()) {
            String committed = state.committedText();
            String tail;
            if (!candidates.isEmpty()) {
                // Commit first candidate's hanzi instead of raw digits/letters.
                tail = tradTextOf(candidates.get(0));
            } else if (chineseLayout == ChineseLayout.T9
                    && topUnits != null && !topUnits.isEmpty()) {
                tail = mergeUnitsWithRawSeparators(state.remaining(), topUnits);
            } else {
                tail = state.remaining();
            }
            flushPendingContextChoices();
            fireCommitText(committed + tail);
            resetInput();
        }
        // Flush English buffer when leaving ENGLISH.
        if (mode == KeyboardMode.ENGLISH && englishBuffer.length() > 0) {
            commitEnglishBuffer();
        }
        exitPrediction();
        switchModeInternal(target);
    }

    // ===== Decoding =====

    private void redecodeAndPublish() {
        exitPrediction();
        decode();
        publish();
    }

    /** Synchronous decode on engine thread. */
    private void decode() {
        if (!isChineseLike() || state.buffer.isEmpty()) {
            candidates = Collections.emptyList();
            pinyinAlts = Collections.emptyList();
            topUnits = "";
            return;
        }

        int sel = state.selectedLength();
        int len = state.buffer.length();
        int letterStart = Math.min(sel, state.lettersEnd);
        int letterEnd = state.lettersEnd;
        String bufferLetters = (letterEnd > letterStart)
                ? state.buffer.substring(letterStart, letterEnd)
                : "";
        int digitStart = Math.max(sel, state.lettersEnd);
        String digits = (digitStart < len)
                ? state.buffer.substring(digitStart, len)
                : "";

        String start = bufferLetters;
        int startStripped = 0;
        while (start.startsWith("'")) {
            start = start.substring(1);
            startStripped++;
        }

        DecodeResult[] raw;
        if (chineseLayout == ChineseLayout.T9 && !digits.isEmpty()) {
            raw = decoder.decodeNumSentence(start, digits, EXTRA_SENTENCES);
        } else if (!start.isEmpty()) {
            raw = decoder.decodeSentence(start, EXTRA_SENTENCES);
        } else {
            raw = new DecodeResult[0];
        }

        List<DecodeResult> out = new ArrayList<>(raw.length + 4);
        java.util.HashSet<String> seenKeys = new java.util.HashSet<>();
        DecodeResult topCandidate = null;

        // Learned complete sentences and personal-dictionary matches are
        // prepended so the user's own confirmed choices take priority.
        String typedLetters = state.buffer.substring(
                Math.min(sel, state.lettersEnd), state.lettersEnd).replace("'", "");
        String typedDigits = state.buffer.substring(
                Math.max(sel, state.lettersEnd)).replace("'", "");
        String typedCode = chineseLayout == ChineseLayout.T9
                ? encodeT9(typedLetters) + typedDigits
                : typedLetters + typedDigits;
        if (!typedCode.isEmpty()) {
            int consumedInput = state.buffer.length() - sel;
            if (!privateField && learnedSentenceStore != null) {
                String layoutId = learnedLayoutId();
                for (LearnedSentenceStore.Entry entry :
                        learnedSentenceStore.find(layoutId, typedCode.toLowerCase())) {
                    if (tuning != null && tuning.isBlocked(entry.text)) continue;
                    DecodeResult learned = new DecodeResult(
                            entry.text, entry.pinyin, consumedInput);
                    String learnedKey = learned.text + "\u0001" + learned.consumed;
                    if (seenKeys.add(learnedKey)) {
                        out.add(learned);
                        if (topCandidate == null) topCandidate = learned;
                    }
                }
            }
            for (QuickPhraseStore.Entry entry : personalDictionary) {
                // Entries store pinyin letters. On the nine-key the typed
                // code is digits, so compare against the entry's key
                // digits; on the full keyboard compare the letters.
                String entryCode = chineseLayout == ChineseLayout.T9
                        ? encodeT9(entry.pinyin) : entry.pinyin;
                if (entryCode.isEmpty() || !entryCode.equals(typedCode)) continue;
                DecodeResult custom = new DecodeResult(
                        entry.text, entry.pinyin, consumedInput);
                String customKey = custom.text + "\u0001" + custom.consumed;
                if (seenKeys.add(customKey)) {
                    out.add(custom);
                    if (topCandidate == null) topCandidate = custom;
                }
            }
        }

        // Keep decoder results separate from the exact-sentence and personal-
        // dictionary candidates above. Contextual learning is allowed to
        // reorder only native decoder results; it must never displace an exact
        // sentence that the user previously confirmed and that the sentence
        // memory deliberately prepended at score 0.
        List<DecodeResult> decoded = new ArrayList<>(raw.length);
        for (DecodeResult r : raw) {
            String text = r.text != null ? r.text : "";
            if (text.isEmpty()) continue;
            int consumed = r.consumed + startStripped;
            if (consumed <= 0) continue;
            String key = text + "\u0001" + consumed;
            if (!seenKeys.add(key)) continue;
            DecodeResult c = new DecodeResult(text, r.units != null ? r.units : "", consumed, r.tokenIds,
                    r.score, r.fuzzyMatchCount, r.fuzzyPenalty);
            decoded.add(c);
        }
        // Contextual memory only reorders candidates already produced by the
        // decoder. It neither injects candidates nor reorders exact sentence
        // memories / personal dictionary entries. Explicit candidate-tuning
        // rules run afterwards and retain their higher priority.
        if (!privateField && contextualSelectionMemory != null) {
            decoded = new ArrayList<>(contextualSelectionMemory.apply(
                    decoded, currentLearningContext()));
        }
        out.addAll(decoded);
        if (tuning != null) out = new ArrayList<>(tuning.apply(out));
        topCandidate = out.isEmpty() ? null : out.get(0);
        candidates = out;

        String topU = topCandidate != null ? topCandidate.units : "";
        if (!topU.isEmpty()) {
            int rawLen = PinyinUtil.countRealChars(bufferLetters) + PinyinUtil.countRealChars(digits);
            topU = clipUnitsToLetterCount(topU, rawLen);
            if (chineseLayout == ChineseLayout.T9 && !digits.isEmpty()) {
                topU = extendUnitsForUncoveredDigits(topU, digits, rawLen);
            }
        }
        topUnits = topU;

        if (chineseLayout == ChineseLayout.T9 && !digits.isEmpty()) {
            pinyinAlts = computePinyinAltsFromRaw(
                    raw, countSyllables(start), digits.length(), digits,
                    decoder.t9PinyinSyllables(digits, MAX_PINYIN_ALTS));
        } else {
            pinyinAlts = Collections.emptyList();
        }
    }

    /**
     * Truncate a segmented pinyin string to at most {@code maxLetters}
     * real letters, preserving {@code '} separators but dropping any
     * trailing separator left behind by the clip.
     */
    private static String encodeT9(String pinyin) {
        if (pinyin == null || pinyin.isEmpty()) return "";
        StringBuilder digits = new StringBuilder(pinyin.length());
        for (int i = 0; i < pinyin.length(); i++) {
            char c = Character.toLowerCase(pinyin.charAt(i));
            if (c >= 'a' && c <= 'c') digits.append('2');
            else if (c >= 'd' && c <= 'f') digits.append('3');
            else if (c >= 'g' && c <= 'i') digits.append('4');
            else if (c >= 'j' && c <= 'l') digits.append('5');
            else if (c >= 'm' && c <= 'o') digits.append('6');
            else if (c >= 'p' && c <= 's') digits.append('7');
            else if (c >= 't' && c <= 'v') digits.append('8');
            else if (c >= 'w' && c <= 'z') digits.append('9');
            else return "";
        }
        return digits.toString();
    }

    /**
     * The top candidate often covers only the first syllables of a longer
     * nine-key input, so the preedit would show the rest as raw digits
     * (e.g. "pi57"). Fill the uncovered tail with the best-guess pinyin of
     * those digits so the hint stays readable while typing a phrase.
     */
    private String extendUnitsForUncoveredDigits(String units, String digits, int rawLen) {
        String dd = digits.replace("'", "");
        for (int i = 0; i < 3; i++) {
            int missing = rawLen - PinyinUtil.countRealChars(units);
            if (missing <= 0 || missing > dd.length()) break;
            // Guess the next few syllables only (cheap), then loop for the rest.
            String tail = dd.substring(dd.length() - missing);
            if (tail.length() > 10) tail = tail.substring(0, 10);
            String guess = decoder.bestNumUnits(tail);
            if (guess == null || guess.isEmpty()) break;
            guess = clipUnitsToLetterCount(guess, tail.length());
            if (guess.isEmpty()) break;
            units = units + "'" + guess;
        }
        return units;
    }

    private static String clipUnitsToLetterCount(String units, int maxLetters) {
        if (maxLetters <= 0) return "";
        int letters = 0;
        int end = 0;
        for (int i = 0; i < units.length(); i++) {
            char c = units.charAt(i);
            if (c == '\'') {
                end = i + 1;
                continue;
            }
            if (letters >= maxLetters) break;
            letters++;
            end = i + 1;
        }
        while (end > 0 && units.charAt(end - 1) == '\'') end--;
        return units.substring(0, end);
    }

    /** Max single-syllable alternatives to show in the T9 left strip. */
    private static final int MAX_PINYIN_ALTS = 12;

    /**
     * Extract pinyin alternatives for the next syllable position from a
     * {@code raw} array returned by {@code DecodeNumSentence}, then fill
     * recall gaps with native pinyin syllables derived directly from the
     * digit tail.
     *
     * <p>The decoder built its lattice with {@code start} syllables fixed
     * in columns {@code [0, p)}, so every result's {@code units} string
     * begins with the same {@code start.split("'")} sequence (the first
     * {@code startSyllables} segments). Stripping those by index gives
     * the next-position syllable directly — no value-level prefix match
     * is needed because the lattice already enforces it.
     *
     * <p>Each pinyin letter maps to exactly one T9 digit, so
     * {@code digitCount = nextSyllable.length()}. A syllable longer than
     * the available digits ({@code maxDigits}) is a tail-expanded
     * candidate (the lattice's {@code tail_expansion} pass invents
     * "what the user might be typing" syllables that span past the
     * existing buffer); the alt strip rejects these because picking
     * one would consume more digits than the buffer actually has.
     */
    private static final String[] T9_LETTERS = {
        null, null, "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"
    };

    private static List<PinyinAlt> computePinyinAltsFromRaw(
            DecodeResult[] raw, int startSyllables, int maxDigits,
            String digits, String[] nativeSyllables) {
        java.util.LinkedHashMap<String, PinyinAlt> seen =
                new java.util.LinkedHashMap<>();
        for (DecodeResult r : raw) {
            if (r.units == null || r.units.isEmpty()) continue;
            String[] segs = r.units.split("'");
            if (segs.length <= startSyllables) continue;
            String nextSyl = segs[startSyllables];
            if (nextSyl.isEmpty()) continue;
            if (nextSyl.length() > maxDigits) continue;  // tail-expanded
            // If the segment appears literally in the decoded text, it
            // was committed as-is (English / brand name like "iPhone")
            // rather than as pinyin converted to hanzi — skip it.
            if (r.text != null && r.text.contains(nextSyl)) continue;
            if (seen.containsKey(nextSyl)) continue;
            seen.put(nextSyl, new PinyinAlt(nextSyl, nextSyl, nextSyl.length()));
            if (seen.size() >= MAX_PINYIN_ALTS) break;
        }
        if (nativeSyllables != null) {
            for (String syl : nativeSyllables) {
                if (seen.size() >= MAX_PINYIN_ALTS) break;
                if (syl == null || syl.isEmpty()) continue;
                if (syl.length() > maxDigits) continue;
                if (!seen.containsKey(syl)) {
                    seen.put(syl, new PinyinAlt(syl, syl, syl.length()));
                }
            }
        }
        // Always add the current digit's individual letters as fallback.
        char firstDigit = 0;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c >= '2' && c <= '9') { firstDigit = c; break; }
        }
        if (firstDigit != 0) {
            String letters = T9_LETTERS[firstDigit - '0'];
            if (letters != null) {
                for (int i = 0; i < letters.length(); i++) {
                    if (seen.size() >= MAX_PINYIN_ALTS) break;
                    String l = String.valueOf(letters.charAt(i));
                    if (!seen.containsKey(l)) {
                        seen.put(l, new PinyinAlt(l, l, 1));
                    }
                }
            }
        }
        return new ArrayList<>(seen.values());
    }

    /** Number of non-empty pinyin syllables in a {@code '}-separated string. */
    private static int countSyllables(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 0;
        for (String seg : s.split("'")) {
            if (!seg.isEmpty()) n++;
        }
        return n;
    }

    // ===== Prediction =====

    /**
     * Show prediction candidates (NextTokens) if context is available.
     *
     * @param enOnly true when in English mode
     */
    private void showPredictions(boolean enOnly) {
        if (!predictionEnabled || privateField || state.contextIds.isEmpty()) {
            exitPrediction();
            publish();
            return;
        }
        DecodeResult[] results = decoder.nextTokens(
                state.contextIdsArray(), PREDICTION_LIMIT, enOnly);
        if (results.length == 0) {
            exitPrediction();
            publish();
            return;
        }
        state.predicting = true;
        candidates = new ArrayList<>(results.length);
        for (DecodeResult r : results) candidates.add(r);
        topUnits = "";
        pinyinAlts = Collections.emptyList();
        publish();
    }

    private void exitPrediction() {
        state.predicting = false;
        candidates = Collections.emptyList();
        topUnits = "";
    }

    /** Include the output script in the key so traditional text is not replayed in simplified mode. */
    private String learnedLayoutId() {
        String layoutId = chineseLayout == ChineseLayout.T9 ? "T9" : "QWERTY";
        return layoutId + (traditionalEnabled ? "_TRAD" : "_SIMPLIFIED");
    }

    /** Queue native language-model learning in the same transaction as the visible text. */
    private void queueNativeLearning(int[] context, int[] tokens) {
        if (privateField || context == null || tokens == null || tokens.length == 0) return;
        final int[] contextCopy = context.clone();
        final int[] tokenCopy = tokens.clone();
        pendingLearningActions.add(() -> decoder.learnUserSentence(contextCopy, tokenCopy));
    }

    /** Queue selected-word learning for the successful composition commit. */
    private void flushPendingContextChoices() {
        if (!privateField && contextualSelectionMemory != null
                && !pendingContextChoices.isEmpty()) {
            final List<PendingContextChoice> choices = new ArrayList<>(pendingContextChoices);
            final int[][] following = new int[choices.size()][];
            for (int i = 0; i < choices.size(); i++) {
                following[i] = followingTokenContext(i);
            }
            final ContextualSelectionMemory store = contextualSelectionMemory;
            pendingLearningActions.add(() -> {
                for (int i = 0; i < choices.size(); i++) {
                    PendingContextChoice choice = choices.get(i);
                    store.learn(choice.pinyin, choice.text, choice.context,
                            choice.tokenIds, following[i]);
                }
            });
        }
        pendingContextChoices.clear();
    }

    private void removeLastPendingContextChoice() {
        if (!pendingContextChoices.isEmpty()) {
            pendingContextChoices.remove(pendingContextChoices.size() - 1);
        }
    }

    /** Collect up to two token IDs selected after the given segment. */
    private int[] followingTokenContext(int choiceIndex) {
        int[] result = new int[2];
        int count = 0;
        for (int i = choiceIndex + 1;
                i < pendingContextChoices.size() && count < result.length; i++) {
            int[] tokens = pendingContextChoices.get(i).tokenIds;
            if (tokens == null) continue;
            for (int token : tokens) {
                result[count++] = token;
                if (count == result.length) break;
            }
        }
        if (count == result.length) return result;
        int[] trimmed = new int[count];
        System.arraycopy(result, 0, trimmed, 0, count);
        return trimmed;
    }

    /**
     * Context visible at the current selection point: committed history plus
     * earlier manually selected segments in the current composition.
     */
    private int[] currentLearningContext() {
        List<Integer> ids = new ArrayList<>(state.contextIds);
        for (int[] selected : selectionTokenIds) {
            if (selected == null) continue;
            for (int token : selected) ids.add(token);
        }
        int keep = Math.min(2, ids.size());
        int[] result = new int[keep];
        for (int i = 0; i < keep; i++) {
            result[i] = ids.get(ids.size() - keep + i);
        }
        return result;
    }

    /** Queue the exact input code and chosen pinyin for the successful commit callback. */
    private void learnCompletedSentence(String text) {
        if (privateField || learnedSentenceStore == null || state.selections.isEmpty()) return;
        String code = LearnedSentenceStore.originalInputCode(state);
        String pinyin = LearnedSentenceStore.selectedPinyin(state);
        if (code.isEmpty() || pinyin.length() == 0 || text == null || text.isEmpty()) return;
        String layoutId = learnedLayoutId();
        final LearnedSentenceStore store = learnedSentenceStore;
        final String learnedCode = code;
        final String learnedPinyin = pinyin;
        final String learnedText = text;
        pendingLearningActions.add(() -> store.learn(
                layoutId, learnedCode, learnedPinyin, learnedText));
    }

    /**
     * Reset input state but keep prediction context.
     */
    private void resetInput() {
        state.reset();
        selectionTokenIds.clear();
        pendingContextChoices.clear();
        pinyinAlts = Collections.emptyList();
        topUnits = "";
        inPunctuationPicker = false;
    }

    // ===== English completion =====

    private void refreshEnglishCompletions() {
        DecodeResult[] results = decoder.getTokens(
                englishBuffer.toString(), PREDICTION_LIMIT, true);
        candidates = new ArrayList<>(results.length);
        for (DecodeResult r : results) candidates.add(r);
        topUnits = "";
        pinyinAlts = Collections.emptyList();
        publish();
    }

    /**
     * Commit the English buffer as-is (user pressed space without
     * picking a completion).
     */
    private void commitEnglishBuffer() {
        commitEnglishBuffer(false);
    }

    /** Commit the current English preedit; space is explicit when requested. */
    private void commitEnglishBuffer(boolean appendSpace) {
        String text = englishBuffer.toString();
        englishBuffer.setLength(0);
        fireCommitText(appendSpace ? text + " " : text);
        previousCommitEndsWithAsciiWord = endsWithAsciiWord(text);
        fireSetComposingText("");
        candidates = Collections.emptyList();
        // No context push for raw text (no token IDs)
        publish();
    }

    // ===== Helpers =====

    private static boolean startsWithAsciiWord(String text) {
        return text != null && !text.isEmpty() && isAsciiWordChar(text.charAt(0));
    }

    private static boolean endsWithAsciiWord(String text) {
        return text != null && !text.isEmpty()
                && isAsciiWordChar(text.charAt(text.length() - 1));
    }

    private static boolean isAsciiWordChar(char c) {
        return c <= 0x7f && (Character.isLetterOrDigit(c) || c == '_');
    }

    private void resetAll() {
        state.reset();
        selectionTokenIds.clear();
        pendingContextChoices.clear();
        candidates = Collections.emptyList();
        pinyinAlts = Collections.emptyList();
        topUnits = "";
        inPunctuationPicker = false;
        englishBuffer.setLength(0);
        previousCommitEndsWithAsciiWord = false;
        publish();
    }

    private void publish() {
        Snapshot snap = new Snapshot(
                state.copy(),
                tradTransform(candidates),
                new ArrayList<>(pinyinAlts),
                topUnits,
                mode,
                chineseLayout,
                englishBuffer.toString(),
                inPunctuationPicker);
        mainRunner.post(() -> {
            if (observer != null) observer.onStateChanged(snap);
        });
    }

    /**
     * Apply traditional conversion to a candidate list (per-token via
     * {@link com.shiyu.sime.ime.data.TraditionalConverter}). Returns
     * a defensive copy regardless so the caller can mutate freely.
     */
    private List<DecodeResult> tradTransform(List<DecodeResult> in) {
        if (!traditionalEnabled || tradConverter == null || in.isEmpty()) {
            return new ArrayList<>(in);
        }
        List<DecodeResult> out = new ArrayList<>(in.size());
        for (DecodeResult r : in) {
            String trad = tradConverter.convert(r.tokenIds, r.text);
            if (trad.equals(r.text)) {
                out.add(r);
            } else {
                out.add(new DecodeResult(trad, r.units, r.consumed, r.tokenIds, r.score,
                        r.fuzzyMatchCount, r.fuzzyPenalty));
            }
        }
        return out;
    }

    /** Trad-aware text accessor for a single DecodeResult. */
    private String tradTextOf(DecodeResult r) {
        if (!traditionalEnabled || tradConverter == null) return r.text;
        return tradConverter.convert(r.tokenIds, r.text);
    }
}
