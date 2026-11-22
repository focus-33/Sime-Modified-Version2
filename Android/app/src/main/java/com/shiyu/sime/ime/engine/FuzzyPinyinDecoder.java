package com.shiyu.sime.ime.engine;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adds one-step fuzzy-pinyin retrieval around an existing Decoder. Exact
 * results remain first; fuzzy results carry a score penalty and mapped input
 * offsets. The wrapped native decoder and its dictionary are not modified.
 */
public final class FuzzyPinyinDecoder implements Decoder {
    private static final int MAX_FUZZY_RESULTS = 6;
    private static final int MAX_T9_QUERIES = 8;
    private final Decoder base;

    public FuzzyPinyinDecoder(Decoder base) {
        if (base == null) throw new IllegalArgumentException("base decoder is required");
        this.base = base;
    }

    @Override
    public DecodeResult[] decodeSentence(String pinyin, int limit) {
        DecodeResult[] exact = safe(base.decodeSentence(pinyin, limit));
        if (pinyin == null || pinyin.isEmpty()) return exact;
        List<FuzzyVariant> variants = FuzzyPinyinEngine.expandPinyin(pinyin);
        return appendFuzzy(exact, variants,
                variant -> base.decodeSentence(variant.value, limit),
                r -> r.consumed);
    }

    @Override
    public DecodeResult[] decodeNumSentence(String startLetters, String digits, int limit) {
        String start = startLetters == null ? "" : startLetters;
        String nums = digits == null ? "" : digits;
        DecodeResult[] exact = safe(base.decodeNumSentence(start, nums, limit));
        final boolean diagnostic = nums.length() >= 30;
        if (diagnostic) {
            logQuery("EXACT", start, nums, exact);
        }
        if (start.isEmpty() && nums.isEmpty()) return exact;

        List<DecodeResult> fuzzy = new ArrayList<>();
        Set<String> exactKeys = keys(exact);
        Map<String, DecodeResult> best = new HashMap<>();
        Map<String, FuzzyOrigin> origins = new HashMap<>();

        // T9 syllables already selected in the letter strip: transform only
        // that explicit prefix, leaving the still-ambiguous digit suffix intact.
        int queries = 0;
        int startUnitsLength = start.replace("'", "").length();
        List<FuzzyVariant> startVariants = FuzzyPinyinEngine.expandPinyin(start);
        if (diagnostic && !startVariants.isEmpty()) {
            Log.d("SIME_FUZZY_QUERY", "START_VARIANTS count=" + startVariants.size());
        }
        for (FuzzyVariant v : startVariants) {
            if (queries++ >= MAX_T9_QUERIES) {
                if (diagnostic) Log.d("SIME_FUZZY_QUERY", "START_VARIANT_SKIPPED " + v.diagnosticInfo());
                break;
            }
            DecodeResult[] found = safe(base.decodeNumSentence(v.value, nums, limit));
            if (diagnostic) logQuery("START_FUZZY " + v.diagnosticInfo(), v.value, nums, found);
            collectMappedStart(found, v, exactKeys, best, origins);
        }

        // Unselected T9 digits: syllable boundaries are inferred only when the
        // complete digit chunk can be segmented into legal pinyin syllables.
        Map<String, DecodeResult[]> t9QueryCache = new HashMap<>();
        int prefixUnitsLength = startUnitsLength;
        List<FuzzyVariant> t9Variants = FuzzyPinyinEngine.expandT9(nums);
        if (diagnostic) {
            Log.d("SIME_FUZZY_QUERY", "T9_VARIANTS count=" + t9Variants.size()
                    + " remainingQueryBudget=" + Math.max(0, MAX_T9_QUERIES - queries));
        }
        for (FuzzyVariant v : t9Variants) {
            DecodeResult[] found = t9QueryCache.get(v.value);
            if (found == null) {
                if (queries >= MAX_T9_QUERIES) {
                    if (diagnostic) Log.d("SIME_FUZZY_QUERY", "T9_VARIANT_SKIPPED " + v.diagnosticInfo());
                    break;
                }
                found = safe(base.decodeNumSentence(start, v.value, limit));
                t9QueryCache.put(v.value, found);
                queries++;
                if (diagnostic) logQuery("T9_FUZZY " + v.diagnosticInfo(), start, v.value, found);
            } else if (diagnostic) {
                Log.d("SIME_FUZZY_QUERY", "T9_QUERY_REUSED " + v.diagnosticInfo());
            }
            collectMappedDigits(found, v, start.length(), prefixUnitsLength,
                    exactKeys, best, origins);
        }

        fuzzy.addAll(best.values());
        fuzzy.sort((a, b) -> Float.compare(b.score, a.score));
        DecodeResult[] merged = mergeExactAndFuzzy(exact, fuzzy, origins);
        if (diagnostic) {
            Log.d("SIME_FUZZY_QUERY", "MERGED fuzzyUnique=" + fuzzy.size()
                    + " finalCount=" + merged.length + " causeIndex=" + indexOf(merged, "原因")
                    + " cause=" + describeCause(merged));
        }
        return merged;
    }

