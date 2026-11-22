package com.shiyu.sime.ime.keyboard;

import android.content.Context;
import android.widget.LinearLayout;

import com.shiyu.sime.ime.keyboard.framework.KeyView;
import com.shiyu.sime.ime.keyboard.framework.KeyboardContainer;
import com.shiyu.sime.ime.keyboard.layouts.TextEditLayout;

/**
 * Gboard-style text editing panel. The 选择 key is a local toggle: while
 * it is on, cursor keys are sent as shift+key so they extend the
 * selection. Everything else is forwarded as {@link KeyType#EDIT} keys.
 */
public class TextEditKeyboardView extends KeyboardView {
    private final KeyboardContainer container;
    private boolean selecting = false;

    public TextEditKeyboardView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        container = new KeyboardContainer(context, theme);
        container.setOnKeyEmitListener(this::onContainerKey);
        container.setLayout(TextEditLayout.build());
        addView(container, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        refreshSelectKey();
    }

    private void onContainerKey(SimeKey key) {
        if (key.type != KeyType.EDIT) {
            emit(key); // 空格
            return;
        }
        String action = key.text;
        switch (action) {
            case "toggle_select":
                selecting = !selecting;
                refreshSelectKey();
                emit(SimeKey.edit(selecting ? "select_on" : "select_off"));
                return;
            case "cancel_select":
                selecting = false;
                refreshSelectKey();
                emit(SimeKey.edit("cancel_selection"));
                emit(SimeKey.edit("select_off"));
                return;
            case "left": case "right": case "up": case "down":
            case "home": case "end":
                emit(SimeKey.edit(selecting ? action + "+s" : action));
                return;
            case "cut": case "copy": case "paste": case "backspace":
                emit(key);
                if (selecting) {
                    selecting = false;
                    refreshSelectKey();
                    emit(SimeKey.edit("select_off"));
                }
                return;
            default:
                emit(key);
        }
    }

    private void refreshSelectKey() {
        KeyView kv = container.findKeyById(TextEditLayout.ID_SELECT);
        if (kv != null) kv.setHighlighted(selecting);
    }
}
