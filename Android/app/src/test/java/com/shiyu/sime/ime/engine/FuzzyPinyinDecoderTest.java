package com.shiyu.sime.ime.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FuzzyPinyinDecoderTest {

    @Test
    public void t9FuzzyCandidatesRestoreTheMatchingOriginalPinyinUnits() {
        final int[] yinQueryCount = {0};
        Decoder stub = new Decoder() {
            @Override public DecodeResult[] decodeSentence(String pinyin, int limit) {
                return new DecodeResult[0];
            }
            @Override public DecodeResult[] decodeNumSentence(String start, String digits, int limit) {
                if ("946".equals(digits)) {
                    yinQueryCount[0]++;
                    return new DecodeResult[] {
                            new DecodeResult("因", "yin", 3, new int[] {1}, -1f),
                            new DecodeResult("新", "xin", 3, new int[] {2}, -1.1f)
                    };
                }
                return new DecodeResult[0];
            }
            @Override public DecodeResult[] nextTokens(int[] context, int limit, boolean enOnly) {
                return new DecodeResult[0];
            }
            @Override public DecodeResult[] getTokens(String prefix, int limit, boolean enOnly) {
                return new DecodeResult[0];
            }
            @Override public String[] t9PinyinSyllables(String digits, int limit) {
                return new String[0];
            }
            @Override public int contextSize() { return 2; }
        };

        DecodeResult[] results = new FuzzyPinyinDecoder(stub)
                .decodeNumSentence("", "9464", 10);
        DecodeResult yin = find(results, "因");
        DecodeResult xin = find(results, "新");
        assertTrue("fuzzy T9 candidate for yin should be returned", yin != null);
        assertTrue("fuzzy T9 candidate for xin should be returned", xin != null);
        assertEquals("ying", yin.units);
        assertEquals("xing", xin.units);
        assertEquals(4, yin.consumed);
        assertEquals(4, xin.consumed);
        assertEquals("duplicate T9 mappings should share one native query",
                1, yinQueryCount[0]);
    }


    @Test
    public void remainingShiYingWeiDoesNotLeaveAnInventedTrailingLetter() {
        final int[] queryCount = {0};
        Decoder stub = new Decoder() {
            @Override public DecodeResult[] decodeSentence(String pinyin, int limit) {
                return new DecodeResult[0];
            }
            @Override public DecodeResult[] decodeNumSentence(String start, String digits, int limit) {
                if ("".equals(start) && "744946934".equals(digits)) {
                    queryCount[0]++;
                    return new DecodeResult[] {
                            new DecodeResult("是因为", "shi'yin'wei", 9, new int[] {1}, -1f)
                    };
                }
                return new DecodeResult[0];
            }
            @Override public DecodeResult[] nextTokens(int[] context, int limit, boolean enOnly) {
                return new DecodeResult[0];
            }
            @Override public DecodeResult[] getTokens(String prefix, int limit, boolean enOnly) {
                return new DecodeResult[0];
            }
            @Override public String[] t9PinyinSyllables(String digits, int limit) {
                return new String[0];
            }
            @Override public int contextSize() { return 2; }
        };

        DecodeResult[] results = new FuzzyPinyinDecoder(stub)
                .decodeNumSentence("", "7449464934", 10);
        DecodeResult match = find(results, "是因为");
        assertTrue("fuzzy 是因为 candidate should be returned", match != null);
        assertEquals("shi'ying'wei", match.units);
        assertEquals(10, match.consumed);
        assertEquals(1, queryCount[0]);
    }

    @Test
    public void t9FuzzyUnitsUseLetterCountNotRawPrefixLength() {
        Decoder stub = new Decoder() {
            @Override public DecodeResult[] decodeSentence(String pinyin, int limit) {
                return new DecodeResult[0];
            }
            @Override public DecodeResult[] decodeNumSentence(String start, String digits, int limit) {
                if ("shi'".equals(start) && "946".equals(digits)) {
                    return new DecodeResult[] {
                            new DecodeResult("因", "shi'yin", 7, new int[] {1}, -1f)
                    };
                }
                return new DecodeResult[0];
            }
            @Override public DecodeResult[] nextTokens(int[] context, int limit, boolean enOnly) {
                return new DecodeResult[0];
            }
            @Override public DecodeResult[] getTokens(String prefix, int limit, boolean enOnly) {
                return new DecodeResult[0];
            }
            @Override public String[] t9PinyinSyllables(String digits, int limit) {
                return new String[0];
            }
            @Override public int contextSize() { return 2; }
        };

        DecodeResult[] results = new FuzzyPinyinDecoder(stub)
                .decodeNumSentence("shi'", "9464", 10);
        DecodeResult match = find(results, "因");
        assertTrue("prefixed fuzzy T9 candidate should be returned", match != null);
        assertEquals("shi'ying", match.units);
        assertEquals(8, match.consumed);
    }

    @Test
    public void fullKeyboardFuzzyCandidateStillRestoresOriginalUnits() {
        Decoder stub = new Decoder() {
            @Override public DecodeResult[] decodeSentence(String pinyin, int limit) {
                if ("yuanyin".equals(pinyin)) {
                    return new DecodeResult[] {
                            new DecodeResult("原因", "yuan'yin", 7, new int[] {1}, -1f)
                    };
                }
                return new DecodeResult[0];
            }
            @Override public DecodeResult[] decodeNumSentence(String start, String digits, int limit) {
                return new DecodeResult[0];
            }
            @Override public DecodeResult[] nextTokens(int[] context, int limit, boolean enOnly) {
                return new DecodeResult[0];
            }
            @Override public DecodeResult[] getTokens(String prefix, int limit, boolean enOnly) {
                return new DecodeResult[0];
            }
            @Override public String[] t9PinyinSyllables(String digits, int limit) {
                return new String[0];
            }
            @Override public int contextSize() { return 2; }
        };

        DecodeResult[] results = new FuzzyPinyinDecoder(stub)
                .decodeSentence("yuanying", 10);
        DecodeResult match = find(results, "原因");
        assertTrue("full-keyboard fuzzy candidate should be returned", match != null);
        assertEquals("yuan'ying", match.units);
        assertEquals(8, match.consumed);
    }

    private static DecodeResult find(DecodeResult[] results, String text) {
        for (DecodeResult result : results) {
            if (text.equals(result.text)) return result;
        }
        return null;
    }
}
