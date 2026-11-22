package com.shiyu.sime.ime.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TunedDecoderPolicyTest {

    private static DecodeResult r(String text, String units, int consumed, float score) {
        return new DecodeResult(text, units, consumed, new int[] {1}, score);
    }

    private static List<String> texts(List<DecodeResult> list) {
        List<String> out = new ArrayList<>();
        for (DecodeResult c : list) out.add(c.text);
        return out;
    }

    @Test
    public void longSentenceKeepsOneWholeCandidateThenFirstWord() {
        List<DecodeResult> in = Arrays.asList(
                r("我要去火车站", "wo'yao'qu'huo'che'zhan", 17, -20f),
                r("我先去火车站", "wo'xian'qu'huo'che'zhan", 17, -21f),
                r("我要求火车站", "wo'yao'qiu'huo'che'zhan", 17, -22f),
                r("我", "wo", 2, -8f),
                r("我要", "wo'yao", 5, -10f),
                r("我要去", "wo'yao'qu", 7, -12f));

        assertEquals(Arrays.asList("我要去火车站", "我要去", "我", "我要"),
                texts(TunedDecoder.limitLongSentences(in)));
    }

    @Test
    public void fuzzyFirstWordAlternativesShareTheBestSentenceBoundary() {
        List<DecodeResult> in = Arrays.asList(
                r("原型是行为", "yuan'xing'shi'xing'wei", 18, -20f),
                r("原型", "yuan'xing", 8, -8f),
                r("远行", "yuan'xing", 8, -9f),
                r("元", "yuan", 4, -7f),
                r("试验", "shi'yan", 7, -10f),
                r("行为", "xing'wei", 10, -11f),
                r("因为", "yin'wei", 10, -12f),
                new DecodeResult("原因", "yuan'ying", 8, new int[] {1},
                        -13f, 1, 1.2f));

        List<String> result = texts(TunedDecoder.limitLongSentences(in));
        assertEquals(Arrays.asList("原型是行为", "原型", "远行", "原因", "元", "试验", "行为", "因为"),
                result);
    }

    @Test
    public void fuzzyFirstWordAlternativeIsNotBuriedBehindManyExactT9Readings() {
        List<DecodeResult> in = Arrays.asList(
                r("原型是行为", "yuan'xing'shi'xing'wei", 18, -20f),
                r("原型", "yuan'xing", 8, -8f),
                r("远行", "yuan'xing", 8, -9f),
                r("圆形", "yuan'xing", 8, -9.5f),
                r("原形", "yuan'xing", 8, -10f),
                r("圆型", "yuan'xing", 8, -10.5f),
                r("元形", "yuan'xing", 8, -11f),
                r("援行", "yuan'xing", 8, -11.5f),
                new DecodeResult("原因", "yuan'ying", 8, new int[] {1}, -13f, 1, 1.25f),
                r("元", "yuan", 4, -7f));

        List<String> result = texts(TunedDecoder.limitLongSentences(in));
        assertEquals("原型是行为", result.get(0));
        assertEquals("原型", result.get(1));
        assertEquals("远行", result.get(2));
        assertEquals("原因", result.get(3));
    }

    @Test
    public void fourSyllableInputKeepsTwoWholeCandidates() {
        List<DecodeResult> in = Arrays.asList(
                r("一心一意", "yi'xin'yi'yi", 9, -12f),
                r("一心只想", "yi'xin'zhi'xiang", 9, -13f),
                r("一阴一阳", "yi'yin'yi'yang", 9, -14f),
                r("一心", "yi'xin", 5, -9f));

        assertEquals(Arrays.asList("一心一意", "一心只想", "一心"),
                texts(TunedDecoder.limitLongSentences(in)));
    }

    @Test
    public void shortInputIsUnchanged() {
        List<DecodeResult> in = Arrays.asList(
                r("你好", "ni'hao", 5, -8f),
                r("你号", "ni'hao", 5, -9f),
                r("尼", "ni", 2, -10f));

        assertEquals(texts(in), texts(TunedDecoder.limitLongSentences(in)));
    }

    @Test
    public void stretchedAbbreviationSentenceIsRemovedWhenBestIsFullSyllables() {
        List<DecodeResult> in = Arrays.asList(
                new DecodeResult("明明是", "ming'ming'shi", 11, new int[] {1}, -12f),
                new DecodeResult("名美国公民是", "ming'm'g'g'mi'shi", 11, new int[] {1, 2, 3}, -28f),
                new DecodeResult("命名", "ming'ming", 8, new int[] {1}, -25f));

        assertEquals(Arrays.asList("明明是", "命名"),
                texts(TunedDecoder.dropNonsenseSentences(in)));
    }

    @Test
    public void lowScoringComposedSentenceIsRemoved() {
        List<DecodeResult> in = Arrays.asList(
                new DecodeResult("嗯两次模板", "en'liang'ci'mu'ban", 14, new int[] {1, 2, 3}, -33f),
                new DecodeResult("恩", "en", 2, new int[] {1}, -85f));

        assertEquals(Arrays.asList("恩"), texts(TunedDecoder.dropNonsenseSentences(in)));
    }

    @Test
    public void initialsTypedOnPurposeAreKept() {
        List<DecodeResult> in = Arrays.asList(
                new DecodeResult("你好世界", "n'h'sh'j", 4, new int[] {1, 2}, -15f),
                new DecodeResult("你会收集", "n'h'sh'j", 4, new int[] {1, 2}, -17f));

        assertEquals(texts(in), texts(TunedDecoder.dropNonsenseSentences(in)));
    }

    @Test
    public void borderlineStitchedSentenceBelowMinusFiveAndAHalfIsRemoved() {
        // 5 syllables at -28.8 is -5.76 per syllable.
        List<DecodeResult> in = Arrays.asList(
                new DecodeResult("恩将仇报", "en'jiang'chou'bao", 14, new int[] {1}, -16.3f),
                new DecodeResult("佛教还目标", "fo'jiao'hai'mu'biao", 14, new int[] {1, 2, 3}, -28.8f));

        assertEquals(Arrays.asList("恩将仇报"),
                texts(TunedDecoder.dropNonsenseSentences(in)));
    }

    @Test
    public void abbreviationCandidatesAreDropped() {
        List<DecodeResult> in = Arrays.asList(
                r("后顾之忧", "h'g'z'y", 4, -20f),
                r("打麻将", "d'm'jiang", 7, -15f),
                r("你好", "ni'hao", 5, -8f),
                r("书", "shu", 3, -9f),
                r("数", "sh", 2, -10f));      // last piece may be an unfinished syllable

        assertEquals(Arrays.asList("你好", "书", "数"),
                texts(TunedDecoder.dropAbbreviations(in)));
    }

    @Test
    public void bannedReadingIsDropped() {
        List<DecodeResult> in = Arrays.asList(
                r("便宜", "bian'yi", 6, -10f),
                r("便宜", "pian'yi", 6, -10f));

        List<DecodeResult> out = TunedDecoder.dropBannedReadings(in);
        assertEquals(1, out.size());
        assertEquals("pian'yi", out.get(0).units);
    }

    @Test
    public void wordsThatCutTheKeysDifferentlyAreRemovedForFourSyllableInput() {
        List<DecodeResult> in = Arrays.asList(
                r("无妄之灾", "wu'wang'zhi'zai", 12, -14f),
                r("无妄", "wu'wang", 6, -9f),
                r("余泱漪", "yu'yang'yi", 8, -12f),   // 8 is not a syllable boundary of the best sentence
                r("无", "wu", 2, -8f));

        assertEquals(Arrays.asList("无妄之灾", "无妄", "无"),
                texts(TunedDecoder.restrictToTopSegmentation(in)));
    }

    @Test
    public void typedLueIsHandedToTheDictionaryAsLve() {
        assertEquals("lve'guo", TunedDecoder.aliasIn("lue'guo"));
        assertEquals("nvehai", TunedDecoder.aliasIn("nuehai"));
        // not pinyin: leave English words alone
        assertEquals("blue", TunedDecoder.aliasIn("blue"));
        assertEquals("value", TunedDecoder.aliasIn("value"));
    }

    @Test
    public void dictionarySpellingIsShownAsLue() {
        assertEquals("lue'guo", TunedDecoder.unitsOut("lve'guo"));
        assertEquals("nue", TunedDecoder.unitsOut("nve"));
    }
}
