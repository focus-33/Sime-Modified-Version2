package com.shiyu.sime;

import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import com.shiyu.sime.ime.InputKernel;
import com.shiyu.sime.ime.InputState;
import com.shiyu.sime.ime.InputView;
import com.shiyu.sime.ime.KeyboardMode;
import com.shiyu.sime.ime.data.ClipboardWatcher;
import com.shiyu.sime.ime.data.TraditionalConverter;
import com.shiyu.sime.ime.engine.CandidateTuning;
import com.shiyu.sime.ime.engine.ContextualSelectionMemory;
import com.shiyu.sime.ime.engine.FuzzyPinyinDecoder;
import com.shiyu.sime.ime.engine.LearnedSentenceStore;
import com.shiyu.sime.ime.engine.SimeEngineDecoder;
import com.shiyu.sime.ime.engine.TunedDecoder;
import com.shiyu.sime.ime.prefs.SimePrefs;

import java.io.File;
import java.text.BreakIterator;

/**
 * Thin IME host. Life-cycle + {@link InputConnection} bridging only —
 * all input semantics live in {@link InputKernel}.
 */
public class SimeService extends InputMethodService
        implements InputKernel.Listener {

    private static final String TAG = "SimeIME";

    private static volatile SimeService instance;
    private SimeEngine engine;
    private InputKernel kernel;
    private InputView inputView;
    private ClipboardWatcher clipboardWatcher;
    private TraditionalConverter tradConverter;

    /**
     * When non-null, commits / deletes / preedit are routed here
     * instead of to the host app's {@link InputConnection}. Used by
     * the in-IME phrase composer (AddPhraseView) so the user can type
     * a quick phrase using Sime's own keyboard.
     */
    private com.shiyu.sime.ime.compose.ComposeSink composeSink;

    public void setComposeSink(com.shiyu.sime.ime.compose.ComposeSink sink) {
        this.composeSink = sink;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        engine = new SimeEngine();
        engine.start(getApplicationContext());
        File rankDir = new File(getApplicationContext().getFilesDir(), "sime");
        //noinspection ResultOfMethodCallIgnored
        rankDir.mkdirs();
        CandidateTuning tuning = new CandidateTuning(new File(rankDir, "candidate_rank.txt"));
        CandidateTuning.setShared(tuning);
        kernel = new InputKernel(new TunedDecoder(
                new FuzzyPinyinDecoder(new SimeEngineDecoder(engine)), tuning));
        kernel.setTuning(tuning);
        kernel.setLearnedSentenceStore(
                new LearnedSentenceStore(new File(rankDir, "learned_sentences.txt")));
        kernel.setContextualSelectionMemory(new ContextualSelectionMemory(
                new File(rankDir, "contextual_selection_memory.txt")));
        engine.setOnReadyListener(kernel::onEngineReady);
        kernel.setPersonalDictionary(
                new com.shiyu.sime.ime.data.QuickPhraseStore(this).getEntries());
        clipboardWatcher = new ClipboardWatcher(this);
        clipboardWatcher.start();
        // Load the trad table on a background thread so we don't stall
        // IME startup on the ~2MB file read. Engine.start() already runs
        // its own background extraction; we piggyback after a short delay
        // so the assets are likely on disk by the time we read.
        tradConverter = new TraditionalConverter();
        kernel.setTraditionalConverter(tradConverter);
        new Thread(() -> {
            File simeDir = new File(getApplicationContext().getFilesDir(), "sime");
            File ftDict = new File(simeDir, "sime.ft.dict.txt");
            // Wait briefly for the native engine's background extraction.
            for (int i = 0; i < 30; i++) {
                if (ftDict.exists()) break;
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
            tradConverter.load(ftDict);
        }, "sime-asset-load").start();
        applyPrefs();
    }

    @Override
    public void onStartInputView(android.view.inputmethod.EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (engine != null) engine.ensureStarted(getApplicationContext());
        if (kernel != null) {
            kernel.setPersonalDictionary(
                    new com.shiyu.sime.ime.data.QuickPhraseStore(this).getEntries());
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        if (clipboardWatcher != null) clipboardWatcher.stop();
        if (kernel != null) kernel.detach();
        if (engine != null) engine.stop();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // System is asking us to release memory. Drop the trie sep_cache
        // (rebuilds lazily on next decode at small one-shot cost) and
        // flush user-sentence history so unsaved learns aren't lost if
        // the process is reaped under pressure.
        if (level >= TRIM_MEMORY_RUNNING_MODERATE && engine != null) {
            engine.resetCaches();
            engine.flushUserSentence();
        }
    }

    private void applyPrefs() {
        SimePrefs prefs = new SimePrefs(this);
        kernel.setChineseLayout(prefs.getChineseLayout());
        kernel.setPredictionEnabled(prefs.getPredictionEnabled());
        kernel.setTraditionalEnabled(prefs.getTraditionalEnabled());
        kernel.setAutoSpaceEnglishWords(prefs.getAutoSpaceEnglishWords());
        com.shiyu.sime.ime.feedback.InputFeedbacks.setVibrationEnabled(
                prefs.getVibrationEnabled());
    }

    @Override
    public View onCreateInputView() {
        inputView = new InputView(this);
        InputKernel.Snapshot initialSnapshot = new InputKernel.Snapshot(
                new InputState(), java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), "",
                KeyboardMode.CHINESE, kernel.getInitialChineseLayout(), "");
        inputView.attach(kernel, initialSnapshot);
        kernel.attach(this, inputView);
        inputView.setOnHideRequest(() -> requestHideSelf(0));
        return inputView;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK
                && com.shiyu.sime.ime.candidates.CandidateMenu.dismissActive()) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        // Pick up any layout change made in SettingsActivity since we last ran.
        applyPrefs();
        // First launch after install (or an earlier failed start): make
        // sure the engine is loading, instead of staying silent forever.
        if (engine != null) engine.ensureStarted(getApplicationContext());
        // Detect password / OTP-style fields. While focused there:
        //   - clipboard watcher pauses (don't capture pasted secrets)
        //   - prediction is suppressed (avoid leaking previous-context
        //     suggestions into a sensitive field)
        boolean privateField = isPrivateField(attribute);
        if (clipboardWatcher != null) clipboardWatcher.setPaused(privateField);
        if (kernel != null) kernel.setPrivateField(privateField);
        if (kernel != null) kernel.onStartInput();
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        if (clipboardWatcher != null) clipboardWatcher.setPaused(false);
        if (kernel != null) kernel.setPrivateField(false);
        if (kernel != null) kernel.onFinishInput();
    }

    private static boolean isPrivateField(EditorInfo info) {
        if (info == null) return false;
        // Apps / autofill providers can opt out of personalized learning
        // explicitly. Honor that flag wherever Android sets it.
        if ((info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) {
            return true;
        }
        int type = info.inputType;
        int cls = type & InputType.TYPE_MASK_CLASS;
        int variation = type & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_TEXT) {
            if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) {
                return true;
            }
        }
        if (cls == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
            return true;
        }
        return false;
    }

    /** Import a native history file that has already passed exact VOCAB-signature validation. */
    public static boolean importMergedNativeLearning(File mergedFile) {
        SimeService service = instance;
        return service != null && service.engine != null
                && service.engine.replaceUserSentenceFromMergedFile(mergedFile);
    }

    public static String currentNativeVocabSignature() {
        SimeService service = instance;
        if (service == null || service.engine == null) return "";
        service.engine.flushUserSentence();
        return service.engine.userSentenceVocabSignature();
    }

    // ===== InputKernel.Listener =====

    @Override
    public void onCommitText(String text) {
        onCommitText(text, null);
    }

    @Override
    public void onCommitText(String text, Runnable confirmLearning) {
        if (composeSink != null) {
            composeSink.onCommit(text);
            // Text in Sime's own phrase editor is not host-document text.
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        boolean committed = ic.commitText(text, 1);
        // A successful explicit candidate commit is the learning signal. Do not
        // depend on ExtractedText: many host editors do not expose a reliable snapshot.
        if (committed && confirmLearning != null && text != null && !text.isEmpty()) {
            confirmLearning.run();
        }
    }

    @Override
    public void onDeleteBefore(int count) {
        if (composeSink != null) {
            composeSink.onDelete(count);
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        // Walk back `count` grapheme clusters and delete that many UTF-16
        // chars in one call. Without this, emoji (surrogate pairs) and
        // ZWJ sequences (家庭表情等) take multiple presses to disappear.
        ic.deleteSurroundingText(graphemeUnitsBefore(ic, count), 0);
    }

    private static int graphemeUnitsBefore(InputConnection ic, int graphemes) {
        // Pull a generous window before the cursor — enough to cover the
        // longest plausible ZWJ emoji sequence times `graphemes`.
        CharSequence before = ic.getTextBeforeCursor(64 * graphemes, 0);
        if (before == null || before.length() == 0) return graphemes;
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(before.toString());
        int boundary = before.length();
        for (int i = 0; i < graphemes; i++) {
            int prev = it.preceding(boundary);
            if (prev == BreakIterator.DONE) break;
            boundary = prev;
        }
        int deleted = before.length() - boundary;
        // Fallback to char-count if the buffer was empty / iterator did
        // nothing — never return 0 when the caller asked for a deletion.
        return deleted > 0 ? deleted : graphemes;
    }

    @Override
    public void onSendEnter() {
        if (composeSink != null) {
            // Enter inside the composer: ignore (user uses 完成 button).
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
    }

    @Override
    public void onSetComposingText(String preedit) {
        if (composeSink != null) {
            composeSink.onPreedit(preedit == null ? "" : preedit);
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (preedit == null || preedit.isEmpty()) {
            ic.finishComposingText();
        } else {
            ic.setComposingText(preedit, 1);
        }
    }

    @Override
    public void onEditAction(String action) {
        if (composeSink != null || action == null) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        boolean select = action.endsWith("+s");
        String a = select ? action.substring(0, action.length() - 2) : action;
        switch (a) {
            case "select_on":
            case "select_off":
                selAnchor = -1; // re-anchor at the cursor on the next move
                return;
            case "cancel_selection": {
                CharSequence selected = ic.getSelectedText(0);
                if (selected != null && selected.length() > 0) {
                    // Collapse the active selection at its right edge.
                    sendCursorKey(ic, KeyEvent.KEYCODE_DPAD_RIGHT, false);
                }
                selAnchor = -1;
                return;
            }
            case "left": case "right": case "up": case "down":
            case "home": case "end":
                if (select) moveSelecting(ic, a);
                else sendCursorKey(ic, cursorKeyCode(a), false);
                return;
            case "selectall": ic.performContextMenuAction(android.R.id.selectAll); break;
            case "cut":   ic.performContextMenuAction(android.R.id.cut); break;
            case "copy":  ic.performContextMenuAction(android.R.id.copy); break;
            case "paste": ic.performContextMenuAction(android.R.id.paste); break;
            case "undo":  sendCtrlKey(ic, KeyEvent.KEYCODE_Z, false); break;
            case "redo":  sendCtrlKey(ic, KeyEvent.KEYCODE_Z, true); break;
            case "backspace": {
                // Like Gboard: with a selection, delete it; otherwise one char.
                CharSequence selected = ic.getSelectedText(0);
                if (selected != null && selected.length() > 0) {
                    ic.commitText("", 1);
                } else {
                    ic.deleteSurroundingText(graphemeUnitsBefore(ic, 1), 0);
                }
                break;
            }
            default: break;
        }
    }

    /** Fixed end of the selection being extended in 选择 mode (-1 = unset). */
    private int selAnchor = -1;

    private static int cursorKeyCode(String a) {
        switch (a) {
            case "left":  return KeyEvent.KEYCODE_DPAD_LEFT;
            case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "up":    return KeyEvent.KEYCODE_DPAD_UP;
            case "down":  return KeyEvent.KEYCODE_DPAD_DOWN;
            case "home":  return KeyEvent.KEYCODE_MOVE_HOME;
            default:      return KeyEvent.KEYCODE_MOVE_END;
        }
    }

    /**
     * 选择 mode, like Gboard: the selection starts where the cursor was
     * and grows/shrinks as the cursor keys move. Left/right/home/end are
     * computed from the editor's text and applied with setSelection, which
     * is exact in every editor that exposes its text. Up/down (which need
     * the editor's line layout) and editors that expose no text fall back
     * to a real Shift+key press.
     */
    private void moveSelecting(InputConnection ic, String a) {
        if (!a.equals("up") && !a.equals("down")) {
            ExtractedTextRequest req = new ExtractedTextRequest();
            req.hintMaxChars = 100000;
            req.hintMaxLines = 10000;
            ExtractedText et = ic.getExtractedText(req, 0);
            if (et != null && et.text != null && et.selectionStart >= 0
                    && et.selectionEnd >= 0) {
                int off = et.startOffset;
                int s = et.selectionStart + off;
                int e = et.selectionEnd + off;
                if (selAnchor < 0 || (selAnchor != s && selAnchor != e)) selAnchor = s;
                int active = (selAnchor == s) ? e : s;
                CharSequence t = et.text;
                int rel = Math.max(0, Math.min(t.length(), active - off));
                int next;
                switch (a) {
                    case "left":
                        next = rel > 0 ? Character.offsetByCodePoints(t, rel, -1) : 0;
                        break;
                    case "right":
                        next = rel < t.length()
                                ? Character.offsetByCodePoints(t, rel, 1) : t.length();
                        break;
                    case "home":
                        next = rel;
                        while (next > 0 && t.charAt(next - 1) != '\n') next--;
                        break;
                    default: // "end"
                        next = rel;
                        while (next < t.length() && t.charAt(next) != '\n') next++;
                        break;
                }
                ic.setSelection(selAnchor, next + off);
                return;
            }
        }
        shiftKeyMove(ic, cursorKeyCode(a));
    }

    /** Hardware-style Shift+key: press Shift, press the key, release both. */
    private static void shiftKeyMove(InputConnection ic, int keyCode) {
        int meta = KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        long now = android.os.SystemClock.uptimeMillis();
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_SHIFT_LEFT, 0, meta));
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta));
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta));
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0));
    }

    private static void sendCursorKey(InputConnection ic, int keyCode, boolean shift) {
        int meta = shift ? (KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON) : 0;
        long now = android.os.SystemClock.uptimeMillis();
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta));
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta));
    }

    private static void sendCtrlKey(InputConnection ic, int keyCode, boolean shift) {
        int meta = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON
                | (shift ? (KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON) : 0);
        long now = android.os.SystemClock.uptimeMillis();
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta));
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta));
    }
}
