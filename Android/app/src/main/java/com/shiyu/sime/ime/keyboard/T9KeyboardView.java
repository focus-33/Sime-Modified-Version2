package com.shiyu.sime.ime.keyboard;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.shiyu.sime.ime.InputKernel;
import com.shiyu.sime.ime.keyboard.framework.KeyView;
import com.shiyu.sime.ime.keyboard.framework.KeyboardContainer;
import com.shiyu.sime.ime.keyboard.layouts.T9Layout;

import java.util.List;

/**
 * T9 (九宫格) keyboard. Three vertical blocks side by side:
 *
 * <ul>
 *   <li><b>Left block</b> — dynamic strip on top (pinyin alts /
 *       fallback letters / idle punctuation) plus a 符号 cell at the
 *       bottom.
 *   <li><b>Center block</b> — 3×3 digit grid on top, plus a bottom row
 *       (123 | 空格 | 中\n英) sharing the digit grid's width.
 *   <li><b>Right block</b> — ⌫ / 重输 / 换行 with row weights 1, 1, 2
 *       so 换行 is a tall key that extends from row 3 down through the
 *       bottom-row area.
 * </ul>
 *
 * <p>Two keys are dual-state ({@code @# ↔ 分词}, {@code 换行 ↔ 确定});
 * the controller overrides their per-key click listener and label
 * depending on whether the kernel currently has input.
 */
public class T9KeyboardView extends KeyboardView {

    private static final String[] T9_LETTERS = {
            "", "", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"
    };

    /**
     * The pinyin letters mapped to a single T9 digit key. Empty string
     * for non-T9 chars (digits 0/1, separators, anything else). Single
     * source of truth for the T9 keymap.
     */
    public static String lettersForDigit(char digit) {
        int idx = digit - '0';
        if (idx < 0 || idx >= T9_LETTERS.length) return "";
        return T9_LETTERS[idx];
    }

    private static final String[] IDLE_PUNC = {
            "？", "！", "：", "、", "…", "～"
    };

    /** Max items in the left strip (pinyin alts + fallback letters). */
    private static final int MAX_LEFT_ITEMS = 12;
    private static final int LEFT_ITEM_HEIGHT_DP = 42;

    private LinearLayout leftStrip;
    private ScrollView leftScroll;
    private KeyboardContainer mainGrid;
    private KeyboardContainer rightCol;

    private boolean active = false;
    private List<InputKernel.PinyinAlt> pinyinAlts = java.util.Collections.emptyList();
    private String firstDigitLetters = "";
    private boolean shiftLocked;
    private KeyView activeLetterKey;
    private String activeLetters = "";
    private int selectedLetterIndex = 1;

    public interface OnLeftStripListener {
        void onPinyinAltPick(int index);
        void onFallbackLetter(char letter);
    }

    private OnLeftStripListener leftListener;

    public T9KeyboardView(Context context) {
        super(context);
        // Override the base class's VERTICAL orientation — this view's
        // top-level layout is three columns side by side.
        setOrientation(HORIZONTAL);
        build();
    }

    public void setOnLeftStripListener(OnLeftStripListener l) {
        this.leftListener = l;
    }

    public void setActive(boolean active,
                           List<InputKernel.PinyinAlt> alts,
                           String firstDigitLetters) {
        this.active = active;
        this.pinyinAlts = alts != null ? alts
                : java.util.Collections.<InputKernel.PinyinAlt>emptyList();
        this.firstDigitLetters = firstDigitLetters != null ? firstDigitLetters : "";
        refreshDualStateKeys();
        populateLeftStrip();
    }

    private void build() {
        // ===== Left block: dynamic strip on top (3f) + 符号 (1f) =====
        // Width weights symmetric around the center digit grid:
        //   left 0.9 : center 3.2 : right 0.9  (was 1:3:1).
        // Wider center, equally narrowed sides.
        LinearLayout leftBlock = new LinearLayout(getContext());
        leftBlock.setOrientation(VERTICAL);
        addView(leftBlock, new LayoutParams(0, LayoutParams.MATCH_PARENT, 0.9f));

        leftScroll = new ScrollView(getContext());
        leftScroll.setVerticalScrollBarEnabled(false);
        leftScroll.setFillViewport(true);
        leftBlock.addView(leftScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 3f));

