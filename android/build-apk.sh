#!/usr/bin/env bash
#
# 离线构建 APK —— 只用本机的 Android SDK build-tools 与 JDK，不需要下载 Gradle。
#
#   ./build-apk.sh              构建正式包（用 keystore/flashcards.jks 签名）
#   ./build-apk.sh --debug      构建调试包（包名带 .debug 后缀）
#
# 产物:
#   build/manual/out/flashcards-release.apk
#   build/manual/out/flashcards-debug.apk
#
# 日常开发建议用 Gradle（./gradlew assembleRelease），本脚本是「没有网络 / 不想装 Gradle」时的备选，
# 两者产出的 APK 内容一致。

set -euo pipefail

cd "$(dirname "$0")"

VARIANT=release
[ "${1:-}" = "--debug" ] && VARIANT=debug

# ---------------------------------------------------------------- 环境
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-35.0.0}"
PLATFORM_VERSION="${PLATFORM_VERSION:-35}"

BT="$SDK/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK/platforms/android-$PLATFORM_VERSION/android.jar"

# JDK 17（AGP / d8 都要求）
if [ -z "${JAVA_HOME:-}" ]; then
  for cand in /opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/openjdk /usr/libexec/java_home; do
    if [ -x "$cand/bin/javac" ] || [ -x "$cand" ]; then JAVA_HOME="$cand"; break; fi
  done
fi
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ] || { echo "找不到 JDK，请设置 JAVA_HOME" >&2; exit 1; }

[ -x "$BT/aapt2" ]   || { echo "找不到 build-tools: $BT" >&2; exit 1; }
[ -f "$ANDROID_JAR" ] || { echo "找不到 android.jar: $ANDROID_JAR" >&2; exit 1; }

echo "SDK        : $SDK"
echo "build-tools: $BUILD_TOOLS_VERSION"
echo "platform   : $PLATFORM_VERSION"
echo "JDK        : $($JAVA_HOME/bin/java -version 2>&1 | head -1)"
echo "变体       : $VARIANT"
echo

# ---------------------------------------------------------------- 路径
OUT="build/manual"
GEN="$OUT/gen"          # aapt2 生成的 R.java
CLASSES="$OUT/classes"  # javac 产物
DEX="$OUT/dex"          # d8 产物
APKS="$OUT/apks"        # 中间 apk
ASSETS="$OUT/assets"    # 打包进 apk 的网页资源
FINAL="$OUT/out"

# 只清理中间产物，保留 out/ 里已经构建好的其它变体
rm -rf "$GEN" "$CLASSES" "$DEX" "$APKS" "$ASSETS"
mkdir -p "$GEN" "$CLASSES" "$DEX" "$APKS" "$FINAL"

# ---------------------------------------------------------------- 1. 网页资源
echo "[1/6] 准备网页资源…"
./tools/prepare-web-assets.sh ../index.html app/src/main/webfonts "$ASSETS/web"

# ---------------------------------------------------------------- 2. 编译资源
echo "[2/6] 编译资源 (aapt2 compile)…"
"$BT/aapt2" compile --dir app/src/main/res -o "$OUT/res.zip"

# 清单处理：raw aapt2 要求 <manifest> 带 package 属性，但 AGP 8 又不允许在源码里写 package。
# 所以统一在这里生成一份带 package 的临时清单，源码清单保持 AGP 8 兼容。
PKG="com.amarisgo.flashcards"
[ "$VARIANT" = "debug" ] && PKG="com.amarisgo.flashcards.debug"

MANIFEST="$OUT/AndroidManifest.xml"
python3 - "$MANIFEST" "$PKG" <<'PY'
import re, sys
dst, pkg = sys.argv[1], sys.argv[2]
m = open('app/src/main/AndroidManifest.xml', encoding='utf-8').read()
m = re.sub(r'(<manifest[^>]*?)\s+package="[^"]*"', r'\1', m, count=1)
m = m.replace('<manifest', '<manifest package="%s"' % pkg, 1)
open(dst, 'w', encoding='utf-8').write(m)
print('临时清单: %s (package=%s)' % (dst, pkg))
PY

# ---------------------------------------------------------------- 3. 链接资源
echo "[3/6] 链接资源与清单 (aapt2 link)…"
"$BT/aapt2" link \
  -o "$APKS/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" \
  -R "$OUT/res.zip" \
  -A "$ASSETS" \
  --java "$GEN" \
  --min-sdk-version 26 \
  --target-sdk-version "$PLATFORM_VERSION" \
  --version-code 5 \
  --version-name 2.3 \
  --auto-add-overlay

# ---------------------------------------------------------------- 4. 编译 Java
echo "[4/6] 编译 Java 源码…"
find "$GEN" app/src/main/java -name '*.java' > "$OUT/sources.txt"
# 用 -source/-target 8 + -bootclasspath android.jar：
# JDK 9+ 起 javac 不允许在 target>=9 时使用 -bootclasspath，而 android.jar 才是真正的编译期依赖。
# 产出 Java 8 字节码，d8 直接可以转 dex。
"$JAVA_HOME/bin/javac" \
  -encoding UTF-8 \
  -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -Xlint:-options \
  -nowarn \
  -d "$CLASSES" \
  @"$OUT/sources.txt"

# ---------------------------------------------------------------- 5. 转 dex 并塞进 apk
echo "[5/6] 转换 dex 并打包…"
find "$CLASSES" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" \
  --lib "$ANDROID_JAR" \
  --min-api 26 \
  --output "$DEX" \
  @"$OUT/classes.txt"

cp "$APKS/base.apk" "$APKS/unsigned.apk"
# aapt add 需要绝对路径（下面会 cd 到 dex 目录）
(cd "$DEX" && "$BT/aapt" add -f "$PWD/../apks/unsigned.apk" classes.dex)

# ---------------------------------------------------------------- 6. 对齐与签名
echo "[6/6] 对齐并签名…"
"$BT/zipalign" -p -f 4 "$APKS/unsigned.apk" "$APKS/aligned.apk"

KS="keystore/flashcards.jks"
if [ -f "$KS" ]; then
  KS_PASS=$(grep '^storePassword=' keystore/keystore.properties 2>/dev/null | cut -d= -f2-)
  KEY_PASS=$(grep '^keyPassword=' keystore/keystore.properties 2>/dev/null | cut -d= -f2-)
  KEY_ALIAS=$(grep '^keyAlias=' keystore/keystore.properties 2>/dev/null | cut -d= -f2-)
  KS_PASS="${KS_PASS:-flashcards}"; KEY_PASS="${KEY_PASS:-flashcards}"; KEY_ALIAS="${KEY_ALIAS:-flashcards}"
  "$BT/apksigner" sign \
    --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" --ks-key-alias "$KEY_ALIAS" \
    --out "$FINAL/flashcards-$VARIANT.apk" "$APKS/aligned.apk"
  echo "✓ 已签名"
else
  cp "$APKS/aligned.apk" "$FINAL/flashcards-$VARIANT-unsigned.apk"
  echo "⚠️  未找到 keystore/flashcards.jks，产出的是未签名包"
fi

echo
echo "════════════════════════════════════"
echo " 产物: $FINAL/flashcards-$VARIANT.apk"
ls -lh "$FINAL" | tail -n +2
echo
"$BT/apksigner" verify --print-certs "$FINAL/flashcards-$VARIANT.apk" 2>&1 | head -6 || true
echo "════════════════════════════════════"
