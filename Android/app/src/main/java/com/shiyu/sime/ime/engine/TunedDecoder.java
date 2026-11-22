package com.shiyu.sime.ime.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * Decorates the native decoder with: frequency-based candidates for a lone
 * first T9 key, cleaner candidate lists (no abbreviations, no stitched
 * nonsense sentences, fewer long sentences), the lue / nue spelling fix and
 * the user's own ordering / blocking rules.
 */
public final class TunedDecoder implements Decoder {
    private static final String[] KEY_LETTERS = {
            "", "", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"};
    /** a / e / o are whole syllables; keep them below real initials. */
    private static final float SYLLABLE_KEY_PENALTY = 4.0f;
    /** Longest input for which first-syllable probing is done. */
    private static final int PROBE_MIN_DIGITS = 7;
    private static final int PROBE_MAX_DIGITS = 14;
    private static final int PROBE_MAX = 2;
    /**
     * Composed (multi-word) sentences below this average score per syllable
     * are almost always nonsense stitched from unrelated words
     * (e.g. 嗯将筹办); real phrases measured at -5.1 or better.
     */
    static final float WEAK_SENTENCE_PER_SYLLABLE = -5.3f;
    private static final int WEAK_MIN_SYLLABLES = 3;
    private static final int WEAK_MAX_SYLLABLES = 6;
    /** How many other candidates are shown before a demoted sentence. */
    private static final int WEAK_KEEP_AHEAD = 6;

    private final Decoder base;
    private final CandidateTuning tuning;

    public TunedDecoder(Decoder base, CandidateTuning tuning) {
        this.base = base;
        this.tuning = tuning;
    }

    public CandidateTuning tuning() { return tuning; }

    // ===== Full-keyboard pinyin =====

    @Override
    public DecodeResult[] decodeSentence(String pinyin, int limit) {
        try {
            return decodeSentenceTuned(pinyin, limit);
        } catch (RuntimeException e) {
            return engSentence(pinyin, limit); // never lose candidates
        }
    }

    private DecodeResult[] decodeSentenceTuned(String pinyin, int limit) {
        DecodeResult[] orig = engSentence(pinyin, limit);
        List<DecodeResult> extras = new ArrayList<>();
        return finish(limitLongSentences(restrictToTopSegmentation(
                dropNonsenseSentences(dropAbbreviations(dropBannedReadings(
                        merge(orig, extras)))))));
    }

    // ===== Nine-key =====

    @Override
    public DecodeResult[] decodeNumSentence(String startLetters, String digits, int limit) {
        try {
            return decodeNumTuned(startLetters, digits, limit);
        } catch (RuntimeException e) {
            return engNum(startLetters, digits, limit);
        }
    }

    private DecodeResult[] decodeNumTuned(String startLetters, String digits, int limit) {
        String start = startLetters == null ? "" : startLetters;
        if (start.isEmpty() && digits != null && digits.length() == 1
                && digits.charAt(0) >= '2' && digits.charAt(0) <= '9') {
            DecodeResult[] lone = loneKey(digits.charAt(0), limit);
            if (lone.length > 0) return finish(Arrays.asList(lone));
        }
        DecodeResult[] orig = engNum(start, digits, limit);
        List<DecodeResult> extras = new ArrayList<>();
        if (start.isEmpty() && digits != null) {
            addFirstSyllableProbes(extras, orig, digits, limit);
        }
        return finish(limitLongSentences(restrictToTopSegmentation(
                demoteWeakSentences(dropNonsenseSentences(dropAbbreviations(
                        dropBannedReadings(merge(orig, extras))))))));
    }

    /**
     * The nine-key engine searches ambiguous digits with a limited beam, so
     * a rarer four-character word can be missed (e.g. 7487449838 finds
     * 舒适祖父 but not 熟视无睹), yet it is found at once when the first
     * syllable is fixed, as if the user had tapped it in the syllable list.
     * Do that automatically for the most likely first syllable(s).
     */
    private void addFirstSyllableProbes(List<DecodeResult> extras, DecodeResult[] orig,
                                        String digits, int limit) {
        if (digits.length() < PROBE_MIN_DIGITS || digits.length() > PROBE_MAX_DIGITS
                || digits.indexOf('\'') >= 0) return;
        List<String> firsts = new ArrayList<>();
        for (DecodeResult r : orig) {
            if (firsts.size() >= PROBE_MAX) break;
            if (!isChinese(r)) continue;
            String first = r.units.split("'")[0];
            if (first.length() >= digits.length() || !PinyinSyllables.isSyllable(first)) continue;
            if (!firsts.contains(first)) firsts.add(first);
        }
        for (String first : firsts) {
            for (DecodeResult r : engNum(first,
                    digits.substring(first.length()), limit)) {
                if (r.consumed <= 0) continue;
                extras.add(r);
            }
        }
    }

    /** First keystroke on a T9 key: most frequent characters of all its letters. */
    private DecodeResult[] loneKey(char digit, int limit) {
        Map<String, DecodeResult> best = new HashMap<>();
        String letters = KEY_LETTERS[digit - '0'];
        for (int i = 0; i < letters.length(); i++) {
            char ch = letters.charAt(i);
            if (ch == 'i' || ch == 'u' || ch == 'v') continue;
            float penalty = (ch == 'a' || ch == 'e' || ch == 'o')
                    ? SYLLABLE_KEY_PENALTY : 0f;
            for (DecodeResult r : engSentence(String.valueOf(ch), limit)) {
                if (r.text == null || r.text.isEmpty() || isAscii(r.text)) continue;
                float score = r.score - penalty;
                DecodeResult old = best.get(r.text);
                if (old == null || old.score < score) {
                    best.put(r.text, new DecodeResult(r.text, String.valueOf(ch),
                            1, r.tokenIds, score));
                }
            }
        }
        List<DecodeResult> list = new ArrayList<>(best.values());
        Collections.sort(list, (a, b) -> Float.compare(b.score, a.score));
        return list.toArray(new DecodeResult[0]);
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7f) return false;
        return true;
    }

