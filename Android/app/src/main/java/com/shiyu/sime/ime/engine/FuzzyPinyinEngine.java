package com.shiyu.sime.ime.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java fuzzy-pinyin variant generator. It does not access the dictionary,
 * rank candidates, or depend on Android classes, so it can be unit tested alone.
 */
public final class FuzzyPinyinEngine {
    public static final int MAX_VARIANTS = 8;

    private FuzzyPinyinEngine() {}

    /** Generate one-rule variants for a complete full-pinyin string. */
    public static List<FuzzyVariant> expandPinyin(String input) {
        return expand(input, false);
    }

    /** Generate one-rule variants for a complete T9 digit string. */
    public static List<FuzzyVariant> expandT9(String digits) {
        return expand(digits, true);
    }

    private static List<FuzzyVariant> expand(String input, boolean t9) {
        if (input == null || input.isEmpty()) return Collections.emptyList();
        LinkedHashMap<String, FuzzyVariant> unique = new LinkedHashMap<>();
        int chunkStart = 0;
        for (int i = 0; i <= input.length(); i++) {
            if (i != input.length() && input.charAt(i) != '\'') continue;
            if (i > chunkStart) {
                String chunk = input.substring(chunkStart, i);
                addChunkVariants(input, chunk, chunkStart, t9, unique);
            }
            chunkStart = i + 1;
        }
        return new ArrayList<>(unique.values());
    }

    private static void addChunkVariants(String original, String chunk, int offset,
                                         boolean t9,
                                         LinkedHashMap<String, FuzzyVariant> out) {
        Set<String> syllableKeys = new HashSet<>();
        for (String syllable : PinyinSyllables.allSyllables()) {
            syllableKeys.add(key(syllable, t9));
        }
        int n = chunk.length();
        boolean[] prefix = new boolean[n + 1];
        boolean[] suffix = new boolean[n + 1];
        prefix[0] = true;
        for (int i = 0; i < n; i++) {
            if (!prefix[i]) continue;
            for (String k : syllableKeys) {
                if (chunk.startsWith(k, i)) prefix[i + k.length()] = true;
            }
        }
        // The final syllable may still be in progress while the user types.
        // Earlier syllables must be complete; only the suffix may be a prefix.
        suffix[n] = true;
        for (int i = n - 1; i >= 0; i--) {
            String remainder = chunk.substring(i);
            for (String k : syllableKeys) {
                if (k.startsWith(remainder)) {
                    suffix[i] = true;
                    break;
                }
                if (chunk.startsWith(k, i) && suffix[i + k.length()]) {
                    suffix[i] = true;
                }
            }
        }

        // Collect variants separately for each fuzzy rule first. The previous
        // rule-first traversal could let en/eng consume all eight slots before
        // in/ing was considered, making results depend on rule declaration
        // order rather than giving each enabled fuzzy pair a fair chance.
        List<LinkedHashMap<String, FuzzyVariant>> byRule = new ArrayList<>();
        for (FuzzyPinyinRules.Rule rule : FuzzyPinyinRules.all()) {
            LinkedHashMap<String, FuzzyVariant> ruleVariants = new LinkedHashMap<>();
            for (String sourceSyllable : PinyinSyllables.allSyllables()) {
                if (!sourceSyllable.endsWith(rule.from)) continue;
                String targetSyllable = sourceSyllable.substring(
                        0, sourceSyllable.length() - rule.from.length()) + rule.to;
                if (!PinyinSyllables.isSyllable(targetSyllable)) continue;
                String from = key(sourceSyllable, t9);
                String to = key(targetSyllable, t9);
                for (int i = 0; i + from.length() <= n; i++) {
                    int end = i + from.length();
                    if (!prefix[i] || !suffix[end] || !chunk.startsWith(from, i)) continue;
                    FuzzyVariant variant = new FuzzyVariant(original,
                            offset + i, offset + end, to, sourceSyllable,
                            targetSyllable, rule.id, rule.penalty);
                    if (!variant.value.equals(original)) {
                        // T9 digit strings can represent different pinyin
                        // syllables (e.g. xing and ying). Keep distinct
                        // source/target mappings even when their query digits
                        // are identical; the decoded units identify the right one.
                        String variantKey = variant.value + "\u0001" + (offset + i)
                                + "\u0001" + sourceSyllable + "\u0001" + targetSyllable;
                        ruleVariants.putIfAbsent(variantKey, variant);
                    }
                }
            }
            byRule.add(ruleVariants);
        }

        // Round-robin across rule families, but cap distinct query strings,
        // not mapping variants. In T9, different pinyin syllables can share
        // exactly the same digit query (for example xing->xin and ying->yin).
        // Keep every source/target mapping for an admitted query so the decoder
        // can validate the returned pinyin units and select the correct one.
        // The decoder caches by variant.value, so these extra mappings do not
        // cause extra native queries.
        Set<String> selectedQueries = new HashSet<>();
        for (FuzzyVariant existing : out.values()) {
            selectedQueries.add(existing.value);
        }

        int maxRounds = 0;
        for (LinkedHashMap<String, FuzzyVariant> ruleVariants : byRule) {
            maxRounds = Math.max(maxRounds, ruleVariants.size());
        }
        for (int round = 0; round < maxRounds; round++) {
            for (LinkedHashMap<String, FuzzyVariant> ruleVariants : byRule) {
                if (round >= ruleVariants.size()) continue;
                Map.Entry<String, FuzzyVariant> entry =
                        new ArrayList<>(ruleVariants.entrySet()).get(round);
                String query = entry.getValue().value;
                if (!selectedQueries.contains(query)
                        && selectedQueries.size() >= MAX_VARIANTS) {
                    continue;
                }
                if (!out.containsKey(entry.getKey())) {
                    out.put(entry.getKey(), entry.getValue());
                    selectedQueries.add(query);
                }
            }
        }
    }

    private static String key(String syllable, boolean t9) {
        if (!t9) return syllable;
        StringBuilder out = new StringBuilder(syllable.length());
        for (int i = 0; i < syllable.length(); i++) {
            char c = syllable.charAt(i);
            if (c >= 'a' && c <= 'c') out.append('2');
            else if (c >= 'd' && c <= 'f') out.append('3');
            else if (c >= 'g' && c <= 'i') out.append('4');
            else if (c >= 'j' && c <= 'l') out.append('5');
            else if (c >= 'm' && c <= 'o') out.append('6');
            else if (c >= 'p' && c <= 's') out.append('7');
            else if (c >= 't' && c <= 'v') out.append('8');
            else if (c >= 'w' && c <= 'z') out.append('9');
            else return "";
        }
        return out.toString();
    }
}
