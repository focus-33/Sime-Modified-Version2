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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Imports the portable UTF-8 learning backup exported by Sime. */
public final class ImportLearningActivity extends Activity {
    private static final int REQUEST_OPEN_DOCUMENT = 7106;
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;

    private static final class SentenceRow {
        String layout, code, pinyin, text;
        int uses;
        long updated;
    }

    private static final class ContextRow {
        String pinyin, text;
        int[] left, target, right;
        int uses;
        long updated;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        try {
            startActivityForResult(intent, REQUEST_OPEN_DOCUMENT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "系统没有可用的文件选择应用", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN_DOCUMENT) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }
        try {
            importFile(data.getData());
        } catch (Exception e) {
            Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private void importFile(Uri uri) throws IOException {
        byte[] bytes;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("无法读取所选文件");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > MAX_FILE_BYTES) throw new IOException("文件超过 4 MB，已拒绝导入");
                out.write(buffer, 0, n);
            }
            bytes = out.toByteArray();
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        List<SentenceRow> sentences = new ArrayList<>();
        List<ContextRow> contexts = new ArrayList<>();
        List<String> nativeLines = new ArrayList<>();
        String section = "";
        for (String line : content.split("\\r?\\n", -1)) {
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line;
                continue;
            }
            if (line.isEmpty()) {
                if ("[原生语言模型学习记录]".equals(section)) nativeLines.add("");
                continue;
            }
            if (line.startsWith("#")) continue;
            if ("[整句记忆]".equals(section)) {
                String[] f = line.split("\\t", -1);
                if (f.length != 6) throw new IOException("整句记忆字段数量错误");
                SentenceRow row = new SentenceRow();
                row.layout = unescape(f[0]); row.code = unescape(f[1]);
                row.pinyin = unescape(f[2]); row.text = unescape(f[3]);
                row.uses = positiveInt(f[4]); row.updated = nonNegativeLong(f[5]);
                if (row.layout.isEmpty() || row.code.isEmpty() || row.pinyin.isEmpty() || row.text.isEmpty())
                    throw new IOException("整句记忆包含空字段");
                sentences.add(row);
            } else if ("[上下文选词记忆]".equals(section)) {
                String[] f = line.split("\\t", -1);
                if (f.length != 7) throw new IOException("上下文记忆字段数量错误");
                ContextRow row = new ContextRow();
                row.pinyin = unescape(f[0]); row.text = unescape(f[1]);
                row.left = parseIds(f[2], 2); row.target = parseIds(f[3], 64);
                row.right = parseIds(f[4], 2); row.uses = positiveInt(f[5]);
                row.updated = nonNegativeLong(f[6]);
                if (row.pinyin.isEmpty() || row.text.isEmpty()) throw new IOException("上下文记忆包含空字段");
                contexts.add(row);
            } else if ("[原生语言模型学习记录]".equals(section)) {
                nativeLines.add(line);
            }
        }
        if (sentences.isEmpty() && contexts.isEmpty() && nativeLines.isEmpty())
            throw new IOException("文件中没有可识别的 Sime 自学习记录");

        File dir = new File(getFilesDir(), "sime");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建学习数据目录");
        LearnedSentenceStore sentenceStore = new LearnedSentenceStore(new File(dir, "learned_sentences.txt"));
        ContextualSelectionMemory contextStore = new ContextualSelectionMemory(
                new File(dir, "contextual_selection_memory.txt"));
        for (SentenceRow row : sentences) {
            sentenceStore.mergeImported(row.layout, row.code, row.pinyin, row.text, row.uses, row.updated);
        }
        for (ContextRow row : contexts) {
            contextStore.mergeImported(row.pinyin, row.text, row.left, row.target, row.right,
                    row.uses, row.updated);
        }

        String nativeResult = "原生记录：无";
        if (!nativeLines.isEmpty()) {
            nativeResult = importNativeSection(dir, nativeLines);
        }
        Toast.makeText(this, "导入完成：整句 " + sentences.size() + " 条，上下文 "
                + contexts.size() + " 条；" + nativeResult, Toast.LENGTH_LONG).show();
    }

    private String importNativeSection(File dir, List<String> imported) throws IOException {
        if (imported.size() < 2 || !"SIME_USER_SENTENCE_V4".equals(imported.get(0))
                || !imported.get(1).startsWith("VOCAB ")) {
            return "原生记录格式无效，已跳过";
        }
        String currentSignature = SimeService.currentNativeVocabSignature();
        if (currentSignature.isEmpty()) return "输入法引擎未就绪，原生记录已跳过";
        if (!("VOCAB " + currentSignature).equals(imported.get(1))) {
            return "词库签名不匹配，原生记录已跳过";
        }
        File current = new File(dir, "sentences.txt");
        List<String> existing;
        if (current.isFile()) {
            existing = readLines(current);
            if (existing.size() < 2 || !"SIME_USER_SENTENCE_V4".equals(existing.get(0))
                    || !existing.get(1).equals(imported.get(1))) {
                return "当前原生记录格式或词库签名无效，已跳过";
            }
        } else {
            existing = new ArrayList<>();
            existing.add("SIME_USER_SENTENCE_V4");
            existing.add(imported.get(1));
        }
        StringBuilder merged = new StringBuilder();
        merged.append(existing.get(0)).append('\n').append(existing.get(1)).append('\n');
        appendBody(merged, existing);
        appendBody(merged, imported);
        File temp = new File(dir, "sentences_import_merged.txt");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(merged.toString().getBytes(StandardCharsets.UTF_8));
        }
        boolean ok = SimeService.importMergedNativeLearning(temp);
        //noinspection ResultOfMethodCallIgnored
        temp.delete();
        return ok ? "原生记录已合并" : "输入法引擎未就绪，原生记录已跳过";
    }

    private static void appendBody(StringBuilder out, List<String> lines) {
        for (int i = 2; i < lines.size(); i++) {
            out.append(lines.get(i)).append('\n');
        }
        if (lines.size() <= 2 || !lines.get(lines.size() - 1).isEmpty()) out.append('\n');
    }

    private static List<String> readLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    private static int positiveInt(String s) throws IOException {
        try { int v = Integer.parseInt(s); if (v <= 0) throw new NumberFormatException(); return v; }
        catch (NumberFormatException e) { throw new IOException("使用次数无效"); }
    }

    private static long nonNegativeLong(String s) throws IOException {
        try { long v = Long.parseLong(s); if (v < 0) throw new NumberFormatException(); return v; }
        catch (NumberFormatException e) { throw new IOException("更新时间无效"); }
    }

    private static int[] parseIds(String s, int max) throws IOException {
        if (s.isEmpty()) return new int[0];
        String[] parts = s.split(",", -1);
        if (parts.length > max) throw new IOException("Token ID 数量超出限制");
        int[] ids = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                ids[i] = Integer.parseInt(parts[i]);
                if (ids[i] < 0) throw new NumberFormatException();
            }
        } catch (NumberFormatException e) { throw new IOException("Token ID 格式无效"); }
        return ids;
    }

    private static String unescape(String s) throws IOException {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') { out.append(c); continue; }
            if (++i >= s.length()) throw new IOException("转义字段不完整");
            switch (s.charAt(i)) {
                case '\\': out.append('\\'); break;
                case 't': out.append('\t'); break;
                case 'r': out.append('\r'); break;
                case 'n': out.append('\n'); break;
                default: throw new IOException("未知转义字符");
            }
        }
        return out.toString();
    }
}
