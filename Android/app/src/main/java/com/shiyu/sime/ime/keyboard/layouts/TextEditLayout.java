package com.shiyu.sime.ime.keyboard.layouts;

import com.shiyu.sime.R;
import com.shiyu.sime.ime.keyboard.SimeKey;
import com.shiyu.sime.ime.keyboard.framework.KeyDef;
import com.shiyu.sime.ime.keyboard.framework.KeyRow;
import com.shiyu.sime.ime.keyboard.framework.KeyboardLayout;
import com.shiyu.sime.ime.theme.Typography;

/**
 * Text-editing panel (Gboard style): cursor keys in a cross, a 选择
 * toggle that makes the cursor keys extend the selection, plus
 * select-all / cut / copy / paste / undo and delete.
 *
 * <pre>
 *   全选  复制   ↑   粘贴  ⌫
 *   选择   ←   撤销   →   剪切
 *   取消  行首   ↓   行尾  空格
 * </pre>
 */
public final class TextEditLayout {
    public static final String ID_SELECT = "edit_select";

    private TextEditLayout() {}

    public static KeyboardLayout build() {
        return KeyboardLayout.builder()
                .horizontalPadding(6)
                .verticalPadding(6)
                .keyMargin(4)
                .row(KeyRow.builder(1f)
                        .key(fn("全选", "selectall"))
                        .key(fn("复制", "copy"))
                        .key(arrow(R.drawable.ic_edit_up, "up"))
                        .key(fn("粘贴", "paste"))
                        .key(KeyDef.function("⌫", SimeKey.edit("backspace"))
                                .repeatable(true)))
                .row(KeyRow.builder(1f)
                        .key(KeyDef.function("选择", SimeKey.edit("toggle_select"))
                                .id(ID_SELECT).labelSize(Typography.SMALL))
                        .key(arrow(R.drawable.ic_edit_left, "left").repeatable(true))
                        .key(fn("撤销", "undo"))
                        .key(arrow(R.drawable.ic_edit_right, "right").repeatable(true))
                        .key(fn("剪切", "cut")))
                .row(KeyRow.builder(1f)
                        .key(KeyDef.function("取消", SimeKey.edit("cancel_select"))
                                .labelSize(Typography.SMALL))
                        .key(fn("行首", "home"))
                        .key(arrow(R.drawable.ic_edit_down, "down").repeatable(true))
                        .key(fn("行尾", "end"))
                        .key(KeyDef.function("空格", SimeKey.space())
                                .labelSize(Typography.SMALL)))
                .build();
    }

    private static KeyDef.Builder fn(String label, String action) {
        return KeyDef.function(label, SimeKey.edit(action)).labelSize(Typography.SMALL);
    }

    private static KeyDef.Builder arrow(int iconRes, String action) {
        return KeyDef.function("", SimeKey.edit(action)).icon(iconRes);
    }
}
