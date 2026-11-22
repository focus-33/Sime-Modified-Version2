package com.shiyu.sime;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import com.shiyu.sime.ime.engine.ContextualSelectionMemory;
import com.shiyu.sime.ime.engine.LearnedSentenceStore;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Exports Sime's independent learning stores as a readable UTF-8 text file. */
public final class ExportLearningActivity extends Activity {
    private static final int REQUEST_CREATE_DOCUMENT = 7104;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "Sime-自学习文件-"
                + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date())
                + ".txt");
        try {
            startActivityForResult(intent, REQUEST_CREATE_DOCUMENT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "系统没有可用的文件保存应用", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CREATE_DOCUMENT) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }
        Uri uri = data.getData();
        try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) throw new IOException("无法打开目标文件");
            writeExport(out);
            Toast.makeText(this, "自学习文件已导出", Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private void writeExport(OutputStream out) throws IOException {
        File dir = new File(getFilesDir(), "sime");
        LearnedSentenceStore sentences = new LearnedSentenceStore(
                new File(dir, "learned_sentences.txt"));
        ContextualSelectionMemory contexts = new ContextualSelectionMemory(
                new File(dir, "contextual_selection_memory.txt"));
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                out, StandardCharsets.UTF_8))) {
            w.write("# Sime 自学习文件\n");
            w.write("# 编码：UTF-8。此文件是可读备份；编辑它不会自动修改输入法内部记忆。\n");
            w.write("# 字段中的反斜杠转义：\\\\ 表示反斜杠，\\t 表示制表符，\\n 表示换行。\n\n");

            w.write("[整句记忆]\n");
            w.write("# 键盘布局\\t原始输入码\\t已选拼音\\t整句\\t使用次数\\t更新时间(毫秒)\n");
            for (LearnedSentenceStore.Entry e : sentences.entries()) {
                w.write(escape(e.layout) + "\t" + escape(e.code) + "\t"
                        + escape(e.pinyin) + "\t" + escape(e.text) + "\t"
                        + e.uses + "\t" + e.updatedAt + "\n");
            }

            w.write("\n[上下文选词记忆]\n");
            w.write("# 拼音\\t所选词语\\t左上下文TokenID\\t目标词TokenID\\t右上下文TokenID\\t使用次数\\t更新时间(毫秒)\n");
            for (ContextualSelectionMemory.Entry e : contexts.entries()) {
                w.write(escape(e.pinyin) + "\t" + escape(e.text) + "\t"
                        + ints(e.leftContext) + "\t" + ints(e.targetTokenIds) + "\t"
                        + ints(e.rightContext) + "\t" + e.uses + "\t"
                        + e.updatedAt + "\n");
            }

            // The original native language-model history is also a plain-text
            // file. Include it verbatim so the backup covers every learning
            // store, while warning that token IDs are engine-internal.
            w.write("\n[原生语言模型学习记录]\n");
            w.write("# 以下内容来自 sentences.txt，保留原格式；token ID 与当前词库绑定，不建议手工改动。\n");
            File nativeFile = new File(dir, "sentences.txt");
            if (nativeFile.isFile()) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        new FileInputStream(nativeFile), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        w.write(line);
                        w.write('\n');
                    }
                }
            } else {
                w.write("# 当前尚无已保存的原生语言模型学习记录。\n");
            }
            w.flush();
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\t", "\\t")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String ints(int[] values) {
        if (values == null || values.length == 0) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) b.append(',');
            b.append(values[i]);
        }
        return b.toString();
    }
}