    private interface VariantQuery {
        DecodeResult[] decode(FuzzyVariant variant);
    }

    private interface ConsumedReader {
        int get(DecodeResult result);
    }

    private DecodeResult[] appendFuzzy(DecodeResult[] exact, List<FuzzyVariant> variants,
                                       VariantQuery query, ConsumedReader consumedReader) {
        if (variants.isEmpty()) return exact;
        Set<String> exactKeys = keys(exact);
        Map<String, DecodeResult> best = new HashMap<>();
        Map<String, FuzzyOrigin> origins = new HashMap<>();
        for (FuzzyVariant variant : variants) {
            DecodeResult[] found = safe(query.decode(variant));
            for (DecodeResult r : found) {
                int mapped = variant.mapConsumed(consumedReader.get(r));
                addFuzzy(r, mapped, variant, 0, 0, true, exactKeys, best, origins);
            }
        }
        List<DecodeResult> fuzzy = new ArrayList<>(best.values());
        fuzzy.sort((a, b) -> Float.compare(b.score, a.score));
        return mergeExactAndFuzzy(exact, fuzzy, origins);
    }

    private static void collectMappedStart(DecodeResult[] found, FuzzyVariant variant,
                                           Set<String> exactKeys,
                                           Map<String, DecodeResult> best,
                                           Map<String, FuzzyOrigin> origins) {
        for (DecodeResult r : found) {
            addFuzzy(r, variant.mapConsumed(r.consumed), variant, 0, 0, true, exactKeys, best, origins);
        }
    }

    private static void collectMappedDigits(DecodeResult[] found, FuzzyVariant variant,
                                            int prefixLength, int prefixUnitsLength,
                                            Set<String> exactKeys,
                                            Map<String, DecodeResult> best,
                                            Map<String, FuzzyOrigin> origins) {
        for (DecodeResult r : found) {
            int mapped;
            if (r.consumed <= prefixLength) {
                mapped = r.consumed;
            } else {
                int mappedDigits = variant.mapConsumed(r.consumed - prefixLength);
                mapped = mappedDigits < 0 ? -1 : prefixLength + mappedDigits;
            }
            // T9 variants change digit lengths, but the native decoder
            // returns pinyin-letter units. Restore the original syllable
            // spelling there as well, offset past any fixed letter prefix.
            addFuzzy(r, mapped, variant, prefixUnitsLength, prefixLength,
                    true, exactKeys, best, origins);
        }
    }

    private static void addFuzzy(DecodeResult r, int mappedConsumed,
                                 FuzzyVariant variant, int unitsOffset, int consumedOffset,
                                 boolean restoreUnits,
                                 Set<String> exactKeys, Map<String, DecodeResult> best,
                                 Map<String, FuzzyOrigin> origins) {
        if (r == null || r.text == null || r.text.isEmpty() || mappedConsumed <= 0) return;
        String key = r.text + "\u0001" + mappedConsumed;
        if (exactKeys.contains(key)) return;
        if (restoreUnits && variant.coversChangedSyllable(mappedConsumed, consumedOffset)
                && !variant.matchesTargetUnits(r.units, unitsOffset)) {
            // A T9 query can be shared by distinct pinyin readings. Do not
            // attach a result to the wrong fuzzy mapping merely because its
            // digit query is identical.
            return;
        }
        String candidateUnits = restoreUnits
                ? variant.restoreUnits(r.units, unitsOffset) : r.units;
        DecodeResult candidate = new DecodeResult(r.text, candidateUnits, mappedConsumed,
                r.tokenIds, r.score - variant.penalty, 1, variant.penalty);
        DecodeResult old = best.get(key);
        if (old == null || candidate.score > old.score) {
            best.put(key, candidate);
            origins.put(key, new FuzzyOrigin(variant, consumedOffset));
        }
    }

