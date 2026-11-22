package com.shiyu.sime.ime.engine;

import com.shiyu.sime.ime.InputState;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LearnedSentenceStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void persistsSentenceAndIncreasesItsUseCount() throws Exception {
        File file = new File(temp.newFolder(), "learned_sentences.txt");
        LearnedSentenceStore store = new LearnedSentenceStore(file);
        store.learn("T9", "98269464", "yuan'yin", "原因是");
        store.learn("T9", "98269464", "yuan'yin", "原因是");

        LearnedSentenceStore reloaded = new LearnedSentenceStore(file);
        List<LearnedSentenceStore.Entry> found = reloaded.find("T9", "98269464");
        assertEquals(1, found.size());
        assertEquals("原因是", found.get(0).text);
        assertEquals("yuan'yin", found.get(0).pinyin);
        assertEquals(2, found.get(0).uses);
    }

    @Test public void keepsDifferentPinyinReadingsForTheSameT9CodeSeparate() throws Exception {
        File file = new File(temp.newFolder(), "learned_sentences.txt");
        LearnedSentenceStore store = new LearnedSentenceStore(file);
        store.learn("T9", "98269464", "yuan'ying", "原型");
        store.learn("T9", "98269464", "yuan'yin", "原因");

        List<LearnedSentenceStore.Entry> found = store.find("T9", "98269464");
        assertEquals(2, found.size());
        assertTrue(found.stream().anyMatch(e -> e.pinyin.equals("yuan'ying") && e.text.equals("原型")));
        assertTrue(found.stream().anyMatch(e -> e.pinyin.equals("yuan'yin") && e.text.equals("原因")));
    }

    @Test public void restoresOriginalT9DigitsAfterSeveralSyllablePicks() {
        InputState state = new InputState();
        state.buffer = "98269464";
        state.applyLetterPick("9826", "yuan", false);
        state.applyLetterPick("9464", "ying", false);
        state.select("原因", "yuan'yin", 7);
        state.select("是", "shi", 2);

        assertEquals("98269464", LearnedSentenceStore.originalInputCode(state));
        assertEquals("yuan'yin'shi", LearnedSentenceStore.selectedPinyin(state));
    }
}
