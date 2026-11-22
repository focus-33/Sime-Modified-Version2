package com.shiyu.sime.ime.engine;

import com.shiyu.sime.ime.InputAction;
import com.shiyu.sime.ime.InputState;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent memory for complete sentences explicitly selected by the user.
 *
 * <p>Each entry is identified by keyboard layout, the exact original input
 * code, the selected pinyin segmentation, and the committed text. Keeping
 * pinyin in the identity is important for T9: different pronunciations can
 * share the same digit sequence and must remain separate learned entries.
 */
public final class LearnedSentenceStore {
    private static final int MAX_ENTRIES = 2000;
    private static final int MAX_MATCHES = 12;

    public static final class Entry {
        public final String layout;
        public final String code;
        public final String pinyin;
        public final String text;
        public final int uses;
        public final long updatedAt;

        Entry(String layout, String code, String pinyin, String text,
              int uses, long updatedAt) {
            this.layout = layout;
            this.code = code;
            this.pinyin = pinyin;
            this.text = text;
            this.uses = uses;
            this.updatedAt = updatedAt;
        }
    }

    private final File file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public LearnedSentenceStore(File file) {
        this.file = file;
        load();
    }

    /** Return a stable snapshot of all learned sentences for backup/export. */
    public synchronized List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }

    /** Merge one validated record from a portable UTF-8 learning backup. */
    public synchronized void mergeImported(String layout, String code, String pinyin,
                                           String text, int uses, long updatedAt) {
        if (empty(layout) || empty(code) || empty(pinyin) || empty(text)) return;
        String normalizedCode = code.replace("'", "").toLowerCase();
        String normalizedPinyin = normalizePinyin(pinyin);
        if (normalizedCode.isEmpty() || normalizedPinyin.isEmpty()) return;
        String k = key(layout, normalizedCode, normalizedPinyin, text);
        Entry old = entries.get(k);
        int mergedUses = Math.max(1, uses) + (old == null ? 0 : old.uses);
        long mergedTime = Math.max(updatedAt, old == null ? 0L : old.updatedAt);
        entries.put(k, new Entry(layout, normalizedCode, normalizedPinyin,
                text, mergedUses, mergedTime));
        trim();
        save();
    }

    /** Return exact-code matches, most frequently selected first. */
    public synchronized List<Entry> find(String layout, String code) {
        List<Entry> found = new ArrayList<>();
        if (empty(layout) || empty(code)) return found;
        String normalizedCode = code.replace("'", "").toLowerCase();
        for (Entry e : entries.values()) {
            if (layout.equals(e.layout) && normalizedCode.equals(e.code)) found.add(e);
        }
        found.sort(Comparator.comparingInt((Entry e) -> e.uses).reversed()
                .thenComparing(Comparator.comparingLong((Entry e) -> e.updatedAt).reversed()));
        if (found.size() > MAX_MATCHES) {
            return new ArrayList<>(found.subList(0, MAX_MATCHES));
        }
        return found;
    }

    /**
     * Recover the exact code originally typed, reversing T9 syllable/fallback
     * substitutions from the newest action to the oldest without mutating state.
     */
    public static String originalInputCode(InputState state) {
        if (state == null || state.buffer == null) return "";
        StringBuilder restored = new StringBuilder(state.buffer);
        for (InputAction action : state.undoStack) {
            if (action.type != InputAction.Type.PINYIN_PICK
                    && action.type != InputAction.Type.FALLBACK_PICK) continue;
            int start = action.start;
            int end = start + (action.leadingSeparator ? 1 : 0)
                    + action.pickedLetters.length();
            if (start < 0 || end > restored.length() || action.replacedDigits == null) continue;
            restored.replace(start, end, action.replacedDigits);
        }
        return restored.toString().replace("'", "").toLowerCase();
    }

    /** Join the pinyin attached to each selected segment into one sentence reading. */
    public static String selectedPinyin(InputState state) {
        if (state == null) return "";
        StringBuilder pinyin = new StringBuilder();
        for (InputState.Selection selection : state.selections) {
            if (selection.pinyin == null || selection.pinyin.isEmpty()) continue;
            String part = selection.pinyin;
            while (part.startsWith("'")) part = part.substring(1);
            while (part.endsWith("'")) part = part.substring(0, part.length() - 1);
            if (part.isEmpty()) continue;
            if (pinyin.length() > 0) pinyin.append('\'');
            pinyin.append(part);
        }
        return normalizePinyin(pinyin.toString());
    }

    /** Record one completed, explicitly selected sentence. */
    public synchronized void learn(String layout, String code,
                                   String pinyin, String text) {
        if (empty(layout) || empty(code) || empty(pinyin) || empty(text)) return;
        String normalizedCode = code.replace("'", "").toLowerCase();
        String normalizedPinyin = normalizePinyin(pinyin);
        if (normalizedCode.isEmpty() || normalizedPinyin.isEmpty()) return;

        String key = key(layout, normalizedCode, normalizedPinyin, text);
        Entry old = entries.get(key);
        int uses = old == null ? 1 : old.uses + 1;
        entries.put(key, new Entry(layout, normalizedCode, normalizedPinyin,
                text, uses, System.currentTimeMillis()));
        trim();
        save();
    }

    private static String normalizePinyin(String pinyin) {
        String value = pinyin.toLowerCase().trim();
        while (value.startsWith("'")) value = value.substring(1);
        while (value.endsWith("'")) value = value.substring(0, value.length() - 1);
        while (value.contains("''")) value = value.replace("''", "'");
        return value;
    }

    private static String key(String layout, String code, String pinyin, String text) {
        return layout + '\u0000' + code + '\u0000' + pinyin + '\u0000' + text;
    }

    private static boolean empty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private void trim() {
        if (entries.size() <= MAX_ENTRIES) return;
        List<Entry> all = new ArrayList<>(entries.values());
        all.sort(Comparator.comparingInt((Entry e) -> e.uses)
                .thenComparingLong(e -> e.updatedAt));
        int remove = entries.size() - MAX_ENTRIES;
        for (int i = 0; i < remove; i++) {
            Entry e = all.get(i);
            entries.remove(key(e.layout, e.code, e.pinyin, e.text));
        }
    }

    private void load() {
        if (file == null || !file.isFile()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\\t", -1);
                if (p.length != 6 || !p[0].equals("S")) continue;
                try {
                    String layout = dec(p[1]);
                    String code = dec(p[2]);
                    String pinyin = dec(p[3]);
                    String text = dec(p[4]);
                    int uses = Math.max(1, Integer.parseInt(p[5].split(",", -1)[0]));
                    long updated = p[5].contains(",")
                            ? Long.parseLong(p[5].substring(p[5].indexOf(',') + 1)) : 0L;
                    Entry e = new Entry(layout, code, pinyin, text, uses, updated);
                    entries.put(key(layout, code, pinyin, text), e);
                } catch (RuntimeException ignored) {
                    // Skip a malformed record without losing the remaining file.
                }
            }
        } catch (IOException ignored) {
            // Start empty if the learning file cannot be read.
        }
        trim();
    }

    private void save() {
        if (file == null) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return;
        File temp = new File(file.getPath() + ".tmp");
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(temp), StandardCharsets.UTF_8))) {
            for (Entry e : entries.values()) {
                w.write("S\t" + enc(e.layout) + "\t" + enc(e.code) + "\t"
                        + enc(e.pinyin) + "\t" + enc(e.text) + "\t"
                        + e.uses + "," + e.updatedAt);
                w.newLine();
            }
        } catch (IOException ignored) {
            // Keep in-memory learning if storage is temporarily unavailable.
            return;
        }
        if (temp.renameTo(file)) return;
        // Some filesystems do not replace an existing destination on rename.
        if (file.exists() && file.delete() && temp.renameTo(file)) return;
        // Last-resort replacement; the in-memory store remains usable if it fails.
        try (FileInputStream in = new FileInputStream(temp);
             FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        } catch (IOException ignored) {
            // Keep in-memory learning if storage is temporarily unavailable.
        }
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String dec(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
