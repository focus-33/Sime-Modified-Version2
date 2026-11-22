package com.shiyu.sime.ime.candidates;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.shiyu.sime.ime.InputKernel.CandidateAction;
import com.shiyu.sime.ime.theme.SimeTheme;

/**
 * Long-press menu for a candidate: 升序 / 降序 / 屏蔽 / 更多, where 更多
 * opens a second level with 移动到首位 / 移动到末尾.
 */
public final class CandidateMenu {
    /** How long a candidate must be held before the menu appears. */
    public static final long LONG_PRESS_MS = 800;

    public interface Callback {
        void onAction(CandidateAction action);
    }

    private final Context ctx;
    private final SimeTheme theme;
    private final Callback callback;
    private final LinearLayout content;
    private PopupWindow popup;
    private static CandidateMenu activeMenu;

    private CandidateMenu(Context ctx, SimeTheme theme, Callback cb) {
        this.ctx = ctx;
        this.theme = theme;
        this.callback = cb;
        content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(theme.keyBackground);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), theme.dividerColor);
        content.setBackground(bg);
        content.setElevation(dp(8));
        content.setPadding(dp(4), dp(2), dp(4), dp(2));
        showTopLevel();
    }

    /** Show the menu just below {@code anchor}. */
    public static void show(View anchor, SimeTheme theme, Callback cb) {
        dismissActive();
        CandidateMenu m = new CandidateMenu(anchor.getContext(), theme, cb);
        m.open(anchor);
    }

    /** Dismiss the currently visible candidate menu, if any. */
    public static boolean dismissActive() {
        CandidateMenu menu = activeMenu;
        if (menu == null || menu.popup == null || !menu.popup.isShowing()) {
            activeMenu = null;
            return false;
        }
        menu.dismiss();
        return true;
    }

    private void open(View anchor) {
        // A focusable PopupWindow takes window focus away from the IME.
        // On Android 9 this can make the editor/input session restart and
        // clear the composing state and candidate strip. Keep it non-focusable;
        // the IME service handles BACK and dismisses this popup explicitly.
        popup = new PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, false);
        popup.setOutsideTouchable(true);
        popup.setClippingEnabled(true);
        activeMenu = this;
        popup.setOnDismissListener(() -> {
            if (activeMenu == this) activeMenu = null;
        });
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int menuW = Math.max(content.getMeasuredWidth(), dp(3 * 70));
        int xOff = 0;
        if (loc[0] + menuW > screenW) xOff = screenW - menuW - loc[0] - dp(4);
        try {
            popup.showAsDropDown(anchor, xOff, 0);
        } catch (RuntimeException e) {
            popup = null; // window token gone; ignore
        }
    }

    private void dismiss() {
        if (popup != null) {
            try { popup.dismiss(); } catch (RuntimeException ignored) { }
            popup = null;
        }
        if (activeMenu == this) {
            activeMenu = null;
        }
    }

    private void showTopLevel() {
        content.removeAllViews();
        addItem("升序", () -> fire(CandidateAction.MOVE_UP));
        addItem("降序", () -> fire(CandidateAction.MOVE_DOWN));
        addItem("屏蔽", () -> fire(CandidateAction.BLOCK));
        addItem("更多", this::showMore);
    }

    private void showMore() {
        content.removeAllViews();
        addItem("移到首位", () -> fire(CandidateAction.MOVE_FIRST));
        addItem("移到末尾", () -> fire(CandidateAction.MOVE_LAST));
        addItem("返回", this::showTopLevel);
    }

    private void fire(CandidateAction a) {
        dismiss();
        callback.onAction(a);
    }

    private void addItem(String label, Runnable action) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tv.setTextColor(theme.candidateText);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        tv.setClickable(true);
        tv.setOnClickListener(v -> action.run());
        content.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    /**
     * Attach an 800 ms long-press to {@code v}. A completed long press
     * swallows the following tap so the candidate is not also picked.
     */
    public static void attachLongPress(View v, Runnable onLongPress) {
        final int slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
        v.setOnTouchListener(new View.OnTouchListener() {
            boolean fired;
            float downX, downY;
            final Runnable task = () -> {
                fired = true;
                v.setPressed(false);
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                onLongPress.run();
            };

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        fired = false;
                        downX = e.getRawX();
                        downY = e.getRawY();
                        view.postDelayed(task, LONG_PRESS_MS);
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        if (!fired && (Math.abs(e.getRawX() - downX) > slop
                                || Math.abs(e.getRawY() - downY) > slop)) {
                            view.removeCallbacks(task);
                        }
                        return fired;
                    case MotionEvent.ACTION_UP:
                        view.removeCallbacks(task);
                        if (fired) { fired = false; return true; }
                        return false;
                    case MotionEvent.ACTION_CANCEL:
                        view.removeCallbacks(task);
                        fired = false;
                        return false;
                    default:
                        return fired;
                }
            }
        });
    }
}
