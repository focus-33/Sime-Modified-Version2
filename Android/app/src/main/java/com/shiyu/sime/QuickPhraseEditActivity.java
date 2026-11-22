package com.shiyu.sime;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.shiyu.sime.ime.data.QuickPhraseStore;
import com.shiyu.sime.ime.theme.Typography;

/** Editor for one personal-dictionary entry (output phrase + pinyin). */
public class QuickPhraseEditActivity extends Activity {
    public static final String EXTRA_EDIT_INDEX = "edit_index";
    public static final String EXTRA_INITIAL_TEXT = "initial_text";
    public static final String EXTRA_INITIAL_PINYIN = "initial_pinyin";

    private EditText textField;
    private EditText pinyinField;
    private int editIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        editIndex = getIntent().getIntExtra(EXTRA_EDIT_INDEX, -1);
        boolean editing = editIndex >= 0;
        setTitle(editing ? "编辑个人字典" : "添加个人字典词条");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));

        TextView phraseLabel = new TextView(this);
        phraseLabel.setText("自造词 / 短语：");
        phraseLabel.setTextSize(Typography.CALLOUT);
        root.addView(phraseLabel);

        textField = new EditText(this);
        textField.setHint("例如：人工智能");
        textField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        String initialText = getIntent().getStringExtra(EXTRA_INITIAL_TEXT);
        if (initialText != null) textField.setText(initialText);
        textField.setMinLines(2);
        textField.setGravity(Gravity.TOP | Gravity.START);
        root.addView(textField, fieldParams());

        TextView pinyinLabel = new TextView(this);
        pinyinLabel.setText("对应拼音（不需要声调，可连续输入）：");
        pinyinLabel.setTextSize(Typography.CALLOUT);
        LinearLayout.LayoutParams labelParams = fieldParams();
        labelParams.topMargin = dp(14);
        root.addView(pinyinLabel, labelParams);

        pinyinField = new EditText(this);
        pinyinField.setHint("例如：ren gong zhi neng");
        pinyinField.setSingleLine(true);
        pinyinField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        String initialPinyin = getIntent().getStringExtra(EXTRA_INITIAL_PINYIN);
        if (initialPinyin != null) pinyinField.setText(initialPinyin);
        root.addView(pinyinField, fieldParams());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams buttonParams = fieldParams();
        buttonParams.topMargin = dp(18);
        root.addView(buttons, buttonParams);

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setOnClickListener(v -> finish());
        buttons.addView(cancel);

        Button save = new Button(this);
        save.setText("保存");
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        saveParams.leftMargin = dp(8);
        buttons.addView(save, saveParams);
        save.setOnClickListener(v -> saveEntry());

        setContentView(root);
        textField.requestFocus();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        return lp;
    }

    private void saveEntry() {
        String text = textField.getText().toString().trim();
        String pinyin = QuickPhraseStore.normalizePinyin(
                pinyinField.getText().toString());
        if (text.isEmpty() || pinyin.isEmpty()
                || !pinyin.matches("[a-z]+")) {
            pinyinField.setError("请输入有效的拼音字母组合（a-z）");
            return;
        }
        QuickPhraseStore store = new QuickPhraseStore(this);
        if (editIndex >= 0) store.updateAt(editIndex, text, pinyin);
        else store.add(text, pinyin);
        finish();
    }

    private int dp(int value) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics());
    }
}
