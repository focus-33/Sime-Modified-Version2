#!/usr/bin/env bash
# 用雾凇词库给 sime.dict 补充多字词的其他读音（不需要训练语料，sime.cnt 不用动）。
#
# 用法：tools/patch_dict.sh <engine-src> <rime-ice/cn_dicts> <in sime.dict> <out sime.dict>
# 可用环境变量 SIME_DUMP / SIME_CONVERTER 直接指定已编译好的工具，跳过 cmake 编译。
set -euo pipefail
ENGINE=$(realpath "$1"); RIME=$(realpath "$2"); IN=$(realpath "$3"); OUT=$4
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)

if [ -z "${SIME_DUMP:-}" ] || [ -z "${SIME_CONVERTER:-}" ]; then
  # 直接用 g++ 编译引擎核心 + 两个小工具（不依赖 cmake / ncnn）。
  mkdir -p "$WORK/obj"
  ls "$ENGINE"/src/*.cc | xargs -P "$(nproc)" -I{} sh -c \
    'g++ -std=c++20 -O1 -I"$1/include" -I"$1/src" -c "$2" -o "$3/obj/$(basename "$2" .cc).o"' _ "$ENGINE" {} "$WORK"
  for t in dump converter; do
    g++ -std=c++20 -O1 -I"$ENGINE/include" -I"$ENGINE/src" "$ENGINE/bin/$t.cc" \
        "$WORK"/obj/*.o -o "$WORK/sime-$t"
  done
  SIME_DUMP="$WORK/sime-dump"
  SIME_CONVERTER="$WORK/sime-converter"
fi

"$SIME_DUMP" --dict "$IN" --out "$WORK/d" >/dev/null 2>&1

# 自检：不做任何修改重建一遍，必须和原文件逐字节一致，保证工具链无损。
sed "s/'/\//g" "$WORK/d.letter_pinyin" > "$WORK/cn.same.txt"
"$SIME_CONVERTER" "$WORK/d.token" "$WORK/same.dict" --cn "$WORK/cn.same.txt" \
        --en "$WORK/d.letter_en" >/dev/null 2>&1
if ! cmp -s "$WORK/same.dict" "$IN"; then
  echo "自检失败：未修改重建的词典与原文件不一致，放弃补丁。" >&2
  exit 3
fi

python3 "$HERE/augment_readings.py" --dump "$WORK/d.letter_pinyin" --rime "$RIME" \
        --out "$WORK/cn.txt" --add "$HERE/add_readings.txt" --remove "$HERE/remove_readings.txt"
"$SIME_CONVERTER" "$WORK/d.token" "$OUT" --cn "$WORK/cn.txt" \
        --en "$WORK/d.letter_en" >/dev/null 2>&1
echo "已生成: $OUT ($(stat -c %s "$OUT") 字节)"
rm -rf "$WORK"
