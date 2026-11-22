package com.shiyu.sime.ime.engine;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Collections;
import java.util.Set;

/**
 * The pinyin syllables the dictionary uses (including lue / nue, which the
 * dictionary itself spells lve / nve), and small helpers over them. Used to
 * tell complete syllables from abbreviations / unfinished syllables.
 */
public final class PinyinSyllables {
    private static final Set<String> SYLLABLES = new HashSet<>(Arrays.asList((
            "a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu ca cai can " +
            "cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chuai " +
            "chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo da dai dan dang dao de dei den " +
            "deng di dian diao die ding diu dong dou du duan dui dun duo e ei en eng er fa fan fang fei " +
            "fen feng fo fou fu ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui " +
            "gun guo ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo ji " +
            "jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun ka kai kan kang kao ke kei ken " +
            "keng kong kou ku kua kuai kuan kuang kui kun kuo la lai lan lang lao le lei leng li lia " +
            "lian liang liao lie lin ling liu long lou lu luan lue lun luo lv ma mai man mang mao me " +
            "mei men meng mi mian miao mie min ming miu mo mou mu na nai nan nang nao ne nei nen neng " +
            "ni nian niang niao nie nin ning niu nong nou nu nuan nue nuo nv o ou pa pai pan pang pao " +
            "pei pen peng pi pian piao pie pin ping po pou pu qi qia qian qiang qiao qie qin qing qiong " +
            "qiu qu quan que qun ran rang rao re ren reng ri rong rou ru ruan rui run ruo sa sai san " +
            "sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai " +
            "shuan shuang shui shun shuo si song sou su suan sui sun suo ta tai tan tang tao te teng ti " +
            "tian tiao tie ting tong tou tu tuan tui tun tuo wa wai wan wang wei wen weng wo wu xi xia " +
            "xian xiang xiao xie xin xing xiong xiu xu xuan xue xun ya yan yang yao ye yi yin ying yo " +
            "yong you yu yuan yue yun za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe " +
            "zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu " +
            "zuan zui zun zuo chua dia fiao lo lve nun nve rua tei "
            ).trim().split(" ")));
    private static final Set<String> INITIALS = new HashSet<>(Arrays.asList(
            "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x",
            "zh", "ch", "sh", "r", "z", "c", "s", "y", "w"));

    private PinyinSyllables() {}

    public static boolean isSyllable(String s) { return SYLLABLES.contains(s); }

    /** Immutable view used by the independent fuzzy-pinyin parser. */
    public static Set<String> allSyllables() {
        return Collections.unmodifiableSet(SYLLABLES);
    }

    private static Set<String> prefixes;

    /** True when {@code s} is a syllable or the beginning of one (e.g. "zh", "sh", "shu"). */
    public static synchronized boolean isSyllablePrefix(String s) {
        if (s == null || s.isEmpty()) return false;
        if (prefixes == null) {
            Set<String> p = new HashSet<>();
            for (String syl : SYLLABLES) {
                for (int i = 1; i <= syl.length(); i++) p.add(syl.substring(0, i));
            }
            prefixes = p;
        }
        return prefixes.contains(s);
    }

    /**
     * Split pinyin into syllable-like tokens (longest syllable first, then
     * an initial, then a single character); {@code seps.get(i)} holds the
     * apostrophes that follow {@code tokens.get(i)}.
     */
    static void tokenize(String in, List<String> tokens, List<String> seps) {
        int i = 0, n = in.length();
        while (i < n) {
            if (in.charAt(i) == '\'') { i++; continue; }
            String tok = null;
            for (int len = Math.min(6, n - i); len >= 1 && tok == null; len--) {
                String t = in.substring(i, i + len);
                if (t.indexOf('\'') >= 0) continue;
                if (SYLLABLES.contains(t) || INITIALS.contains(t)) tok = t;
            }
            if (tok == null) tok = in.substring(i, i + 1);
            i += tok.length();
            int s = i;
            while (i < n && in.charAt(i) == '\'') i++;
            tokens.add(tok);
            seps.add(in.substring(s, i));
        }
    }
}
