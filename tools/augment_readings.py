#!/usr/bin/env python3
"""给 Sime 词典补充“多字词的其他读音”。

背景：pipeline/gen_units.py 读雾凇(rime-ice)词库时，多字词只保留“第一次出现的读音”，
所以像“便宜”(bian yi / pian yi) 这样的多音词只剩一个读音，输入 pianyi 就打不出来。

本脚本不需要训练语料：
  1. 读 sime-dump 导出的 <prefix>.letter_pinyin（现有 词 -> 读音）；
  2. 读雾凇的 *.dict.yaml，找出“Sime 已有的词”在雾凇里的其他读音；
  3. 输出 sime-converter 能读的 --cn 文件（原有内容 + 新增读音行）。

词条 id 不变，所以 sime.cnt（语言模型）不用动；新读音与原读音共用同一个词的分数。

用法：
  augment_readings.py --dump d.letter_pinyin --rime /path/to/rime-ice/cn_dicts --out cn.txt
"""
import argparse
import os
import sys
from collections import defaultdict

DEFAULT_FILES = ["8105.dict.yaml", "41448.dict.yaml", "base.dict.yaml",
                 "ext.dict.yaml", "tencent.dict.yaml"]


def parse_weight(text):
    text = text.strip()
    if not text:
        return None
    try:
        if text.endswith('%'):
            return float(text[:-1]) / 100.0
        return float(text)
    except ValueError:
        return None


def parse_rime_dict(path):
    """yield (word, [syllables], weight|None)；格式：词<TAB>拼音(空格分隔)<TAB>权重"""
    in_header = True
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if in_header:
                if line.strip() == '...':
                    in_header = False
                continue
            if not line or line.startswith('#'):
                continue
            parts = line.split('\t')
            if len(parts) < 2:
                continue
            word = parts[0]
            syl = parts[1].strip().split()
            weight = parse_weight(parts[2]) if len(parts) > 2 else None
            yield word, syl, weight


def collect_files(paths):
    files = []
    for p in paths:
        if os.path.isdir(p):
            for name in DEFAULT_FILES:
                fp = os.path.join(p, name)
                if os.path.exists(fp):
                    files.append(fp)
        elif os.path.exists(p):
            files.append(p)
    return files


def read_pairs(path):
    """读“词 拼音”清单 -> [(词, "bian'yi")]"""
    out = []
    if not path or not os.path.exists(path):
        return out
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.split('#', 1)[0].strip()
            if not line:
                continue
            parts = line.split()
            if len(parts) < 2:
                continue
            out.append((parts[0], parts[1].replace('/', "'")))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--dump', required=True, help='sime-dump 导出的 .letter_pinyin')
    ap.add_argument('--rime', required=True, nargs='+',
                    help='雾凇 cn_dicts 目录，或具体的 *.dict.yaml 文件')
    ap.add_argument('--out', required=True, help='输出给 sime-converter 的 --cn 文件')
    ap.add_argument('--min-ratio', type=float, default=0.02,
                    help='新读音权重至少是该词最高权重的这个比例（默认 0.02，调小会多收冷僻读音）')
    ap.add_argument('--max-readings', type=int, default=4,
                    help='每个词最多保留的读音数（默认 4）')
    ap.add_argument('--show', type=int, default=25, help='打印多少条新增示例')
    ap.add_argument('--remove', help='要删除的读音清单，每行“词 拼音”（拼音用 \' 或 / 分隔音节）；# 开头为注释')
    ap.add_argument('--add', help='要强制补上的读音清单，格式同上')
    args = ap.parse_args()

    # 1. 现有词典
    existing = defaultdict(set)      # word -> {"bian'yi"}
    syllables = set()
    lines = []
    with open(args.dump, encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if not line:
                continue
            word, _, pieces = line.partition(' ')
            if not pieces:
                continue
            lines.append((word, pieces))
            existing[word].add(pieces)
            for s in pieces.split("'"):
                if s:
                    syllables.add(s)
    print(f'现有词条行数: {len(lines)}，词数: {len(existing)}，音节数: {len(syllables)}',
          file=sys.stderr)

    # 2. 雾凇读音
    rime = defaultdict(dict)         # word -> {pieces: max weight}
    files = collect_files(args.rime)
    if not files:
        print('错误：没有找到雾凇词库文件', file=sys.stderr)
        return 2
    for fp in files:
        n = 0
        for word, syl, weight in parse_rime_dict(fp):
            if len(word) < 2 or word not in existing:
                continue
            if len(syl) != len(word) or not all(s in syllables for s in syl):
                continue
            pieces = "'".join(syl)
            w = 1.0 if weight is None else weight
            cur = rime[word].get(pieces)
            rime[word][pieces] = w if cur is None else max(cur, w)
            n += 1
        print(f'{os.path.basename(fp)}: 读到可用行 {n}', file=sys.stderr)

    # 3. 选出要新增的读音
    added = []
    for word, readings in rime.items():
        have = existing[word]
        top = max(readings.values()) or 1.0
        room = args.max_readings - len(have)
        if room <= 0:
            continue
        cands = [(w, p) for p, w in readings.items()
                 if p not in have and (top <= 0 or w >= args.min_ratio * top)]
        cands.sort(key=lambda x: -x[0])
        for w, p in cands[:room]:
            added.append((word, p))

    # 4. 手工清单：先强制补上，再删除（删除优先，且不会把一个词删到没有任何读音）
    forced = read_pairs(args.add)
    removals = set(read_pairs(args.remove))
    all_rows = list(lines) + list(added) + [(w, p) for w, p in forced]
    have = defaultdict(set)
    for w, p in all_rows:
        have[w].add(p)
    removed = []
    final = []
    seen = set()
    for w, p in all_rows:
        if (w, p) in seen:
            continue
        seen.add((w, p))
        if (w, p) in removals:
            if len(have[w]) > 1:
                have[w].discard(p)
                removed.append((w, p))
                continue
            print(f'  ! 跳过删除 {w} {p}：这是它唯一的读音', file=sys.stderr)
        final.append((w, p))

    # 5. 输出（converter 用 / 分隔音节）
    with open(args.out, 'w', encoding='utf-8') as out:
        for word, pieces in final:
            out.write(f"{word} {pieces.replace(chr(39), '/')}\n")
    for w, p in forced:
        print(f'  + (手工) {w} {p}', file=sys.stderr)
    for w, p in removed:
        print(f'  - (删除) {w} {p}', file=sys.stderr)

    print(f'新增读音: {len(added)} 条（涉及 {len(set(w for w, _ in added))} 个词）',
          file=sys.stderr)
    for word, pieces in added[:args.show]:
        print(f'  + {word}  {pieces}   (原有: {" | ".join(sorted(existing[word]))})',
              file=sys.stderr)
    return 0


if __name__ == '__main__':
    sys.exit(main())
