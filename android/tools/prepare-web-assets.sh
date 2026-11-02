#!/usr/bin/env bash
#
# 把网页版的 index.html 与离线字体整理成可直接放进 APK 的 assets/web/ 目录。
#
#   用法: prepare-web-assets.sh <源 index.html> <字体目录> <输出目录>
#
# 做两件事：
#   1. 去掉 Google Fonts 的 CDN 引用 —— APK 必须能完全离线使用；
#   2. 换成本地字体表（Latin 内置 woff2，中日韩走系统自带的 Noto CJK）。
#
# 网页版的 index.html 本身不会被改动，APK 里的是一份派生产物。

set -euo pipefail

SRC_HTML="${1:?用法: prepare-web-assets.sh <源 index.html> <字体目录> <输出目录>}"
FONT_DIR="${2:?缺少字体目录}"
OUT_DIR="${3:?缺少输出目录}"

[ -f "$SRC_HTML" ] || { echo "找不到源文件: $SRC_HTML" >&2; exit 1; }
[ -d "$FONT_DIR" ] || { echo "找不到字体目录: $FONT_DIR" >&2; exit 1; }

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/fonts"

# 字体原样拷贝
find "$FONT_DIR" -maxdepth 1 -type f \( -name '*.woff2' -o -name '*.woff' -o -name '*.css' \) \
  -exec cp {} "$OUT_DIR/fonts/" \;

# 处理 HTML
python3 - "$SRC_HTML" "$OUT_DIR/index.html" <<'PY'
import re
import sys

src, dst = sys.argv[1], sys.argv[2]
html = open(src, encoding='utf-8').read()

# 去掉指向 Google Fonts 的 <link>（preconnect 与样式表）
before = html
html = re.sub(r'(?m)^[ \t]*<link[^>]*fonts\.(googleapis|gstatic)\.com[^>]*>[ \t]*\r?\n?', '', html)
if html == before:
    print('提示: 未在源文件中找到 Google Fonts 引用，字体表仍然会被注入')

# 注入本地字体表
if 'fonts/fonts.css' not in html:
    html = html.replace('<head>', '<head>\n  <link rel="stylesheet" href="fonts/fonts.css">', 1)

open(dst, 'w', encoding='utf-8').write(html)
print('已生成: ' + dst)
PY

echo "资源目录: $OUT_DIR"
ls -1 "$OUT_DIR" "$OUT_DIR/fonts"
