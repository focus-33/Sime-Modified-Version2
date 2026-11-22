package com.shiyu.sime.ime.candidates;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.shiyu.sime.ime.InputKernel;
import com.shiyu.sime.ime.InputState;
import com.shiyu.sime.ime.KeyboardMode;
import com.shiyu.sime.ime.PinyinUtil;
import com.shiyu.sime.ime.engine.DecodeResult;
import com.shiyu.sime.ime.feedback.InputFeedbacks;
import com.shiyu.sime.ime.theme.SimeTheme;
import com.shiyu.sime.ime.theme.Typography;

import java.util.List;

/**
 * Candidate bar with two states.
 *
 * <p>Idle: {@code ⚙ ... ∨}. Active: {@code [preedit]  hanzi hanzi ...  ∨}.
 */
public class CandidatesBar extends FrameLayout {
    /**
     * Keep the horizontally scrolling strip bounded. The decoder can return
     * close to 200 T9 Layer-2 alternatives; binding all of them on every key
     * delays the next frame even though only a handful fit on screen. The
     * expanded candidate grid still receives the complete list.
     */
    private static final int MAX_STRIP_CANDIDATES = 24;

    /**
     * Single listener for all bar events. Default no-op methods so each
     * caller only overrides what it needs.
     */
    public interface Listener {
        default void onCandidatePick(int index) {}
        /** Candidate held for 800 ms; {@code anchor} is the cell view. */
        default void onCandidateLongPress(int index, View anchor) {}
        default void onSettingsClick() {}
        /** Toolbar shortcut: "traditional", "clipboard", "textedit", "language". */
        default void onShortcutClick(String key) {}
        default void onHideClick() {}
        default void onExpandToggle() {}
        default void onDismissPrediction() {}
        default void onSettingsBackClick() {}
        /** English mode: tap on the highlighted "current word" cell —
         *  commits the English buffer literally. */
        default void onEnglishLiteralCommit() {}
    }

    private final SimeTheme theme;

    private LinearLayout idleView;
    private LinearLayout activeView;
    private TextView preeditView;
    private LinearLayout candidateContainer;
    private LockableHorizontalScrollView candidateScroll;
    private boolean expanded = false;