    private static final class FuzzyOrigin {
        final FuzzyVariant variant;
        final int consumedOffset;
        FuzzyOrigin(FuzzyVariant variant, int consumedOffset) {
            this.variant = variant;
            this.consumedOffset = consumedOffset;
        }
    }

    /** Replace an exact candidate that ended inside the fuzzy syllable with
     * its full-input fuzzy counterpart, preserving its original list position. */
    private static DecodeResult[] mergeExactAndFuzzy(DecodeResult[] exact,
                                                       List<DecodeResult> fuzzy,
                                                       Map<String, FuzzyOrigin> origins) {
        if (fuzzy.isEmpty()) return exact;
        Map<String, DecodeResult> replacements = new HashMap<>();
        Set<String> used = new HashSet<>();
        for (DecodeResult f : fuzzy) {
            FuzzyOrigin origin = origins.get(f.text + "\u0001" + f.consumed);
            if (origin == null) continue;
            for (DecodeResult e : exact) {
                if (e.text.equals(f.text) && e.consumed < f.consumed
                        && origin.variant.consumesPartialSource(e.consumed, origin.consumedOffset)) {
                    replacements.put(e.text + "\u0001" + e.consumed, f);
                    used.add(f.text + "\u0001" + f.consumed);
                    break;
                }
            }
        }
        List<DecodeResult> out = new ArrayList<>(exact.length + fuzzy.size());
        for (DecodeResult e : exact) {
            DecodeResult replacement = replacements.get(e.text + "\u0001" + e.consumed);
            out.add(replacement == null ? e : replacement);
        }
        int appended = 0;
        for (DecodeResult f : fuzzy) {
            if (!used.contains(f.text + "\u0001" + f.consumed)) {
                if (appended >= MAX_FUZZY_RESULTS) break;
                out.add(f);
                appended++;
            }
        }
        return out.toArray(new DecodeResult[0]);
    }

    /** Diagnostic-only trace for long T9 inputs; does not affect decoding. */
    private static void logQuery(String stage, String start, String digits, DecodeResult[] results) {
        StringBuilder line = new StringBuilder(256);
        line.append(stage).append(" start=").append(start)
                .append(" digits=").append(digits)
                .append(" count=").append(results.length)
                .append(" causeIndex=").append(indexOf(results, "原因"));
        int shown = Math.min(results.length, 12);
        for (int i = 0; i < shown; i++) {
            DecodeResult r = results[i];
            if (r == null) continue;
            line.append(" | ").append(i).append(':').append(r.text)
                    .append('/').append(r.units).append('/').append(r.consumed);
        }
        Log.d("SIME_FUZZY_QUERY", line.toString());
    }

    private static int indexOf(DecodeResult[] results, String text) {
        for (int i = 0; i < results.length; i++) {
            DecodeResult r = results[i];
            if (r != null && text.equals(r.text)) return i;
        }
        return -1;
    }

    private static String describeCause(DecodeResult[] results) {
        int index = indexOf(results, "原因");
        if (index < 0) return "absent";
        DecodeResult r = results[index];
        return "index=" + index + ",units=" + r.units + ",consumed=" + r.consumed
                + ",fuzzy=" + r.fuzzyMatchCount + ",penalty=" + r.fuzzyPenalty;
    }

    private static Set<String> keys(DecodeResult[] results) {
        Set<String> out = new HashSet<>();
        for (DecodeResult r : results) {
            if (r != null && r.text != null) out.add(r.text + "\u0001" + r.consumed);
        }
        return out;
    }

    private static DecodeResult[] safe(DecodeResult[] results) {
        return results == null ? new DecodeResult[0] : results;
    }

    @Override public DecodeResult[] nextTokens(int[] contextIds, int limit, boolean enOnly) {
        return safe(base.nextTokens(contextIds, limit, enOnly));
    }
    @Override public DecodeResult[] getTokens(String prefix, int limit, boolean enOnly) {
        return safe(base.getTokens(prefix, limit, enOnly));
    }
    @Override public String[] t9PinyinSyllables(String digits, int limit) {
        String[] r = base.t9PinyinSyllables(digits, limit);
        return r == null ? new String[0] : r;
    }
    @Override public int contextSize() { return base.contextSize(); }
    @Override public void learnUserSentence(int[] context, int[] tokens) {
        base.learnUserSentence(context, tokens);
    }
    @Override public void flushUserSentence() { base.flushUserSentence(); }
    @Override public void onUserCommit(String text) { base.onUserCommit(text); }
    @Override public String bestNumUnits(String digits) { return base.bestNumUnits(digits); }
}