    // ===== Prediction / English completion: only hide blocked words =====

    @Override
    public DecodeResult[] nextTokens(int[] contextIds, int limit, boolean enOnly) {
        return dropBlocked(base.nextTokens(contextIds, limit, enOnly));
    }

    @Override
    public DecodeResult[] getTokens(String prefix, int limit, boolean enOnly) {
        return dropBlocked(base.getTokens(prefix, limit, enOnly));
    }

    private DecodeResult[] dropBlocked(DecodeResult[] in) {
        if (!tuning.hasRules()) return in;
        List<DecodeResult> out = new ArrayList<>(in.length);
        for (DecodeResult r : in) if (!tuning.isBlocked(r.text)) out.add(r);
        return out.toArray(new DecodeResult[0]);
    }

    private final java.util.LinkedHashMap<String, String> unitsCache =
            new java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> e) {
                    return size() > 128;
                }
            };

    @Override
    public String bestNumUnits(String digits) {
        if (digits == null || digits.isEmpty()) return "";
        String cached = unitsCache.get(digits);
        if (cached != null) return cached;
        String units;
        if (digits.length() == 1 && digits.charAt(0) >= '2' && digits.charAt(0) <= '9') {
            DecodeResult[] lone = loneKey(digits.charAt(0), 0);
            units = lone.length > 0 ? lone[0].units : "";
        } else {
            DecodeResult[] r = engNum("", digits, 0);
            units = (r.length > 0 && r[0].units != null) ? r[0].units : "";
        }
        unitsCache.put(digits, units);
        return units;
    }

    // ===== Pass-through =====

    @Override
    public String[] t9PinyinSyllables(String digits, int limit) {
        return aliasSyllablesOut(base.t9PinyinSyllables(digits, limit));
    }

    @Override public int contextSize() { return base.contextSize(); }

    @Override
    public void learnUserSentence(int[] context, int[] tokens) {
        base.learnUserSentence(context, tokens);
    }

    @Override public void flushUserSentence() { base.flushUserSentence(); }

    @Override public void onUserCommit(String text) { tuning.onCommit(text); }

    /**
     * Nine-key input is ambiguous, so when no real word fits, the engine
     * stitches several unrelated words into a "sentence" and puts it first.
     * Such weak full-length sentences are moved behind the first few
     * other candidates (still available, no longer the default).
     */
    static List<DecodeResult> demoteWeakSentences(List<DecodeResult> in) {
        int full = 0;
        for (DecodeResult r : in) full = Math.max(full, r.consumed);
        List<DecodeResult> keep = new ArrayList<>(in.size());
        List<DecodeResult> weak = new ArrayList<>();
        for (DecodeResult r : in) {
            if (isWeakSentence(r, full)) weak.add(r); else keep.add(r);
        }
        if (weak.isEmpty() || keep.isEmpty()) return in;
        keep.addAll(Math.min(WEAK_KEEP_AHEAD, keep.size()), weak);
        return keep;
    }

    private static boolean isWeakSentence(DecodeResult r, int full) {
        if (r.consumed != full || r.tokenIds == null || r.tokenIds.length < 2) return false;
        if (r.text == null || isAscii(r.text) || r.units == null) return false;
        int syl = r.units.split("'").length;
        if (syl < WEAK_MIN_SYLLABLES || syl > WEAK_MAX_SYLLABLES) return false;
        return r.score / syl < WEAK_SENTENCE_PER_SYLLABLE;
    }

    // ===== Nonsense whole-input sentences: do not show them at all =====

    /**
     * Composed sentences below this average score per syllable are never
     * real phrases (measured: real sentences score -3.4 or better, stitched
     * nonsense such as 嗯两次模板 scores -6.5; the limit was raised from
     * -6.0 to -5.5 to also catch borderline cases such as 佛教还目标 at -5.75). They are removed, not just
     * moved back.
     */
    static final float NONSENSE_PER_SYLLABLE = -5.5f;
    private static final int NONSENSE_MIN_SYLLABLES = 3;
    private static final int NONSENSE_MAX_SYLLABLES = 8;

    /**
     * Removes, among the candidates that cover the whole input:
     * <ol>
     *   <li>composed (2+ word) sentences that score far too low;</li>
     *   <li>"stretched" sentences that contain single-letter abbreviation
     *       pieces (e.g. ming'm'g'g'mi'shi -> 名美国公民是) when the best
     *       sentence is made of complete syllables only. When the user is
     *       really typing initials, the best sentence has such pieces itself
     *       and nothing is removed.</li>
     * </ol>
     * Words and single characters for part of the input are untouched, so
     * something sensible is always left to pick.
     */
    static List<DecodeResult> dropNonsenseSentences(List<DecodeResult> in) {
        int full = 0;
        for (DecodeResult r : in) if (isChinese(r)) full = Math.max(full, r.consumed);
        if (full <= 0) return in;
        List<DecodeResult> kept = new ArrayList<>(in.size());
        for (DecodeResult r : in) {
            if (isChinese(r) && r.consumed == full && isNonsenseSentence(r)) continue;
            kept.add(r);
        }
        if (kept.isEmpty()) return in;
        DecodeResult top = null;
        for (DecodeResult r : kept) {
            if (isChinese(r) && r.consumed == full) { top = r; break; }
        }
        if (top != null && !hasAbbreviationPiece(top)) {
            List<DecodeResult> kept2 = new ArrayList<>(kept.size());
            for (DecodeResult r : kept) {
                if (r != top && isChinese(r) && r.consumed == full
                        && hasAbbreviationPiece(r)) continue;
                kept2.add(r);
            }
            kept = kept2;
        }
        return kept;
    }

    private static boolean isNonsenseSentence(DecodeResult r) {
        if (r.tokenIds == null || r.tokenIds.length < 2) return false;
        int syl = r.units.split("'").length;
        if (syl < NONSENSE_MIN_SYLLABLES || syl > NONSENSE_MAX_SYLLABLES) return false;
        return r.score / syl < NONSENSE_PER_SYLLABLE;
    }

    /** True if any pinyin piece is not a complete syllable (an initial only). */
    private static boolean hasAbbreviationPiece(DecodeResult r) {
        for (String piece : r.units.split("'")) {
            if (!piece.isEmpty() && !PinyinSyllables.isSyllable(piece)) return true;
        }
        return false;
    }

    // ===== Long phrases / sentences: show fewer, more useful candidates =====

    /**
     * Beyond the very first candidate, extra whole-input sentences are
     * almost never picked, so they only get in the way. Rules, applied to
     * the candidates that cover the whole input:
     * <ul>
     *   <li>up to 3 syllables: unchanged;</li>
     *   <li>4 syllables (a four-character word): keep 2;</li>
     *   <li>5 or more syllables: keep 1.</li>
     * </ul>
     * The candidates that follow are those for the first word of the
     * sentence (the longest word that starts the best sentence goes
     * first), then shorter words and single characters; after the user
     * picks, the next word's candidates appear the same way.
     */
    static List<DecodeResult> limitLongSentences(List<DecodeResult> in) {
        int full = 0;
        for (DecodeResult r : in) if (isChinese(r)) full = Math.max(full, r.consumed);
        if (full <= 0) return in;
        // Whole-input candidates, real ones before stitched-together ones.
        List<DecodeResult> solid = new ArrayList<>();
        List<DecodeResult> weak = new ArrayList<>();
        for (DecodeResult r : in) {
            if (!isChinese(r) || r.consumed != full) continue;
            if (isWeakSentence(r, full)) weak.add(r); else solid.add(r);
        }
        DecodeResult top = !solid.isEmpty() ? solid.get(0)
                : (!weak.isEmpty() ? weak.get(0) : null);
        if (top == null) return in;
        int syllables = top.units.split("'").length;
        if (syllables <= 3) return in;
        final int keepFull = (syllables == 4) ? 2 : 1;

        Set<DecodeResult> keep = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<DecodeResult, Boolean>());
        for (DecodeResult r : solid) { if (keep.size() < keepFull) keep.add(r); }
        for (DecodeResult r : weak) { if (keep.size() < keepFull) keep.add(r); }

        List<DecodeResult> lead = new ArrayList<>();   // kept real sentences, first
        List<DecodeResult> rest = new ArrayList<>(in.size());
        for (DecodeResult r : in) {
            boolean wholeInput = isChinese(r) && r.consumed == full;
            if (wholeInput && !keep.contains(r)) continue;       // dropped
            if (wholeInput && !isWeakSentence(r, full)) lead.add(r);
            else rest.add(r);
        }
        List<DecodeResult> out = new ArrayList<>(lead);
        out.addAll(rest);

        // Find the best sentence's first-word boundary from the longest
        // candidate that is literally its prefix. Then promote the complete
        // candidate group at that same pinyin boundary, not just literal
        // prefixes. This is important for fuzzy alternatives such as
        // 原因 (yuan'ying) beside 原型 (yuan'xing): both consume the same
        // first two syllables even though their Hanzi differ.
        int firstWordConsumed = -1;
        int firstWordSyllables = -1;
        int bestPrefixLength = -1;
        for (DecodeResult r : out) {
            if (!isChinese(r) || r.consumed >= full || !top.text.startsWith(r.text)) continue;
            if (r.text.length() > bestPrefixLength) {
                bestPrefixLength = r.text.length();
                firstWordConsumed = r.consumed;
                firstWordSyllables = r.units.split("'").length;
            }
        }
        if (firstWordConsumed > 0) {
            List<DecodeResult> firstWordGroup = new ArrayList<>();
            List<DecodeResult> remainder = new ArrayList<>(out.size());
            for (DecodeResult r : out) {
                if (isChinese(r) && r.consumed == firstWordConsumed
                        && r.units.split("'").length == firstWordSyllables) {
                    firstWordGroup.add(r);
                } else if (!lead.contains(r)) {
                    remainder.add(r);
                }
            }
            // A fuzzy alternative can be buried behind many exact T9
            // readings of the same first-word span. Keep the two strongest
            // exact readings first, then surface fuzzy readings, while
            // preserving the relative score order within each partition.
            List<DecodeResult> exactFirstWords = new ArrayList<>();
            List<DecodeResult> fuzzyFirstWords = new ArrayList<>();
            for (DecodeResult r : firstWordGroup) {
                if (r.fuzzyMatchCount > 0) fuzzyFirstWords.add(r);
                else exactFirstWords.add(r);
            }
            out = new ArrayList<>(lead);
            if (fuzzyFirstWords.isEmpty()) {
                out.addAll(firstWordGroup);
            } else {
                int exactAhead = Math.min(2, exactFirstWords.size());
                out.addAll(exactFirstWords.subList(0, exactAhead));
                out.addAll(fuzzyFirstWords);
                out.addAll(exactFirstWords.subList(exactAhead, exactFirstWords.size()));
            }
            out.addAll(remainder);
        }
        return out;
    }

    private static boolean isChinese(DecodeResult r) {
        return r.text != null && !r.text.isEmpty() && !isAscii(r.text)
                && r.units != null && !r.units.isEmpty();
    }

    // ===== ü spellings =====
    // The dictionary spells the ü syllables with v (lve, nve), the standard
    // spelling is lue / nue. Typed lue / nue is therefore handed to the engine
    // as lve / nve (and shown back as lue / nue); without this the engine
    // can only split "lue" into lu'e (so 掠过 / 略过 never appear).

    private static boolean isPinyinLike(List<String> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            boolean last = (i == tokens.size() - 1);
            if (PinyinSyllables.isSyllable(t)) continue;
            if (last && PinyinSyllables.isSyllablePrefix(t)) continue;
            return false;
        }
        return !tokens.isEmpty();
    }

    /** Typed lue / nue -> dictionary lve / nve, only when the text is real pinyin. */
    static String aliasIn(String typed) {
        if (typed == null || (typed.indexOf("lue") < 0 && typed.indexOf("nue") < 0)) return typed;
        List<String> tokens = new ArrayList<>();
        List<String> seps = new ArrayList<>();
        PinyinSyllables.tokenize(typed, tokens, seps);
        if (!isPinyinLike(tokens)) return typed;      // e.g. the English word "blue"
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            sb.append(t.equals("lue") ? "lve" : t.equals("nue") ? "nve" : t).append(seps.get(i));
        }
        return sb.toString();
    }

    /** Dictionary lve / nve -> standard lue / nue for display and further input. */
    static String unitsOut(String units) {
        if (units == null || (units.indexOf("lve") < 0 && units.indexOf("nve") < 0)) return units;
        String[] pieces = units.split("'", -1);
        for (int i = 0; i < pieces.length; i++) {
            if (pieces[i].equals("lve")) pieces[i] = "lue";
            else if (pieces[i].equals("nve")) pieces[i] = "nue";
        }
        return String.join("'", pieces);
    }

    private static DecodeResult[] aliasResultsOut(DecodeResult[] in) {
        DecodeResult[] out = null;
        for (int i = 0; i < in.length; i++) {
            String u = unitsOut(in[i].units);
            if (u != in[i].units) {
                if (out == null) out = in.clone();
                DecodeResult r = in[i];
                out[i] = new DecodeResult(r.text, u, r.consumed, r.tokenIds, r.score,
                        r.fuzzyMatchCount, r.fuzzyPenalty);
            }
        }
        return out == null ? in : out;
    }

    private static String[] aliasSyllablesOut(String[] in) {
        if (in == null) return null;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (String s : in) seen.add(s.equals("lve") ? "lue" : s.equals("nve") ? "nue" : s);
        return seen.toArray(new String[0]);
    }

    private DecodeResult[] engSentence(String pinyin, int limit) {
        return aliasResultsOut(base.decodeSentence(aliasIn(pinyin), limit));
    }

    private DecodeResult[] engNum(String start, String digits, int limit) {
        return aliasResultsOut(base.decodeNumSentence(aliasIn(start), digits, limit));
    }

    // ===== Cleaner candidate lists =====

    /** Readings that must never be offered (text|pinyin). */
    private static final java.util.Set<String> BANNED_READINGS = new java.util.HashSet<>(
            java.util.Arrays.asList("便宜|bian'yi"));

    /** Drops banned (word, reading) pairs, e.g. 便宜 read as bian'yi. */
    static List<DecodeResult> dropBannedReadings(List<DecodeResult> in) {
        List<DecodeResult> out = null;
        for (int i = 0; i < in.size(); i++) {
            DecodeResult r = in.get(i);
            boolean banned = r.units != null
                    && BANNED_READINGS.contains(r.text + "|" + r.units.toLowerCase());
            if (banned && out == null) out = new ArrayList<>(in.subList(0, i));
            if (!banned && out != null) out.add(r);
        }
        return out == null ? in : out;
    }

    /**
     * No abbreviations (简拼): a Chinese candidate is kept only when every
     * pinyin piece except the last is a complete syllable, and the last is a
     * complete syllable or the beginning of one (the syllable still being
     * typed). So h'g'z'y -> 后顾之忧 and d'm'jiang -> 打麻将 never appear.
     */
    static List<DecodeResult> dropAbbreviations(List<DecodeResult> in) {
        List<DecodeResult> out = new ArrayList<>(in.size());
        for (DecodeResult r : in) {
            if (isChinese(r) && usesAbbreviation(r.units)) continue;
            out.add(r);
        }
        return out;
    }

    private static boolean usesAbbreviation(String units) {
        String[] pieces = units.split("'");
        for (int i = 0; i < pieces.length; i++) {
            boolean last = (i == pieces.length - 1);
            if (PinyinSyllables.isSyllable(pieces[i])) continue;
            if (last && PinyinSyllables.isSyllablePrefix(pieces[i])) continue;
            return true;
        }
        return false;
    }

    /**
     * With a clean best sentence of four or more syllables, the words offered
     * for its first part must follow its syllable boundaries; words that cut
     * the same keys differently (e.g. 余泱漪 for wu'wang'zhi'zai) are noise.
     */
    static List<DecodeResult> restrictToTopSegmentation(List<DecodeResult> in) {
        int full = 0;
        for (DecodeResult r : in) if (isChinese(r)) full = Math.max(full, r.consumed);
        if (full <= 0) return in;
        DecodeResult top = null;
        for (DecodeResult r : in) {
            if (isChinese(r) && r.consumed == full && !isWeakSentence(r, full)) { top = r; break; }
        }
        if (top == null) return in;
        String[] pieces = top.units.split("'");
        if (pieces.length < 4) return in;
        for (String p : pieces) {
            if (!PinyinSyllables.isSyllable(p)) return in;
        }
        java.util.Set<Integer> boundaries = new java.util.HashSet<>();
        int sum = 0;
        for (String p : pieces) { sum += p.length(); boundaries.add(sum); }
        if (sum != full) return in; // typed separators etc.: leave it alone
        List<DecodeResult> out = new ArrayList<>(in.size());
        for (DecodeResult r : in) {
            if (isChinese(r) && r.consumed < full && !boundaries.contains(r.consumed)) continue;
            out.add(r);
        }
        return out;
    }

    // ===== Merge =====

    private DecodeResult[] finish(List<DecodeResult> merged) {
        return tuning.apply(merged).toArray(new DecodeResult[0]);
    }

    /**
     * The best-scoring head (full-sentence result) of any source leads;
     * everything else follows ordered by engine score, de-duplicated.
     */
    static List<DecodeResult> merge(DecodeResult[] orig, List<DecodeResult> extras) {
        List<DecodeResult> origList = new ArrayList<>(Arrays.asList(orig));
        if (extras.isEmpty()) return origList;
        Map<String, DecodeResult> best = new HashMap<>();
        List<String> order = new ArrayList<>();
        List<DecodeResult> all = new ArrayList<>(origList);
        all.addAll(extras);
        DecodeResult head = null;
        for (DecodeResult r : all) {
            if (r.text == null || r.text.isEmpty()) continue;
            String key = r.text + "\u0001" + r.consumed;
            DecodeResult old = best.get(key);
            if (old == null) { best.put(key, r); order.add(key); }
            else if (r.score > old.score) best.put(key, r);
        }
        // Heads: first result of each source (orig first, then extras).
        List<DecodeResult> heads = new ArrayList<>();
        if (!origList.isEmpty()) heads.add(origList.get(0));
        for (DecodeResult e : extras) {
            if (e.consumed == fullLength(extras, e)) heads.add(e);
        }
        for (DecodeResult h : heads) {
            if (head == null || h.score > head.score) head = h;
        }
        List<DecodeResult> rest = new ArrayList<>();
        for (String k : order) rest.add(best.get(k));
        final DecodeResult lead = head;
        List<DecodeResult> out = new ArrayList<>(rest.size());
        if (lead != null) {
            DecodeResult chosen = best.get(lead.text + "\u0001" + lead.consumed);
            out.add(chosen != null ? chosen : lead);
            rest.remove(chosen != null ? chosen : lead);
        }
        // Stable sort keeps the engine's own order for equal scores.
        Collections.sort(rest, (a, b) -> Float.compare(b.score, a.score));
        out.addAll(rest);
        return out;
    }

    /** Longest consumed length among results (the full-input sentence). */
    private static int fullLength(List<DecodeResult> list, DecodeResult any) {
        int max = 0;
        for (DecodeResult r : list) max = Math.max(max, r.consumed);
        return max;
    }
}