    /** HorizontalScrollView whose horizontal scrolling can be disabled
     *  per state. Children still receive touches (so candidate taps
     *  still register), only the scroll behavior is suppressed. */
    private static class LockableHorizontalScrollView extends HorizontalScrollView {
        boolean scrollLocked = false;
        LockableHorizontalScrollView(Context ctx) { super(ctx); }
        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
            if (scrollLocked) return false;
            return super.onInterceptTouchEvent(ev);
        }
        @Override
        public boolean onTouchEvent(android.view.MotionEvent ev) {
            if (scrollLocked) return false;
            return super.onTouchEvent(ev);
        }
    }
    private TextView expandToggleButton;
    /** Idle view's leftmost button — flips between ⚙ and ← in settings mode. */
    private ImageView idleLeftButton;
    private final java.util.Map<String, View> shortcuts = new java.util.HashMap<>();
    private TextView languageShortcut;
    private TextView traditionalShortcut;
    /** Share of the bar width, measured from the right edge, where the expand button is centred (0 = at the edge). */
    private float expandAlignFraction = 0f;
    /**
     * 1-key symbol list: move all symbols this many dp to the left. The gap on
     * the left shrinks by this amount and the gap on the right grows by it, so
     * the symbols end up 2x this value closer to the left than before. Raise
     * it if "@" is still too far from the left border (keep it between 0 and 8).
     */
    private static final int PICKER_SHIFT_LEFT_DP = 4;
    /**
     * 1-key symbol list: while it is shown the (empty) pinyin line above it is
     * still there, which pushes the symbols towards the bottom. Move the
     * symbols up by this fraction of that empty line's height: 0 = unchanged,
     * 0.33 = one third (default), 0.5 = halfway, 1 = all the way up.
     */
    private static final float PICKER_RAISE_FRACTION = 0.33f;
    /** True while the 1-key symbol list is shown: spread the symbols evenly. */
    private boolean pickerMode = false;
    private boolean languageEnglish = false;
    private String activeShortcut = null;
    private boolean traditionalOn = false;
    private boolean settingsMode = false;

    private Listener listener = new Listener() {};

    /** When true, the right-edge button shows "×" and dismisses the
     *  prediction strip instead of expanding the candidates grid.
     *  The 10-prediction limit makes the expand affordance redundant. */
    private boolean predicting = false;

    /** Cached count of active candidates from the last render — used to
     *  restore visibility when collapsing the expanded grid (since the
     *  bar hides truncated cells while expanded, and no re-render
     *  happens on the collapse-only state change). */
    private int activeCandidateCount = 0;

    public CandidatesBar(Context context) {
        super(context);
        theme = SimeTheme.fromContext(context);
        init();
    }

    public CandidatesBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        theme = SimeTheme.fromContext(context);
        init();
    }

    private void init() {
        setBackgroundColor(theme.barBackground);
        buildIdle();
        buildActive();
        showIdle();
    }

    private void buildIdle() {
        idleView = new LinearLayout(getContext());
        idleView.setOrientation(LinearLayout.HORIZONTAL);
        idleView.setGravity(Gravity.CENTER_VERTICAL);
        idleView.setPadding(dp(4), 0, dp(4), 0);

        idleLeftButton = iconImage(com.shiyu.sime.R.drawable.ic_toolbar_gear);
        InputFeedbacks.wireClick(idleLeftButton, () -> {
            if (settingsMode) listener.onSettingsBackClick();
            else listener.onSettingsClick();
        });
        addToIdle(idleLeftButton);

        // Shortcuts, in order, right after the settings gear.
        // 简繁转换 occupies the former 表情 shortcut position.
        addTraditionalShortcut();
        addShortcut("clipboard", com.shiyu.sime.R.drawable.ic_toolbar_clipboard);
        addShortcut("textedit", com.shiyu.sime.R.drawable.ic_toolbar_textedit);
        addLanguageShortcut();

        ImageView hide = iconImage(com.shiyu.sime.R.drawable.ic_toolbar_hide);
        InputFeedbacks.wireClick(hide, () -> listener.onHideClick());
        addToIdle(hide);
        // Closing spacer so the space left over is shared evenly everywhere.
        idleView.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1f));

        addView(idleView, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private void buildActive() {
        // Two-row stack: a slim preedit row on top of the candidates
        // row. This way long pinyin no longer pushes the hanzi off the
        // visible bar — they live on independent rows.
        activeView = new LinearLayout(getContext());
        activeView.setOrientation(LinearLayout.VERTICAL);
        activeView.setPadding(dp(8), 0, dp(8), 0);

        // ===== Top row: preedit pinyin (left-aligned, small) =====
        preeditView = new TextView(getContext());
        preeditView.setTextSize(TypedValue.COMPLEX_UNIT_SP, Typography.BODY);
        preeditView.setTextColor(theme.preeditText);
        preeditView.setSingleLine(true);
        preeditView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        preeditView.setPadding(dp(4), dp(2), dp(8), 0);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        preeditView.setLayoutParams(pLp);
        activeView.addView(preeditView);

        // ===== Bottom row: candidates scroll + expand toggle =====
        // Gravity END so the toggle button stays glued to the right
        // edge even when the scroll is hidden in expanded mode (where
        // the toggle is the only remaining child).
        LinearLayout bottomRow = new LinearLayout(getContext());
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        bottomRow.setLayoutParams(brLp);

        candidateScroll = new LockableHorizontalScrollView(getContext());
        candidateScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        // No extra gap: the weighted scroll view simply ends where the fixed
        // expand button starts, so candidates use all the room up to it and
        // are still never drawn underneath it.
        sLp.rightMargin = 0;
        candidateScroll.setLayoutParams(sLp);
        candidateContainer = new LinearLayout(getContext());
        candidateContainer.setOrientation(LinearLayout.HORIZONTAL);
        // Pin candidates to the top of the bottom row so they hug the
        // preedit instead of vertically centering with a gap below it.
        candidateContainer.setGravity(Gravity.TOP);
        candidateScroll.addView(candidateContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        bottomRow.addView(candidateScroll);

        expandToggleButton = iconButton("∨");
        InputFeedbacks.wireClick(expandToggleButton, () -> {
            if (predicting) listener.onDismissPrediction();
            else listener.onExpandToggle();
        });
        bottomRow.addView(expandToggleButton);

        activeView.addView(bottomRow);

        addView(activeView, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /** Height of every toolbar glyph, and the touch padding around it. */
    private static final int BAR_ICON_DP = 28;
    private static final int BAR_TOUCH_PAD_DP = 11;

    /**
     * Toolbar icon. The icon drawables have a tight horizontal viewport, so
     * the view is exactly as wide as the glyph plus the touch padding. With
     * equal-weight spacers between all items (see {@link #addToIdle}) the
     * gap between neighbouring glyphs is the same everywhere, whatever the
     * glyph widths are.
     */
    private ImageView iconImage(int resId) {
        ImageView iv = new ImageView(getContext());
        iv.setColorFilter(theme.barForeground);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setClickable(true);
        iv.setFocusable(true);
        setIcon(iv, resId);
        return iv;
    }

    /** Set the drawable and size the view to the glyph's own width. */
    private void setIcon(ImageView iv, int resId) {
        iv.setImageResource(resId);
        android.graphics.drawable.Drawable d = iv.getDrawable();
        float aspect = (d != null && d.getIntrinsicHeight() > 0)
                ? (float) d.getIntrinsicWidth() / d.getIntrinsicHeight() : 1f;
        int pad = dp(BAR_TOUCH_PAD_DP);
        int vpad = Math.max(0, (dp(52) - dp(BAR_ICON_DP)) / 2);
        iv.setPadding(pad, vpad, pad, vpad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Math.round(dp(BAR_ICON_DP) * aspect) + 2 * pad,
                LinearLayout.LayoutParams.MATCH_PARENT);
        iv.setLayoutParams(lp);
    }

    /** Add an item to the idle row, preceded by a stretchy spacer. */
    private void addToIdle(View v) {
        View spacer = new View(getContext());
        idleView.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        idleView.addView(v);
    }

    private void addShortcut(final String key, int resId) {
        ImageView iv = iconImage(resId);
        InputFeedbacks.wireClick(iv, () -> listener.onShortcutClick(key));
        shortcuts.put(key, iv);
        addToIdle(iv);
    }

    /** Text-style toolbar cell (like 中/EN), sized to its text. */
    private TextView pairCell(final String key) {
        TextView tv = new TextView(getContext());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(theme.barForeground);
        tv.setIncludeFontPadding(false);
        tv.setClickable(true);
        tv.setFocusable(true);
        int pad = dp(BAR_TOUCH_PAD_DP);
        tv.setPadding(pad, 0, pad, 0);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        InputFeedbacks.wireClick(tv, () -> listener.onShortcutClick(key));
        addToIdle(tv);
        return tv;
    }

    /**
     * "left/right" with the active half in bold and the other half in
     * regular weight, so the cell shows which function is on.
     */
    private static void showPair(TextView tv, String left, String right, boolean leftActive) {
        String text = left + "/" + right;
        SpannableString ss = new SpannableString(text);
        int from = leftActive ? 0 : left.length() + 1;
        int to = leftActive ? left.length() : text.length();
        ss.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), from, to,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(ss);
    }

    private void addTraditionalShortcut() {
        traditionalShortcut = pairCell("traditional");
        showPair(traditionalShortcut, "简", "繁", !traditionalOn);
    }

    private void addLanguageShortcut() {
        languageShortcut = pairCell("language");
        showPair(languageShortcut, "中", "EN", !languageEnglish);
    }

    private void refreshShortcutTints() {
        for (java.util.Map.Entry<String, View> e : shortcuts.entrySet()) {
            boolean on = e.getKey().equals(activeShortcut);
            View view = e.getValue();
            if (view instanceof ImageView) {
                ((ImageView) view).setColorFilter(
                        on ? theme.accentColor : theme.barForeground);
            }
        }
    }

    public void setLanguageMode(KeyboardMode mode) {
        languageEnglish = (mode == KeyboardMode.ENGLISH);
        if (languageShortcut != null) {
            showPair(languageShortcut, "中", "EN", !languageEnglish);
        }
    }

    /** Highlight the shortcut whose panel is currently shown (or null). */
    public void setActiveShortcut(String key) {
        this.activeShortcut = key;
        refreshShortcutTints();
    }

    /** Reflect the simplified/traditional state on its shortcut. */
    public void setTraditionalActive(boolean on) {
        this.traditionalOn = on;
        if (traditionalShortcut != null) {
            showPair(traditionalShortcut, "简", "繁", !on);
        }
        refreshShortcutTints();
    }

    private TextView iconButton(String glyph) {
        TextView tv = new TextView(getContext());
        tv.setText(glyph);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, Typography.TITLE);
        tv.setTextColor(theme.barForeground);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(4), 0, dp(4), 0);
        tv.setClickable(true);
        tv.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(32), LinearLayout.LayoutParams.MATCH_PARENT);
        tv.setLayoutParams(lp);
        return tv;
    }

    public void render(InputKernel.Snapshot snap) {
        this.pickerMode = snap.inPunctuationPicker;
        applyPickerShift();
        InputState state = snap.state;
        List<DecodeResult> candidates = snap.candidates;
        boolean stateActive = (state != null) && !state.isEmpty();
        boolean hasCandidates = (candidates != null) && !candidates.isEmpty();
        if (state != null && state.buffer.length() >= 30
                && snap.mode == KeyboardMode.CHINESE) {
            int causeIndex = -1;
            StringBuilder sample = new StringBuilder();
            if (candidates != null) {
                for (int i = 0; i < candidates.size(); i++) {
                    DecodeResult r = candidates.get(i);
                    if ("原因".equals(r.text)) causeIndex = i;
                    if (i < 18) {
                        if (sample.length() > 0) sample.append(" | ");
                        sample.append(i).append(':').append(r.text);
                    }
                }
            }
            Log.d("SIME_DIAG_BAR", "buffer=" + state.buffer
                    + " count=" + (candidates == null ? 0 : candidates.size())
                    + " stripLimit=" + MAX_STRIP_CANDIDATES
                    + " causeIndex=" + causeIndex + " first=" + sample);
        }
        boolean hasEnglishInput = !snap.englishBuffer.isEmpty();
        boolean englishMode = snap.mode == KeyboardMode.ENGLISH;
        // Keep the right-edge button visible while composing T9 pinyin.
        // The weighted candidate scroll view is measured only up to the
        // button, with an additional end margin to prevent visual overlap.
        // Preserve the punctuation-picker and expanded-grid behavior.
        expandToggleButton.setVisibility(
                (snap.inPunctuationPicker || expanded) ? GONE : VISIBLE);
        setLanguageMode(snap.mode);
        if (stateActive || hasCandidates || hasEnglishInput) {
            showActive();
            if (englishMode) {
                // English mode: collapse into a single row. The typed
                // buffer (if any) becomes the highlighted first cell;
                // prediction strip after commit drops the buffer cell
                // but keeps the same single-row no-first-highlight style
                // for visual consistency. With preedit hidden, the
                // candidate row is the only row — vertically center it
                // instead of pinning to the top.
                preeditView.setVisibility(GONE);
                candidateContainer.setGravity(Gravity.CENTER_VERTICAL);
                populateCandidates(candidates, /*englishStyle=*/true,
                        hasEnglishInput ? snap.englishBuffer : null);
            } else {
                preeditView.setVisibility(VISIBLE);
                preeditView.setText(stateActive ? buildPreedit(snap, state) : "");
                // CN: pin candidates to top so they hug the preedit row.
                candidateContainer.setGravity(Gravity.TOP);
                populateCandidates(candidates, /*englishStyle=*/false, null);
            }
        } else {
            showIdle();
        }
        if (pickerMode) post(this::layoutPickerCells);
        post(this::applyPickerRaise);
    }

    private void showIdle() {
        idleView.setVisibility(VISIBLE);
        activeView.setVisibility(GONE);
    }

    private void showActive() {
        idleView.setVisibility(GONE);
        activeView.setVisibility(VISIBLE);
    }

    /**
     * Build the preedit display: committed hanzi (from selections) +
     * space + annotated remaining buffer. The "confirmed" prefix —
     * committed hanzi plus the buffer's already-picked pinyin region
     * (bufferLetters) — is wrapped in a {@link BackgroundColorSpan} so
     * the user can see what's been locked in vs what's still tentative.
     */
    private CharSequence buildPreedit(InputKernel.Snapshot snap, InputState state) {
        StringBuilder sb = new StringBuilder();
        String committed = state.committedText();
        if (!committed.isEmpty()) {
            sb.append(committed).append(' ');
        }
        int annotStart = sb.length();
        String units = snap.topUnits != null ? snap.topUnits : "";
        sb.append(annotateRemaining(state.remaining(),
                units != null ? units : ""));

        // Compute the highlight end index in the assembled string.
        // Two cases:
        //   - bufferLetters region is non-empty (user picked pinyin alts):
        //     extend the highlight from 0 through the committed hanzi,
        //     through the separator space, into the annotated portion
        //     until we've covered as many real chars as bufferLetters has,
        //     plus a trailing `'` if the next char is one.
        //   - bufferLetters is empty but committed is non-empty (only
        //     hanzi picks): highlight just the committed hanzi (no
        //     trailing space — avoids an isolated highlighted gap).
        int sel = state.selectedLength();
        int lettersEnd = state.lettersEnd;
        int bufferRealChars = 0;
        if (lettersEnd > sel) {
            bufferRealChars = PinyinUtil.countRealChars(
                    state.buffer.substring(sel, lettersEnd));
        }

        int hiEnd = 0;
        if (bufferRealChars > 0) {
            int realSeen = 0;
            int idx = annotStart;
            while (idx < sb.length() && realSeen < bufferRealChars) {
                if (sb.charAt(idx) != '\'') realSeen++;
                idx++;
            }
            // Pull a trailing '\'' into the highlight so the boundary
            // marker visually belongs to the locked-in region.
            if (idx < sb.length() && sb.charAt(idx) == '\'') idx++;
            hiEnd = idx;
        } else if (!committed.isEmpty()) {
            hiEnd = committed.length();
        }

        SpannableString out = new SpannableString(sb.toString());
        if (hiEnd > 0) {
            int bg = (theme.accentColor & 0x00FFFFFF) | 0x40000000;  // ~25% alpha
            out.setSpan(new BackgroundColorSpan(bg),
                    0, hiEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    /**
     * Zip the raw remaining buffer with the decoder-returned pinyin
     * segmentation so that user-typed {@code '} boundaries stay visible
     * while T9 digits are replaced by their decoded pinyin letters.
     *
     * <p>For T9 input ({@code r} is a digit) the visible char comes
     * from {@code units} so the user sees pinyin instead of digits.
     * For QWERTY ({@code r} is already a letter) the user's typed
     * letter is preserved verbatim — units is only consulted for
     * separator insertions. This avoids cases like typing "hi" in
     * Qwerty getting rendered as "he" because the decoder happened
     * to return units = "he" for an unparseable input.
     *
     * <p>If {@code units} covers fewer real chars than {@code rem}
     * (legitimate partial decode, e.g. T9 input "7457" → top candidate
     * units "pi" only covers digits 7+4), the zip walks as far as
     * units allows and then falls through to append the leftover raw
     * chars verbatim. So "7457" with units "pi" renders as "pi57",
     * making it visually obvious which prefix the engine matched and
     * which suffix was unprocessable.
     */
    private static String annotateRemaining(String rem, String units) {
        if (units.isEmpty()) return rem;
        StringBuilder sb = new StringBuilder(rem.length() + units.length());
        int ri = 0;
        int ui = 0;
        while (ri < rem.length() && ui < units.length()) {
            char r = rem.charAt(ri);
            char u = units.charAt(ui);
            if (u == '\'' && r == '\'') {
                sb.append('\'');
                ri++;
                ui++;
            } else if (u == '\'') {
                sb.append('\'');
                ui++;
            } else if (r == '\'') {
                sb.append('\'');
                ri++;
            } else {
                // T9: r is a digit, u is the decoded letter — show u.
                // Qwerty: r is already a letter — preserve r as typed.
                sb.append(Character.isDigit(r) ? u : r);
                ri++;
                ui++;
            }
        }
        while (ri < rem.length()) sb.append(rem.charAt(ri++));
        while (ui < units.length()) sb.append(units.charAt(ui++));
        return sb.toString();
    }

    /**
     * Recycled view slots — each slot is a (candidate TextView, divider
     * View) pair appended in interleaved order so {@code candidateContainer}'s
     * child sequence stays {@code [cand0, div0, cand1, div1, …]}.
     * Shrinking just hides extra slots; growing appends new pairs at
     * the end. Avoids allocating ~20 views on every keystroke.
     */
    private final java.util.List<TextView> candidatePool = new java.util.ArrayList<>();
    private final java.util.List<View> dividerPool = new java.util.ArrayList<>();

    private void populateCandidates(List<DecodeResult> candidates) {
        populateCandidates(candidates, false, null);
    }

    /**
     * @param englishStyle when true, render with English-style fonts and
     *     suppress the Chinese first-cell bold-accent highlight. Used for
     *     both active English input (with {@code englishBuffer} set) and
     *     post-commit English prediction (buffer null) so the strip looks
     *     consistent.
     * @param englishBuffer when non-null and {@code englishStyle} is true,
     *     render a "current word" cell at index 0 (filled accent
     *     background) showing the buffer text; subsequent cells render
     *     the {@code candidates} list. Tap on cell 0 fires
     *     {@link Listener#onEnglishLiteralCommit()}; other cells fire
     *     {@link Listener#onCandidatePick} with the candidate index
     *     (offset adjusted).
     */
    private void populateCandidates(List<DecodeResult> candidates,
                                    boolean englishStyle,
                                    String englishBuffer) {
        boolean hasBufferCell = englishStyle && englishBuffer != null
                && !englishBuffer.isEmpty();
        int candCount = candidates == null
                ? 0 : Math.min(candidates.size(), MAX_STRIP_CANDIDATES);
        int totalCells = candCount + (hasBufferCell ? 1 : 0);
        this.activeCandidateCount = totalCells;
        // Symbol list (1 key): spread the symbols over the whole row so the
        // gaps to the left and right borders are equal.
        candidateScroll.setFillViewport(pickerMode);
        android.view.ViewGroup.LayoutParams cLp = candidateContainer.getLayoutParams();
        if (cLp != null) {
            cLp.width = pickerMode ? LinearLayout.LayoutParams.MATCH_PARENT
                    : LinearLayout.LayoutParams.WRAP_CONTENT;
            candidateContainer.setLayoutParams(cLp);
        }
        for (int i = 0; i < candidateContainer.getChildCount(); i++) {
            View child = candidateContainer.getChildAt(i);
            android.view.ViewGroup.LayoutParams cl = child.getLayoutParams();
            if (cl instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams ll = (LinearLayout.LayoutParams) cl;
                float w = (pickerMode && child instanceof TextView) ? 1f : 0f;
                if (ll.weight != w) { ll.weight = w; child.setLayoutParams(ll); }
            }
        }
        // A new candidate list (e.g. the next syllable after picking a word)
        // always starts from its first candidate, not the old scroll spot.
        resetCandidateScroll();

        // Grow the pool to cover the current candidate count. New
        // entries are appended in pair order (candidate then divider)
        // so the container's child order remains interleaved.
        while (candidatePool.size() < totalCells) {
            TextView tv = new TextView(getContext());
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, Typography.TITLE);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(12), dp(1), dp(12), dp(1));
            tv.setClickable(true);
            tv.setFocusable(true);
            candidateContainer.addView(tv);
            candidatePool.add(tv);

            View divider = new View(getContext());
            divider.setBackgroundColor(Color.argb(40,
                    Color.red(theme.dividerColor),
                    Color.green(theme.dividerColor),
                    Color.blue(theme.dividerColor)));
            LinearLayout.LayoutParams dLp =
                    new LinearLayout.LayoutParams(dp(1), dp(22));
            dLp.gravity = Gravity.CENTER_VERTICAL;
            candidateContainer.addView(divider, dLp);
            dividerPool.add(divider);
        }

        // Bind visible slots; hide extras from previous longer renders.
        for (int i = 0; i < candidatePool.size(); i++) {
            TextView tv = candidatePool.get(i);
            View div = dividerPool.get(i);
            if (i < totalCells) {
                if (hasBufferCell && i == 0) {
                    // Synthetic "current word" cell: inline text-only
                    // BackgroundColorSpan (~25% alpha accent), matching
                    // the pinyin-segment highlight style in buildPreedit.
                    // Tapping commits the literal buffer.
                    int tint = (theme.accentColor & 0x00FFFFFF) | 0x40000000;
                    SpannableString span = new SpannableString(englishBuffer);
                    span.setSpan(new BackgroundColorSpan(tint),
                            0, span.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    tv.setText(span);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, Typography.TITLE);
                    tv.setTextColor(theme.candidateText);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setPadding(dp(12), dp(1), dp(12), dp(1));
                    tv.setBackground(null);
                    InputFeedbacks.wireClick(tv,
                            () -> listener.onEnglishLiteralCommit());
                    tv.setOnTouchListener(null);
                } else {
                    int candIdx = hasBufferCell ? (i - 1) : i;
                    final int idx = candIdx;
                    DecodeResult c = candidates.get(candIdx);
                    tv.setText(c.text);
                    tv.setBackground(null);
                    // Reset padding (buffer cell tightened it).
                    tv.setPadding(dp(12), dp(1), dp(12), dp(1));
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, Typography.TITLE);
                    if (englishStyle) {
                        // English candidates / predictions: no first-cell
                        // bold-accent highlight (no "selected suggestion"
                        // pattern in English IME convention).
                        tv.setTextColor(theme.candidateText);
                        tv.setTypeface(null, Typeface.NORMAL);
                    } else {
                        if (candIdx == 0) {
                            tv.setTextColor(theme.candidateHighlight);
                            tv.setTypeface(null, Typeface.BOLD);
                        } else {
                            tv.setTextColor(theme.candidateText);
                            tv.setTypeface(null, Typeface.NORMAL);
                        }
                    }
                    InputFeedbacks.wireClick(tv, () -> listener.onCandidatePick(idx));
                    if (englishStyle) {
                        tv.setOnTouchListener(null);
                    } else {
                        CandidateMenu.attachLongPress(tv,
                                () -> listener.onCandidateLongPress(idx, tv));
                    }
                }
                tv.setVisibility(VISIBLE);
                // Last visible slot hides its trailing divider so the
                // bar doesn't end with a stray vertical line.
                div.setVisibility(i < totalCells - 1 ? VISIBLE : GONE);
            } else {
                tv.setVisibility(GONE);
                div.setVisibility(GONE);
            }
        }
    }

    public void setListener(Listener l) {
        this.listener = (l != null) ? l : new Listener() {};
    }

    /** Update the right-edge button glyph + behavior based on whether
     *  the bar is showing a prediction strip vs in-flight candidates. */
    public void setPredicting(boolean p) {
        if (this.predicting == p) return;
        this.predicting = p;
        if (expandToggleButton != null) {
            expandToggleButton.setText(p ? "×" : "∨");
        }
    }

    /**
     * Toggle the bar between normal mode (left icon = ⚙ → opens
     * settings) and settings mode (left icon = ← → pops one settings
     * layer / exits). Forces idle layout because the active candidate
     * view is meaningless inside settings.
     */
    public void setSettingsMode(boolean inSettings) {
        if (this.settingsMode == inSettings) return;
        this.settingsMode = inSettings;
        if (idleLeftButton != null) {
            setIcon(idleLeftButton, inSettings
                    ? com.shiyu.sime.R.drawable.ic_toolbar_back
                    : com.shiyu.sime.R.drawable.ic_toolbar_gear);
        }
    }

    /**
     * Centre the expand (more) button over a keyboard column: its centre is
     * {@code fraction} of the bar width away from the right edge. The nine-key
     * keyboard passes the centre of its delete key; 0 keeps it at the edge.
     */
    public void setExpandAlignFraction(float fraction) {
        this.expandAlignFraction = fraction;
        applyExpandAlignment();
    }

    private void applyExpandAlignment() {
        if (expandToggleButton == null || getWidth() <= 0) return;
        android.view.ViewGroup.LayoutParams raw = expandToggleButton.getLayoutParams();
        if (!(raw instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
        int half = dp(16); // half of the button's 32dp width
        // The button sits inside activeView, which has its own right padding;
        // its centre must be (fraction x bar width) from the bar's right edge.
        int inset = activeView != null ? activeView.getPaddingRight() : 0;
        int margin = expandAlignFraction > 0f
                ? Math.max(0, Math.round(getWidth() * expandAlignFraction) - half - inset) : 0;
        if (lp.rightMargin != margin) {
            lp.rightMargin = margin;
            expandToggleButton.setLayoutParams(lp);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyExpandAlignment();
        post(this::layoutPickerCells);
        post(this::applyPickerRaise);
    }

    /** Lift the symbol list into the empty pinyin line above it (see PICKER_RAISE_FRACTION). */
    private void applyPickerRaise() {
        if (candidateScroll == null || preeditView == null) return;
        float rise = pickerMode ? preeditView.getHeight() * PICKER_RAISE_FRACTION : 0f;
        if (candidateScroll.getTranslationY() != -rise) {
            candidateScroll.setTranslationY(-rise);
        }
    }

    private void applyPickerShift() {
        if (activeView == null) return;
        int shift = pickerMode ? Math.min(dp(8), dp(PICKER_SHIFT_LEFT_DP)) : 0;
        int left = dp(8) - shift;
        int right = dp(8) + shift;
        if (activeView.getPaddingLeft() != left || activeView.getPaddingRight() != right) {
            activeView.setPadding(left, 0, right, 0);
        }
    }

    /**
     * Symbol list (1 key): the symbols have 12dp of padding on each side by
     * default, which makes ten of them wider than the screen, so the list
     * starts far from the left edge but its last symbol ends up against the
     * right edge. Shrink the side padding just enough for all symbols to fit,
     * then the weights spread what is left evenly: the first and the last
     * symbol end up the same distance from their borders.
     */
    private void layoutPickerCells() {
        if (!pickerMode || candidateContainer == null) return;
        int avail = getWidth() - dp(16); // activeView has 8dp on each side
        if (avail <= 0) return;
        List<TextView> cells = new java.util.ArrayList<>();
        int dividers = 0;
        float textSum = 0f;
        for (int i = 0; i < candidateContainer.getChildCount(); i++) {
            View child = candidateContainer.getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                cells.add(tv);
                textSum += tv.getPaint().measureText(tv.getText().toString());
            } else {
                dividers += Math.max(1, child.getLayoutParams() != null
                        && child.getLayoutParams().width > 0
                        ? child.getLayoutParams().width : dp(1));
            }
        }
        if (cells.isEmpty()) return;
        float pad = (avail - textSum - dividers) / (2f * cells.size());
        int p = Math.round(Math.max(dp(2), Math.min(dp(12), pad)));
        for (TextView tv : cells) {
            if (tv.getPaddingLeft() != p || tv.getPaddingRight() != p) {
                tv.setPadding(p, tv.getPaddingTop(), p, tv.getPaddingBottom());
            }
        }
    }

    /** Scroll the candidate strip back to its first candidate. */
    private void resetCandidateScroll() {
        if (candidateScroll == null) return;
        candidateScroll.scrollTo(0, 0);
        // Again after layout: the container was just re-bound and the
        // scroll view may clamp or keep the previous offset until then.
        candidateScroll.post(() -> candidateScroll.scrollTo(0, 0));
    }

    /**
     * Reflect the expanded/collapsed state. The bar keeps showing
     * preedit + hanzi candidates in both states — the user can quick-
     * pick from the bar while also browsing the full grid below.
     * The toggle button (∨) is hidden when expanded because the
     * ExpandedCandidatesView's right column has its own 返回 button.
     */
    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
        // Lock horizontal scroll while expanded — the grid below shows
        // the overflow, so allowing the bar to scroll would just give
        // the user two ways to see the same candidates and confuse the
        // bar/grid split.
        if (candidateScroll != null) {
            candidateScroll.scrollLocked = expanded;
            if (expanded) {
                candidateScroll.scrollTo(0, 0);
                // After layout settles, hide any candidate that's not
                // fully visible (the half-truncated one at the right
                // edge would otherwise also appear as the first cell of
                // the grid → overlap).
                candidateScroll.post(this::hideTruncatedCandidates);
            } else {
                // Restore the cells we hid when expanding. A new
                // render() would do this automatically but the collapse
                // alone doesn't trigger one.
                restoreCandidateVisibility();
            }
        }
        if (expandToggleButton == null) return;
        if (predicting) {
            // Prediction mode keeps the dismiss "×" regardless of any
            // stale expand state.
            expandToggleButton.setText("×");
            expandToggleButton.setVisibility(VISIBLE);
            return;
        }
        expandToggleButton.setText(expanded ? "∧" : "∨");
        expandToggleButton.setVisibility(expanded ? GONE : VISIBLE);
    }

    /**
     * Count how many candidate cells fit in the visible (non-scrolled)
     * portion of the candidate scroll. Used by the expanded view to
     * skip these and only show overflow candidates in the grid.
     */
    /** Re-show all cells that should be active per last render. */
    private void restoreCandidateVisibility() {
        int n = activeCandidateCount;
        for (int i = 0; i < candidatePool.size(); i++) {
            TextView tv = candidatePool.get(i);
            tv.setVisibility(i < n ? VISIBLE : GONE);
            if (i < dividerPool.size()) {
                View div = dividerPool.get(i);
                // Trailing divider on the last visible cell stays GONE.
                div.setVisibility(i < n - 1 ? VISIBLE : GONE);
            }
        }
    }

    /** Hide any candidate cell whose right edge exceeds the visible
     *  scroll width — i.e. the cell is half-truncated or fully
     *  off-screen. Only called while the panel is expanded. */
    private void hideTruncatedCandidates() {
        if (candidateScroll == null || candidateContainer == null) return;
        int scrollWidth = candidateScroll.getWidth();
        if (scrollWidth <= 0) return;
        for (int i = 0; i < candidatePool.size(); i++) {
            TextView tv = candidatePool.get(i);
            if (tv.getVisibility() != VISIBLE) continue;
            int right = tv.getLeft() + tv.getWidth();
            if (right > scrollWidth) {
                tv.setVisibility(GONE);
                if (i < dividerPool.size()) {
                    dividerPool.get(i).setVisibility(GONE);
                }
            }
        }
        // Also hide the trailing divider on the now-last visible cell.
        for (int i = candidatePool.size() - 1; i >= 0; i--) {
            if (candidatePool.get(i).getVisibility() == VISIBLE) {
                if (i < dividerPool.size()) {
                    dividerPool.get(i).setVisibility(GONE);
                }
                break;
            }
        }
    }

    public int getVisibleCandidateCount() {
        if (candidateScroll == null || candidateContainer == null) return 0;
        int scrollWidth = candidateScroll.getWidth();
        if (scrollWidth <= 0) return 0;
        int count = 0;
        int consumed = 0;
        for (int i = 0; i < candidatePool.size(); i++) {
            TextView tv = candidatePool.get(i);
            if (tv.getVisibility() != VISIBLE) break;
            consumed += tv.getWidth();
            if (consumed > scrollWidth) break;
            count++;
            if (i < dividerPool.size()) {
                View div = dividerPool.get(i);
                if (div.getVisibility() == VISIBLE) consumed += div.getWidth();
            }
        }
        return count;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
