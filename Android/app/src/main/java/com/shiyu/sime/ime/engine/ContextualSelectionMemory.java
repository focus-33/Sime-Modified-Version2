package com.shiyu.sime.ime.engine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Learns words/phrases that the user explicitly selects and their context.
 *
 * <p>This is deliberately separate from {@link LearnedSentenceStore}: it does
 * not replay a previously committed sentence. It only adjusts the ranking of
 * candidates already returned by the decoder when their pronunciation and
 * selected text match a learned choice. Matching left or right context gives
 * a stronger boost; a context-free preference gives only a small global nudge.
 */
public final class ContextualSelectionMemory {
    private static final int MAX_ENTRIES = 3000;
    private static final int MAX_CONTEXT = 2;

    public static final class Entry {
        public final String pinyin;
        public final String text;
        public final int[] leftContext;
        public final int[] targetTokenIds;
        public final int[] rightContext;
        public final int uses;
        public final long updatedAt;

        Entry(String pinyin, String text, int[] leftContext, int[] targetTokenIds,
              int[] rightContext, int uses, long updatedAt) {
            this.pinyin = pinyin;
            this.text = text;
            this.leftContext = leftContext.clone();
            this.targetTokenIds = targetTokenIds.clone();
            this.rightContext = rightContext.clone();
            this.uses = uses;
            this.updatedAt = updatedAt;
        }
    }

    private final File file;
    /** Keyed by pronunciation plus surrounding token context; latest choice wins. */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public ContextualSelectionMemory(File file) {
        this.file = file;
        load();
    }

    /** Convenience overload for callers/tests without token-sequence context. */
    public synchronized void learn(String pinyin, String text, int[] precedingContext) {
        learn(pinyin, text, precedingContext, new int[0], new int[0]);
    }

    /**
     * Record one explicit candidate selection. Only the last two preceding
     * and following token IDs are retained. Re-selecting a different word for
     * the same pronunciation and surrounding context replaces the old choice.
     */
    public synchronized void learn(String pinyin, String text, int[] precedingContext,
                                   int[] targetTokenIds, int[] followingContext) {
        String normalized = normalizePinyin(pinyin);
        if (normalized.isEmpty() || empty(text)) return;
        int[] left = tail(precedingContext, MAX_CONTEXT);
        int[] target = targetTokenIds == null ? new int[0] : targetTokenIds.clone();
        int[] right = head(followingContext, MAX_CONTEXT);
        String key = key(normalized, left, right);
        Entry old = entries.get(key);
        int uses = old != null && old.text.equals(text) ? old.uses + 1 : 1;
        entries.put(key, new Entry(normalized, text, left, target, right,
                uses, System.currentTimeMillis()));
        trim();
        save();
    }

