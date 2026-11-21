#!/usr/bin/env bash
# 打手机端 WebView 壳 APK:加载电视上 TVAgent 提供的遥控页
# 无第三方依赖:aapt2 + javac + d8 + apksigner
set -e
cd "$(dirname "$0")"

TC="${TOOLCHAIN_DIR:-build/toolchain}"
JDK=$TC/jdk/jdk-17.0.2
BT=$TC/bt/android-14
PLAT=$TC/plat/android-34/android.jar
OUT=build/out
APP=app
KS=build/keys/tvremote.jks
PASS=tvremote123

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" build/keys

echo "[1/5] 链接清单(无 res)"
"$BT/aapt2.exe" link -o "$OUT/base.apk" \
  -I "$PLAT" \
  --manifest "$APP/AndroidManifest.xml" \
  --min-sdk-version 23 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0

echo "[2/5] 编译 Java"
find "$APP/src" -name "*.java" > "$OUT/sources.txt"
if ! "$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$PLAT" \
  -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  echo "  ✗ javac 失败:"
  grep -a "错误\|error:" "$OUT/javac.log" | head -15
  exit 1
fi
echo "  ✓ $(find "$OUT/classes" -name '*.class' | wc -l) 个 class"

echo "[3/5] 转 dex"
find "$OUT/classes" -name "*.class" > "$OUT/classes.txt"
"$JDK/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api 23 --lib "$PLAT" --output "$OUT/dex" @"$OUT/classes.txt"

echo "[4/5] 打进 APK"
python - "$OUT" <<'PY'
import sys, zipfile, shutil, pathlib
out = pathlib.Path(sys.argv[1])
shutil.copyfile(out / "base.apk", out / "unsigned.apk")
with zipfile.ZipFile(out / "unsigned.apk", "a") as z:
    z.write(out / "dex" / "classes.dex", "classes.dex")
print("  已加入 classes.dex")
PY

echo "[5/5] 对齐 + 签名"
"$BT/zipalign.exe" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f "$KS" ]; then
  "$JDK/bin/keytool" -genkeypair -keystore "$KS" -alias tvremote -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$PASS" -keypass "$PASS" -dname "CN=TVRemote, O=Home, C=CN" >/dev/null 2>&1
fi
"$JDK/bin/java" -jar "$BT/lib/apksigner.jar" sign --ks "$KS" --ks-pass pass:"$PASS" \
  --key-pass pass:"$PASS" --out "$OUT/tvremote.apk" "$OUT/aligned.apk"
"$JDK/bin/java" -jar "$BT/lib/apksigner.jar" verify "$OUT/tvremote.apk" | head -3
ls -lh "$OUT/tvremote.apk"
