package com.shiyu.sime.ime.engine;

/**
 * Strongly-typed decode result returned by the native engine.
 * Mirrors {@code sime::DecodeResult} in {@code include/sime.h}.
 *
 * Fields:
 *   text     — UTF-8 hanzi string
 *   units    — segmented pinyin (e.g. "ni'hao")
 *   consumed — bytes of input consumed (the C++ {@code cnt} field)
 *   tokenIds — token IDs for LM context (used by NextTokens prediction)
 */
public final class DecodeResult {
    public final String text;
    public final String units;
    public final int consumed;
    public final int[] tokenIds;
    /** Engine score, larger is better (log-probability scale). */
    public final float score;
    /** Number of fuzzy-pinyin substitutions used to retrieve this candidate. */
    public final int fuzzyMatchCount;
    /** Total score penalty applied by the fuzzy-pinyin layer. */
    public final float fuzzyPenalty;

    public DecodeResult(String text, String units, int consumed) {
        this(text, units, consumed, EMPTY_IDS);
    }

    public DecodeResult(String text, String units, int consumed, int[] tokenIds) {
        this(text, units, consumed, tokenIds, 0f, 0, 0f);
    }

    public DecodeResult(String text, String units, int consumed, int[] tokenIds,
                        float score) {
        this(text, units, consumed, tokenIds, score, 0, 0f);
    }

    public DecodeResult(String text, String units, int consumed, int[] tokenIds,
                        float score, int fuzzyMatchCount, float fuzzyPenalty) {
        this.score = score;
        this.text = text;
        this.units = units;
        this.consumed = consumed;
        this.tokenIds = tokenIds;
        this.fuzzyMatchCount = fuzzyMatchCount;
        this.fuzzyPenalty = fuzzyPenalty;
    }

    private static final int[] EMPTY_IDS = new int[0];
}