    /**
     * Apply a conservative, context-sensitive score adjustment. Candidates
     * remain decoder-generated; no learned text is injected into the list.
     * Candidate order is stable when adjusted scores are equal.
     */
    public synchronized List<DecodeResult> apply(List<DecodeResult> candidates,
                                                  int[] currentContext) {
        if (candidates == null || candidates.isEmpty() || entries.isEmpty()) {
            return candidates == null ? Collections.emptyList() : candidates;
        }
        int[] left = tail(currentContext, MAX_CONTEXT);
        List<DecodeResult> adjusted = new ArrayList<>(candidates.size());
        for (DecodeResult candidate : candidates) {
            if (candidate == null) continue;
            String pinyin = normalizePinyin(candidate.units);
            if (pinyin.isEmpty() || empty(candidate.text)) {
                adjusted.add(candidate);
                continue;
            }
            int globalUses = 0;
            float contextualBoost = 0f;
            for (Entry entry : entries.values()) {
                boolean samePinyin = pinyin.equals(entry.pinyin);
                boolean hasLearnedPinyinPrefix = pinyinStartsWith(pinyin, entry.pinyin);
                if (!samePinyin && !hasLearnedPinyinPrefix) continue;
                if (samePinyin && candidate.text.equals(entry.text)) {
                    globalUses += entry.uses;
                    contextualBoost = Math.max(contextualBoost,
                            contextBoost(entry.leftContext, left, entry.uses));
                }
                // If the decoder offers a longer candidate beginning with the
                // learned word, use its token sequence to check the learned
                // right context. This is especially useful for sentence starts.
                if (entry.rightContext.length > 0
                        && candidate.text.startsWith(entry.text)
                        && hasLearnedPinyinPrefix
                        && startsWith(candidate.tokenIds, entry.targetTokenIds)) {
                    int afterTarget = entry.targetTokenIds.length;
                    int matched = prefixOverlap(entry.rightContext,
                            candidate.tokenIds, afterTarget);
                    if (matched > 0) {
                        float rightBoost = matched == entry.rightContext.length
                                ? 1.25f : 0.38f;
                        rightBoost += Math.min(0.30f,
                                (float) (Math.log1p(entry.uses) * 0.10));
                        contextualBoost = Math.max(contextualBoost, rightBoost);
                    }
                }
            }
            if (globalUses == 0 && contextualBoost == 0f) {
                adjusted.add(candidate);
                continue;
            }
            // Do not generalize a one-off contextual choice to unrelated
            // sentences. A weak cross-context preference appears only after
            // repeated confirmations of the same pronunciation/text pair.
            float globalBoost = globalUses < 3 ? 0f : Math.min(0.30f,
                    (float) (Math.log1p(globalUses) * 0.10));
            float boost = Math.max(globalBoost, contextualBoost);
            adjusted.add(new DecodeResult(candidate.text, candidate.units,
                    candidate.consumed, candidate.tokenIds, candidate.score + boost,
                    candidate.fuzzyMatchCount, candidate.fuzzyPenalty));
        }
        Collections.sort(adjusted, (a, b) -> Float.compare(b.score, a.score));
        return adjusted;
    }

    /** Merge one validated record from a portable UTF-8 learning backup. */
    public synchronized void mergeImported(String pinyin, String text, int[] left,
                                           int[] target, int[] right,
                                           int uses, long updatedAt) {
        String normalized = normalizePinyin(pinyin);
        if (normalized.isEmpty() || empty(text)) return;
        int[] l = tail(left, MAX_CONTEXT);
        int[] t = target == null ? new int[0] : target.clone();
        int[] r = head(right, MAX_CONTEXT);
        String k = key(normalized, l, r);
        Entry old = entries.get(k);
        int mergedUses = Math.max(1, uses);
        long mergedTime = updatedAt;
        if (old != null) {
            if (old.text.equals(text)) mergedUses += old.uses;
            else mergedUses = Math.max(1, uses);
            mergedTime = Math.max(updatedAt, old.updatedAt);
        }
        entries.put(k, new Entry(normalized, text, l, t, r, mergedUses, mergedTime));
        trim();
        save();
    }