        leftStrip = new LinearLayout(getContext());
        leftStrip.setOrientation(VERTICAL);
        leftScroll.addView(leftStrip, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        KeyboardContainer fuhao = new KeyboardContainer(getContext(), theme);
        fuhao.setOnKeyEmitListener(this::emit);
        fuhao.setLayout(T9Layout.buildFuhaoCell());
        leftBlock.addView(fuhao, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ===== Center block: 3×3 digit grid (3f) + center bottom row (1f) =====
        LinearLayout centerBlock = new LinearLayout(getContext());
        centerBlock.setOrientation(VERTICAL);
        addView(centerBlock, new LayoutParams(0, LayoutParams.MATCH_PARENT, 3.2f));

        mainGrid = new KeyboardContainer(getContext(), theme);
        mainGrid.setOnKeyEmitListener(this::emit);
        mainGrid.setLayout(T9Layout.buildMainGrid());
        centerBlock.addView(mainGrid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 3f));

        KeyboardContainer centerBottom = new KeyboardContainer(getContext(), theme);
        centerBottom.setOnKeyEmitListener(this::emit);
        centerBottom.setLayout(T9Layout.buildCenterBottomRow());
        centerBlock.addView(centerBottom, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ===== Right block: ⌫ / 重输 / 换行(2 rows tall) =====
        rightCol = new KeyboardContainer(getContext(), theme);
        rightCol.setOnKeyEmitListener(this::emit);
        rightCol.setLayout(T9Layout.buildRightColumn());
        addView(rightCol, new LayoutParams(0, LayoutParams.MATCH_PARENT, 0.9f));

        installDualStateHandlers();
        installDirectLetterGestures();
        refreshDualStateKeys();
        populateLeftStrip();
    }

    /**
     * Override the per-key listener for the {@code @# / 分词} key so it
     * dispatches a different SimeKey depending on {@link #active}. The
     * 换行 key uses the static clickAction (always {@link SimeKey#enter()})
     * — only its label flips between 换行 / 确定.
     */
    private void installDualStateHandlers() {
        KeyView topLeft = mainGrid.findKeyById(T9Layout.ID_TOP_LEFT);
        if (topLeft != null) {
            topLeft.setListener((def, action) -> {
                if (action == KeyView.KeyAction.LONG_PRESS) {
                    shiftLocked = !shiftLocked;
                    refreshDualStateKeys();
                    return;
                }
                if (action != KeyView.KeyAction.CLICK) return;
                if (active) {
                    emit(SimeKey.separator());
                } else {
                    // Short press keeps the original 1-key punctuation picker.
                    emit(SimeKey.numPunctuation());
                }
            });
        }
    }

    private void installDirectLetterGestures() {
        for (char digit = '2'; digit <= '9'; digit++) {
            final char keyDigit = digit;
            KeyView key = mainGrid.findKeyById("t9.digit." + keyDigit);
            if (key == null) continue;
            key.setListener((def, action) -> {
                if (action == KeyView.KeyAction.LONG_PRESS) {
                    activeLetterKey = key;
                    activeLetters = lettersForDigit(keyDigit);
                    selectedLetterIndex = Math.min(1, Math.max(0, activeLetters.length() - 1));
                    updateLetterPreview();
                } else if (action == KeyView.KeyAction.CLICK) {
                    emit(def.clickAction);
                }
            });
            key.setTouchSequenceListener(new KeyView.TouchSequenceListener() {
                @Override public void onMove(float screenX, float screenY) {
                    if (activeLetterKey != key || activeLetters.isEmpty()) return;
                    int[] origin = new int[2];
                    getLocationOnScreen(origin);
                    float relative = screenX - origin[0];
                    int index = (int) (relative / Math.max(1f, getWidth()) * activeLetters.length());
                    selectedLetterIndex = Math.max(0, Math.min(activeLetters.length() - 1, index));
                    updateLetterPreview();
                }
                @Override public void onEnd(float screenX, float screenY, boolean cancelled) {
                    if (activeLetterKey != key) return;
                    if (!cancelled && !activeLetters.isEmpty()) {
                        char letter = activeLetters.charAt(selectedLetterIndex);
                        emit(SimeKey.directLetter(shiftLocked ? Character.toUpperCase(letter) : letter));
                    }
                    key.setLabel(lettersForDigit(keyDigit).toUpperCase(java.util.Locale.ROOT));
                    activeLetterKey = null;
                    activeLetters = "";
                }
            });
        }
    }

    private void updateLetterPreview() {
        if (activeLetterKey == null || activeLetters.isEmpty()) return;
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < activeLetters.length(); i++) {
            if (i > 0) label.append(' ');
            char c = activeLetters.charAt(i);
            if (shiftLocked) c = Character.toUpperCase(c);
            if (i == selectedLetterIndex) label.append('[').append(c).append(']');
            else label.append(c);
        }
        activeLetterKey.setLabel(label.toString());
    }

