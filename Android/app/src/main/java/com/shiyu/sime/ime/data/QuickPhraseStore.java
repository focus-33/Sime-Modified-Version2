package com.shiyu.sime.ime.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent personal dictionary. Each entry stores the output phrase and
 * its user-defined full pinyin spelling. Legacy phrase-only entries remain
 * readable and can still be selected from the dictionary panel.
 */
public class QuickPhraseStore {
    private static final String PREFS_NAME = "sime_quick_phrases";
    private static final String KEY_COUNT = "count";
    private static final String KEY_PREFIX = "phrase_";
    private static final String KEY_PINYIN_PREFIX = "pinyin_";

    public static final class Entry {
        public final String text;
        public final String pinyin;

        public Entry(String text, String pinyin) {
            this.text = text == null ? "" : text;
            this.pinyin = normalizePinyin(pinyin);
        }
    }

    private final SharedPreferences prefs;

    public QuickPhraseStore(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<Entry> getEntries() {
        int count = prefs.getInt(KEY_COUNT, 0);
        List<Entry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String text = prefs.getString(KEY_PREFIX + i, null);
            if (text != null) {
                list.add(new Entry(text, prefs.getString(KEY_PINYIN_PREFIX + i, "")));
            }
        }
        return list;
    }

    /** Compatibility accessor for callers that only need displayed text. */
    public List<String> getAll() {
        List<String> list = new ArrayList<>();
        for (Entry entry : getEntries()) list.add(entry.text);
        return list;
    }

    public void add(String text) { add(text, ""); }

    public void add(String text, String pinyin) {
        List<Entry> all = getEntries();
        all.add(new Entry(text, pinyin));
        save(all);
    }

    public void updateAt(int index, String text) {
        List<Entry> all = getEntries();
        if (index >= 0 && index < all.size()) {
            Entry old = all.get(index);
            all.set(index, new Entry(text, old.pinyin));
            save(all);
        }
    }

    public void updateAt(int index, String text, String pinyin) {
        List<Entry> all = getEntries();
        if (index >= 0 && index < all.size()) {
            all.set(index, new Entry(text, pinyin));
            save(all);
        }
    }

    public void removeAt(int index) {
        List<Entry> all = getEntries();
        if (index >= 0 && index < all.size()) {
            all.remove(index);
            save(all);
        }
    }

    private void save(List<Entry> list) {
        SharedPreferences.Editor ed = prefs.edit().clear();
        ed.putInt(KEY_COUNT, list.size());
        for (int i = 0; i < list.size(); i++) {
            ed.putString(KEY_PREFIX + i, list.get(i).text);
            ed.putString(KEY_PINYIN_PREFIX + i, list.get(i).pinyin);
        }
        ed.apply();
    }

    public static String normalizePinyin(String pinyin) {
        if (pinyin == null) return "";
        return pinyin.toLowerCase(java.util.Locale.ROOT)
                .replace("'", "").replace(" ", "").trim();
    }
}