    public synchronized List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }

    public synchronized void clear() {
        entries.clear();
        save();
    }

    private static float contextBoost(int[] learned, int[] current, int uses) {
        int overlap = suffixOverlap(learned, current);
        if (overlap <= 0) return 0f;
        float base;
        if (learned.length == 1 && overlap == 1) base = 0.90f;
        else if (learned.length == 2 && overlap == 2) base = 1.40f;
        else base = 0.40f;
        return base + Math.min(0.35f, (float) (Math.log1p(uses) * 0.12));
    }

    private static int suffixOverlap(int[] learned, int[] current) {
        int max = Math.min(learned.length, current.length);
        int best = 0;
        for (int n = 1; n <= max; n++) {
            boolean same = true;
            for (int i = 1; i <= n; i++) {
                if (learned[learned.length - i] != current[current.length - i]) {
                    same = false;
                    break;
                }
            }
            if (same) best = n;
        }
        return best;
    }

    private static int prefixOverlap(int[] learnedRight, int[] candidateTokens, int offset) {
        if (candidateTokens == null || offset < 0 || offset >= candidateTokens.length) return 0;
        int max = Math.min(learnedRight.length, candidateTokens.length - offset);
        int matched = 0;
        while (matched < max && learnedRight[matched] == candidateTokens[offset + matched]) {
            matched++;
        }
        return matched;
    }

    private static boolean startsWith(int[] value, int[] prefix) {
        if (value == null || prefix == null || prefix.length == 0
                || value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) return false;
        }
        return true;
    }

    private static boolean pinyinStartsWith(String value, String prefix) {
        return value.equals(prefix) || value.startsWith(prefix + "'");
    }

    private static int[] tail(int[] values, int max) {
        if (values == null || values.length == 0) return new int[0];
        int n = Math.min(max, values.length);
        int[] result = new int[n];
        System.arraycopy(values, values.length - n, result, 0, n);
        return result;
    }

    private static int[] head(int[] values, int max) {
        if (values == null || values.length == 0) return new int[0];
        int n = Math.min(max, values.length);
        int[] result = new int[n];
        System.arraycopy(values, 0, result, 0, n);
        return result;
    }

    private static String normalizePinyin(String pinyin) {
        if (pinyin == null) return "";
        String value = pinyin.toLowerCase().trim();
        while (value.startsWith("'")) value = value.substring(1);
        while (value.endsWith("'")) value = value.substring(0, value.length() - 1);
        while (value.contains("''")) value = value.replace("''", "'");
        return value;
    }

    private static String key(String pinyin, int[] left, int[] right) {
        return pinyin + '\u0000' + contextString(left) + '\u0000' + contextString(right);
    }

    private static String contextString(int[] context) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < context.length; i++) {
            if (i > 0) out.append(',');
            out.append(context[i]);
        }
        return out.toString();
    }

    private static int[] parseContext(String value) {
        return parseIntArray(value, MAX_CONTEXT);
    }

    private static int[] parseIntArray(String value, int max) {
        if (value == null || value.isEmpty()) return new int[0];
        String[] parts = value.split(",");
        int n = Math.min(max, parts.length);
        int[] result = new int[n];
        for (int i = 0; i < n; i++) result[i] = Integer.parseInt(parts[i]);
        return result;
    }

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void trim() {
        if (entries.size() <= MAX_ENTRIES) return;
        List<Entry> all = new ArrayList<>(entries.values());
        all.sort(Comparator.comparingInt((Entry e) -> e.uses)
                .thenComparingLong(e -> e.updatedAt));
        int remove = entries.size() - MAX_ENTRIES;
        for (int i = 0; i < remove; i++) {
            Entry e = all.get(i);
            entries.remove(key(e.pinyin, e.leftContext, e.rightContext));
        }
    }

    private void load() {
        if (file == null || !file.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\\t", -1);
                if (fields.length != 8 || !fields[0].equals("C")) continue;
                try {
                    String pinyin = dec(fields[1]);
                    String text = dec(fields[2]);
                    int[] left = parseContext(fields[3]);
                    int[] target = parseIntArray(fields[4], 64);
                    int[] right = parseIntArray(fields[5], MAX_CONTEXT);
                    int uses = Math.max(1, Integer.parseInt(fields[6]));
                    long updated = Long.parseLong(fields[7]);
                    Entry entry = new Entry(pinyin, text, left, target, right, uses, updated);
                    entries.put(key(pinyin, left, right), entry);
                } catch (RuntimeException ignored) {
                    // Skip a damaged record and keep the remaining history.
                }
            }
        } catch (IOException ignored) {
            // An unreadable learning file starts as an empty model.
        }
        trim();
    }

    private void save() {
        if (file == null) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return;
        File temp = new File(file.getPath() + ".tmp");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(temp), StandardCharsets.UTF_8))) {
            for (Entry entry : entries.values()) {
                writer.write("C\t" + enc(entry.pinyin) + "\t" + enc(entry.text)
                        + "\t" + contextString(entry.leftContext)
                        + "\t" + contextString(entry.targetTokenIds)
                        + "\t" + contextString(entry.rightContext)
                        + "\t" + entry.uses + "\t" + entry.updatedAt);
                writer.newLine();
            }
        } catch (IOException ignored) {
            return;
        }
        if (temp.renameTo(file)) return;
        if (file.exists() && file.delete() && temp.renameTo(file)) return;
        try (FileInputStream in = new FileInputStream(temp);
             FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        } catch (IOException ignored) {
            // Keep the in-memory model usable if storage is temporarily unavailable.
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
