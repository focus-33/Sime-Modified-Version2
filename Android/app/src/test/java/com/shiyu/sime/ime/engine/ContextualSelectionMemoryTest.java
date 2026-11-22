package com.shiyu.sime.ime.engine;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ContextualSelectionMemoryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void exactLeftContextPromotesTheManuallySelectedCandidate() {
        ContextualSelectionMemory memory = new ContextualSelectionMemory(
                new File(temp.getRoot(), "context.txt"));
        memory.learn("ying", "应该", new int[] {11, 22});

        List<DecodeResult> ranked = memory.apply(Arrays.asList(
                candidate("应当", "ying", 1.0f),
                candidate("应该", "ying", 0.2f)), new int[] {11, 22});

        assertEquals("应该", ranked.get(0).text);
        assertTrue(ranked.get(0).score > 1.0f);
    }

    @Test public void unrelatedContextDoesNotOverrideDecoderRanking() {
        ContextualSelectionMemory memory = new ContextualSelectionMemory(
                new File(temp.getRoot(), "context.txt"));
        memory.learn("ying", "应该", new int[] {11, 22});

        List<DecodeResult> ranked = memory.apply(Arrays.asList(
                candidate("应当", "ying", 1.0f),
                candidate("应该", "ying", 0.2f)), new int[] {33, 44});

        assertEquals("应当", ranked.get(0).text);
    }

    @Test public void latestManualChoiceReplacesConflictingChoiceInSameContext() {
        ContextualSelectionMemory memory = new ContextualSelectionMemory(
                new File(temp.getRoot(), "context.txt"));
        memory.learn("ying", "应该", new int[] {11, 22});
        memory.learn("ying", "应当", new int[] {11, 22});

        List<DecodeResult> ranked = memory.apply(Arrays.asList(
                candidate("应该", "ying", 0.2f),
                candidate("应当", "ying", 0.2f)), new int[] {11, 22});

        assertEquals("应当", ranked.get(0).text);
        assertNotEquals("应该", ranked.get(0).text);
    }

    @Test public void learnedContextSurvivesReload() {
        File file = new File(temp.getRoot(), "context.txt");
        ContextualSelectionMemory first = new ContextualSelectionMemory(file);
        first.learn("yuan'ying", "原因", new int[] {101, 202});

        ContextualSelectionMemory reloaded = new ContextualSelectionMemory(file);
        List<DecodeResult> ranked = reloaded.apply(Arrays.asList(
                candidate("原型", "yuan'ying", 0.7f),
                candidate("原因", "yuan'ying", 0.1f)), new int[] {101, 202});

        assertEquals("原因", ranked.get(0).text);
    }


    @Test public void matchingRightContextPromotesLongerSentenceCandidate() {
        ContextualSelectionMemory memory = new ContextualSelectionMemory(
                new File(temp.getRoot(), "context.txt"));
        memory.learn("yuan'yin", "原因", new int[0],
                new int[] {100}, new int[] {200});

        List<DecodeResult> ranked = memory.apply(Arrays.asList(
                new DecodeResult("原型", "yuan'yin", 2, new int[] {300}, 0.9f),
                new DecodeResult("原因是", "yuan'yin'shi", 3,
                        new int[] {100, 200}, 0.1f)), new int[0]);

        assertEquals("原因是", ranked.get(0).text);
    }

    @Test public void rightContextIsPersisted() {
        File file = new File(temp.getRoot(), "context.txt");
        ContextualSelectionMemory first = new ContextualSelectionMemory(file);
        first.learn("yuan'yin", "原因", new int[0],
                new int[] {100}, new int[] {200});

        ContextualSelectionMemory reloaded = new ContextualSelectionMemory(file);
        List<DecodeResult> ranked = reloaded.apply(Arrays.asList(
                new DecodeResult("原型", "yuan'yin", 2, new int[] {300}, 0.9f),
                new DecodeResult("原因是", "yuan'yin'shi", 3,
                        new int[] {100, 200}, 0.1f)), new int[0]);

        assertEquals("原因是", ranked.get(0).text);
    }

    @Test public void globalPreferenceIsOnlyAWeakNudgeWithoutMatchingContext() {
        ContextualSelectionMemory memory = new ContextualSelectionMemory(
                new File(temp.getRoot(), "context.txt"));
        memory.learn("ying", "应该", new int[0]);

        List<DecodeResult> ranked = memory.apply(Arrays.asList(
                candidate("应当", "ying", 1.0f),
                candidate("应该", "ying", 0.9f)), new int[] {9, 8});

        assertEquals("应当", ranked.get(0).text);
    }

    private static DecodeResult candidate(String text, String units, float score) {
        return new DecodeResult(text, units, units.length(), new int[] {1}, score);
    }
}
