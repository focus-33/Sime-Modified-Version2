package com.shiyu.sime.ime.engine;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * User-defined candidate ordering and blocking. Rules are applied after
 * the engine has ranked candidates, so engine word-frequency (or its
 * user-history learning) can never override them.
 *
 * <p>Rules, applied in the order they were made (later wins):
 * <ul>
 *   <li>P a b - a must appear before b (升序 / 降序)</li>
 *   <li>F a   - a goes to the first position (移动到首位)</li>
 *   <li>L a   - a goes to the end of its class, i.e. after the last
 *       candidate with the same length (移动到末尾)</li>
 *   <li>B text - text is hidden until the user types it out manually</li>
 * </ul>
 */
public final class CandidateTuning {
    private static final int MAX_OPS = 400;

    private static volatile CandidateTuning shared;

    /** Instance the service uses; lets the settings panel reset it. */
    public static CandidateTuning shared() { return shared; }
    public static void setShared(CandidateTuning t) { shared = t; }

    private final File file;
    private final List<String[]> ops = new ArrayList<>();
    private final Set<String> blocked = new LinkedHashSet<>();

    public CandidateTuning(File file) {
        this.file = file;
        load();
    }

    /** Identity of a candidate: pronunciation + text. */
    public static String idOf(DecodeResult c) {
        String u = c.units == null ? "" : c.units.toLowerCase();
        return u + "|" + c.text;
    }

    public synchronized boolean hasRules() {
        return !ops.isEmpty() || !blocked.isEmpty();
    }

    public synchronized boolean isBlocked(String text) {
        return blocked.contains(text);
    }

    // ===== Apply =====

    public synchronized List<DecodeResult> apply(List<DecodeResult> in) {
        if (!hasRules()) return in;
        List<DecodeResult> list = new ArrayList<>(in.size());
        for (DecodeResult c : in) if (!blocked.contains(c.text)) list.add(c);
        for (String[] op : ops) {
            switch (op[0]) {
                case "P": applyPrefer(list, op[1], op[2]); break;
                case "F": applyFirst(list, op[1]); break;
                case "L": applyLast(list, op[1]); break;
                default: break;
            }
        }
        return list;
    }

    private static int indexOf(List<DecodeResult> l, String id) {
        for (int i = 0; i < l.size(); i++) if (idOf(l.get(i)).equals(id)) return i;
        return -1;
    }

    private static void applyPrefer(List<DecodeResult> l, String a, String b) {
        int ia = indexOf(l, a), ib = indexOf(l, b);
        if (ia < 0 || ib < 0 || ia < ib) return;
        l.add(ib, l.remove(ia));
    }

    private static void applyFirst(List<DecodeResult> l, String a) {
        int ia = indexOf(l, a);
        if (ia > 0) l.add(0, l.remove(ia));
    }

    private static int classOf(String text) {
        boolean ascii = true;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7f) { ascii = false; break; }
        }
        if (ascii) return -1; // English words form their own class
        return text.codePointCount(0, text.length());
    }

    private static void applyLast(List<DecodeResult> l, String a) {
        int ia = indexOf(l, a);
        if (ia < 0) return;
        DecodeResult c = l.remove(ia);
        int cls = classOf(c.text);
        int last = -1;
        for (int i = 0; i < l.size(); i++) {
            if (classOf(l.get(i).text) == cls) last = i;
        }
        l.add(last < 0 ? Math.min(ia, l.size()) : last + 1, c);
    }

    // ===== Edits (all take the list the user is currently looking at) =====

    /** 升序: move the candidate one place forward. */
    public synchronized void moveUp(List<DecodeResult> view, int idx) {
        if (idx <= 0 || idx >= view.size()) return;
        addOp("P", idOf(view.get(idx)), idOf(view.get(idx - 1)));
    }

    /** 降序: move the candidate one place back. */
    public synchronized void moveDown(List<DecodeResult> view, int idx) {
        if (idx < 0 || idx >= view.size() - 1) return;
        addOp("P", idOf(view.get(idx + 1)), idOf(view.get(idx)));
    }

    /** 移动到首位. */
    public synchronized void moveToFirst(List<DecodeResult> view, int idx) {
        if (idx < 0 || idx >= view.size()) return;
        addOp("F", idOf(view.get(idx)), "");
    }

    /** 移动到末尾 (of its class, not of the whole list). */
    public synchronized void moveToLast(List<DecodeResult> view, int idx) {
        if (idx < 0 || idx >= view.size()) return;
        addOp("L", idOf(view.get(idx)), "");
    }

    /** 屏蔽. */
    public synchronized void block(String text) {
        if (text == null || text.isEmpty()) return;
        if (blocked.add(text)) save();
    }

    /**
     * Called with every committed string. A blocked word the user types out
     * by hand (it can no longer be picked) is unblocked.
     */
    public synchronized void onCommit(String text) {
        if (text != null && blocked.remove(text)) save();
    }

    public synchronized void clear() {
        ops.clear();
        blocked.clear();
        save();
    }

    private void addOp(String type, String a, String b) {
        for (int i = ops.size() - 1; i >= 0; i--) {
            String[] o = ops.get(i);
            boolean same = o[0].equals(type) && o[1].equals(a) && o[2].equals(b);
            boolean reverse = type.equals("P") && o[0].equals("P")
                    && o[1].equals(b) && o[2].equals(a);
            boolean replaced = !type.equals("P") && !o[0].equals("P")
                    && o[1].equals(a);
            if (same || reverse || replaced) ops.remove(i);
        }
        ops.add(new String[] {type, a, b});
        while (ops.size() > MAX_OPS) ops.remove(0);
        save();
    }

    // ===== Persistence =====

    private void load() {
        if (file == null || !file.isFile()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length == 2 && p[0].equals("B")) blocked.add(p[1]);
                else if (p.length == 3) ops.add(new String[] {p[0], p[1], p[2]});
            }
        } catch (IOException ignored) {
            // Start empty if the file is unreadable.
        }
    }

    private void save() {
        if (file == null) return;
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            for (String b : blocked) w.write("B\t" + b + "\n");
            for (String[] o : ops) w.write(o[0] + "\t" + o[1] + "\t" + o[2] + "\n");
        } catch (IOException ignored) {
            // Rules still work in memory for this session.
        }
    }
}