    private void refreshDualStateKeys() {
        if (mainGrid == null) return;
        KeyView topLeft = mainGrid.findKeyById(T9Layout.ID_TOP_LEFT);
        if (topLeft != null) {
            topLeft.setLabel(shiftLocked ? "大写" : (active ? "分词" : "@#"));
            topLeft.setHighlighted(shiftLocked);
        }
        KeyView enter = rightCol.findKeyById(T9Layout.ID_ENTER);
        if (enter != null) enter.setLabel(active ? "确定" : "换行");
    }

    /** Scroll the left strip (pinyin letters / symbols) back to its top. */
    private void resetLeftScroll() {
        if (leftScroll == null) return;
        leftScroll.scrollTo(0, 0);
        leftScroll.post(() -> leftScroll.scrollTo(0, 0));
    }

    private void populateLeftStrip() {
        populateLeftStripItems();
        // Whatever the strip now shows starts from its first item, instead
        // of staying at the spot the user had scrolled down to.
        resetLeftScroll();
    }

    private void populateLeftStripItems() {
        leftStrip.removeAllViews();
        if (!active) {
            for (int i = 0; i < IDLE_PUNC.length; i++) {
                final String p = IDLE_PUNC[i];
                leftStrip.addView(makeLeftItem(p,
                        () -> emit(SimeKey.punctuation(p))));
            }
            return;
        }
        // Active: single-syllable pinyin alts first, then fallback letters
        // for the first digit. Track shown labels so the fallback loop
        // doesn't duplicate single-character pinyin alternatives.
        java.util.HashSet<String> shown = new java.util.HashSet<>();
        int added = 0;
        for (int i = 0; i < pinyinAlts.size() && added < MAX_LEFT_ITEMS; i++) {
            final int idx = i;
            String label = pinyinAlts.get(i).letters;
            leftStrip.addView(makeLeftItem(label, () -> {
                if (leftListener != null) leftListener.onPinyinAltPick(idx);
            }));
            shown.add(label);
            added++;
        }
        for (int i = 0; i < firstDigitLetters.length() && added < MAX_LEFT_ITEMS; i++) {
            final char ch = firstDigitLetters.charAt(i);
            String label = String.valueOf(ch);
            if (shown.contains(label)) continue;
            leftStrip.addView(makeLeftItem(label, () -> {
                if (leftListener != null) leftListener.onFallbackLetter(ch);
            }));
            shown.add(label);
            added++;
        }
        if (added == 0) {
            // No alts and no letters — fall back to punctuation so the
            // user isn't left with an empty strip.
            for (int i = 0; i < IDLE_PUNC.length; i++) {
                final String p = IDLE_PUNC[i];
                leftStrip.addView(makeLeftItem(p,
                        () -> emit(SimeKey.punctuation(p))));
            }
        }
    }

    private TextView makeLeftItem(String label, Runnable onClick) {
        // After any pick, return to the top even if the state (and so the
        // strip contents) does not change, e.g. the idle symbol list.
        return StripHelper.makeStripCell(
                getContext(), theme, label, () -> {
                    onClick.run();
                    resetLeftScroll();
                }, LEFT_ITEM_HEIGHT_DP);
    }
}
