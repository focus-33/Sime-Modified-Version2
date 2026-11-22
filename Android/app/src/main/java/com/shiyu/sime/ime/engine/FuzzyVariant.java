package com.shiyu.sime.ime.engine;

/** One single-substitution variant and its offset mapping back to typed input. */
public final class FuzzyVariant {
    public final String value;
    public final String ruleId;
    public final float penalty;
    private final int sourceStart;
    private final int sourceEnd;
    private final int targetEnd;
    private final String sourceSyllable;
    private final String targetSyllable;
    private final int sourceStartUnits;
    private final int targetEndUnits;

    FuzzyVariant(String original, int sourceStart, int sourceEnd,
                 String replacement, String sourcePinyinSyllable,
                 String targetPinyinSyllable, String ruleId, float penalty) {
        this.value = original.substring(0, sourceStart) + replacement
                + original.substring(sourceEnd);
        this.sourceStart = sourceStart;
        this.sourceEnd = sourceEnd;
        this.targetEnd = sourceStart + replacement.length();
        // For T9 variants, original/replacement contain digits, but decoder
        // units contain pinyin letters. Keep the pinyin spellings separately
        // so restoring units never inserts T9 digits into the preedit.
        this.sourceSyllable = sourcePinyinSyllable;
        this.targetSyllable = targetPinyinSyllable;
        this.sourceStartUnits = original.substring(0, sourceStart).replace("\'", "").length();
        this.targetEndUnits = sourceStartUnits + targetPinyinSyllable.length();
        this.ruleId = ruleId;
        this.penalty = penalty;
    }

    /** Compact source/target description used by opt-in runtime diagnostics. */
    String diagnosticInfo() {
        return ruleId + " " + sourceSyllable + "->" + targetSyllable
                + " sourceRange=" + sourceStart + ".." + sourceEnd
                + " query=" + value;
    }

    /** True when an exact result stops inside the original syllable being fuzzy-matched. */
    public boolean consumesPartialSource(int originalConsumed) {
        return consumesPartialSource(originalConsumed, 0);
    }

    public boolean consumesPartialSource(int originalConsumed, int offset) {
        int relative = originalConsumed - offset;
        return relative > sourceStart && relative < sourceEnd;
    }

    boolean coversChangedSyllable(int mappedConsumed, int offset) {
        return mappedConsumed >= sourceEnd + offset;
    }

    /** True when decoder units contain this variant's target syllable at its exact offset. */
    boolean matchesTargetUnits(String units, int offset) {
        if (units == null || units.isEmpty()) return false;
        int wantedStart = sourceStartUnits + offset;
        int flatIndex = 0;
        int matched = 0;
        for (int i = 0; i < units.length(); i++) {
            char c = units.charAt(i);
            if (c == '\'') continue;
            if (flatIndex >= wantedStart && matched < targetSyllable.length()) {
                if (c != targetSyllable.charAt(matched)) return false;
                matched++;
                if (matched == targetSyllable.length()) return true;
            }
            flatIndex++;
        }
        return false;
    }

    /**
     * Restores the original spelling in a decoder's segmented units while
     * preserving apostrophe boundaries. The decoder may return units in the
     * transformed spelling (for example yuan'yin); UI/state must retain the
     * user's actual input spelling (yuan'ying).
     */
    public String restoreUnits(String units) {
        return restoreUnits(units, 0);
    }

    public String restoreUnits(String units, int offset) {
        if (units == null || units.isEmpty() || !matchesTargetUnits(units, offset)) return units;
        StringBuilder out = new StringBuilder(units.length() + 2);
        int flatIndex = 0;
        boolean restored = false;
        int skipUntil = targetEndUnits + offset;
        for (int i = 0; i < units.length(); i++) {
            char c = units.charAt(i);
            if (c == '\'') {
                out.append(c);
                continue;
            }
            if (!restored && flatIndex == sourceStartUnits + offset) {
                out.append(sourceSyllable);
                restored = true;
            }
            if (restored && flatIndex < skipUntil) {
                flatIndex++;
                continue;
            }
            out.append(c);
            flatIndex++;
        }
        return restored ? out.toString() : units;
    }

    /**
     * Maps a consumed offset in the variant back to the user's original input.
     * Offsets inside the substituted syllable are rejected: a partial match of
     * a transformed syllable must not be reported as a valid original prefix.
     */
    public int mapConsumed(int variantConsumed) {
        if (variantConsumed < 0) return -1;
        if (variantConsumed <= sourceStart) return variantConsumed;
        if (variantConsumed < targetEnd) return -1;
        if (variantConsumed == targetEnd) return sourceEnd;
        return variantConsumed + (sourceEnd - targetEnd);
    }
}
