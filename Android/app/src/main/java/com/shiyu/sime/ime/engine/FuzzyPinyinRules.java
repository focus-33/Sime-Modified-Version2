package com.shiyu.sime.ime.engine;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Versioned, immutable rules for the first SFPE release. */
public final class FuzzyPinyinRules {
    public static final int VERSION = 1;
    public static final float DEFAULT_PENALTY = 1.25f;

    public static final class Rule {
        public final String id;
        public final String from;
        public final String to;
        public final float penalty;

        private Rule(String id, String from, String to, float penalty) {
            this.id = id;
            this.from = from;
            this.to = to;
            this.penalty = penalty;
        }
    }

    private static final List<Rule> RULES = Collections.unmodifiableList(Arrays.asList(
            new Rule("en_to_eng", "en", "eng", DEFAULT_PENALTY),
            new Rule("eng_to_en", "eng", "en", DEFAULT_PENALTY),
            new Rule("in_to_ing", "in", "ing", DEFAULT_PENALTY),
            new Rule("ing_to_in", "ing", "in", DEFAULT_PENALTY)
    ));

    private FuzzyPinyinRules() {}

    public static List<Rule> all() { return RULES; }
}
