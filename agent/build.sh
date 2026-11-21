#!/usr/bin/env bash
# build_agent.sh —— 打「电视Agent」APK(纯 Java,无第三方库;无障碍 + 前台 HTTP 直连)
# 配方:aapt2 + javac + d8 + apksigner(无 Gradle)
# 与遥控壳的关键差异:
#   1) 本项目带 res(xml/drawable)→ 先 aapt2 compile 再 link
#   2) link 加 -A assets,把 index.html / key.txt / apps.json 打进 APK
#   3) 构建前从凭据文件提取两个 key 写 assets/key.txt(不进 git):
#        第 1 行 SILICONFLOW_API_KEY=xxx
#        第 2 行 MODELSCOPE_API_KEY=xxx
#   4) assets/apps.json 由 apps.example.json 生成(example 更新时覆盖,本地改的 apps.json 更新时保留)
#   5) keystore:<KEYSTORE>(默认 build/keys/agent.jks,密码 agent12345),产物 build/out/agent.apk
#
# 可用环境变量覆盖默认值(便于开源/他人环境):
#   TV_AGENT_TOOLCHAIN  工具链根目录,须含 jdk/jdk-17.0.2、bt/android-14、plat/android-34
#   TV_AGENT_CRED       凭据文件(YAML,含 SILICONFLOW_API_KEY / MODELSCOPE_API_KEY 两个键)
set -e
cd "$(dirname "$0")"

TC="${TV_AGENT_TOOLCHAIN:-../toolchain}"      # <TOOLCHAIN_DIR>
CRED="${TV_AGENT_CRED:-credentials.yaml}"               # <KEY_FILE>
JDK="$TC/jdk/jdk-17.0.2"
BT="$TC/bt/android-14"
PLAT="$TC/plat/android-34/android.jar"
APP=app
OUT=build/out
KS="${TV_AGENT_KS:-build/keys/agent.jks}"     # <KEYSTORE>
PASS=agent12345

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen" build/keys "$APP/assets"

echo "[0/7] 注入 API key -> assets/key.txt(两行)+ 生成 apps.json"
python -I - "$CRED" <<'PY'
import io, sys

CRED = sys.argv[1]
OUTF = r"app/assets/key.txt"   # 相对项目根(cwd 已在脚本目录)


def pick(name):
    """逐行找 name: 取值,strip 引号;找不到返回空串"""
    try:
        with io.open(CRED, "r", encoding="utf-8") as f:
            for line in f:
                s = line.strip()
                if s.startswith(name) and (":" in s or "=" in s):
                    v = s.split(":", 1)[1] if ":" in s else s.split("=", 1)[1]
                    v = v.strip().strip('"').strip("'").strip()
                    return v
    except Exception as e:
        print("  读取凭据失败:", e)
    return ""


sf = pick("SILICONFLOW_API_KEY")
ms = pick("MODELSCOPE_API_KEY")
# 显式 LF 二进制写,避免 Windows 文本模式把 \n 变 \r\n
with io.open(OUTF, "wb") as f:
    f.write(("SILICONFLOW_API_KEY=" + sf + "\n").encode("utf-8"))
    f.write(("MODELSCOPE_API_KEY=" + ms + "\n").encode("utf-8"))
print("  SILICONFLOW_API_KEY:", ("OK,长度 %d" % len(sf)) if sf else "!!为空,语音识别将不可用")
print("  MODELSCOPE_API_KEY :", ("OK,长度 %d" % len(ms)) if ms else "!!为空,文字指令将不可用")
PY
# apps.json:不存在 → 从模板复制;example 比本地 apps.json 新(说明你改了模板)→ 覆盖;
# 本地 apps.json 自己改过(比 example 新)→ 保留
if [ ! -f "$APP/assets/apps.json" ] || [ "$APP/assets/apps.example.json" -nt "$APP/assets/apps.json" ]; then
  cp "$APP/assets/apps.example.json" "$APP/assets/apps.json"
  echo "  apps.json 已从 apps.example.json 生成(占位示例,记得改成你电视上装的应用)"
else
  echo "  apps.json 使用本地已有版本"
fi
echo "  提醒:app/assets/key.txt 与 app/assets/apps.json 为本地生成文件,已在 .gitignore 排除,勿提交"

echo "[1/7] aapt2 编译资源 + 链接(含 assets)"
"$BT/aapt2.exe" compile --dir "$APP/res" -o "$OUT/res.zip"
"$BT/aapt2.exe" link -o "$OUT/base.apk" \
  -I "$PLAT" \
  --manifest "$APP/AndroidManifest.xml" \
  -A "$APP/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 23 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0 \
  "$OUT/res.zip"

echo "[2/7] 编译 Java"
find "$APP/src" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
if ! "$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$PLAT" \
    -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  echo "  ✗ javac 失败:"
  grep -a "错误\|error:" "$OUT/javac.log" | head -20
  exit 1
fi
echo "  ✓ $(find "$OUT/classes" -name '*.class' | wc -l) 个 class"

echo "[3/7] 转 dex"
find "$OUT/classes" -name "*.class" > "$OUT/classes.txt"
"$JDK/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api 23 --lib "$PLAT" --output "$OUT/dex" @"$OUT/classes.txt"

echo "[4/7] 打包 classes.dex"
python -I - "$OUT" <<'PY'
import sys, zipfile, shutil, pathlib
out = pathlib.Path(sys.argv[1])
shutil.copyfile(out / "base.apk", out / "unsigned.apk")
with zipfile.ZipFile(out / "unsigned.apk", "a") as z:
    z.write(out / "dex" / "classes.dex", "classes.dex")
print("  已加入 classes.dex")
PY

echo "[5/7] 对齐 + 签名"
"$BT/zipalign.exe" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f "$KS" ]; then
  "$JDK/bin/keytool" -genkeypair -keystore "$KS" -alias agent -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$PASS" -keypass "$PASS" -dname "CN=TVAgent, O=Home, C=CN" >/dev/null 2>&1
fi
"$JDK/bin/java" -jar "$BT/lib/apksigner.jar" sign --ks "$KS" --ks-pass pass:"$PASS" \
  --key-pass pass:"$PASS" --out "$OUT/agent.apk" "$OUT/aligned.apk"

echo "[6/7] 自检:apksigner verify"
"$JDK/bin/java" -jar "$BT/lib/apksigner.jar" verify "$OUT/agent.apk" && echo "  ✓ 签名验证通过"

echo "[7/7] 自检:index.html 与 assets"
python -I - "$APP" <<'PY'
import io, json, os, sys, zipfile
app = sys.argv[1]
p = os.path.join(app, "assets", "index.html")
data = io.open(p, "r", encoding="utf-8").read()
size = os.path.getsize(p)
assert size > 10 * 1024, "index.html 只有 %d 字节,疑似丢内容" % size
opens = data.count("<script")
closes = data.count("</script>")
assert opens == closes and opens >= 1, "<script> 配对异常:%d/%d" % (opens, closes)
print("  index.html OK:%d 字节,<script> %d/%d 配对" % (size, opens, closes))
# APK 内容核对
z = zipfile.ZipFile(os.path.join("build", "out", "agent.apk"))
for want in ("assets/index.html", "assets/key.txt", "assets/apps.json", "classes.dex"):
    assert want in z.namelist(), "APK 缺少 " + want
# apps.json 必须是合法 JSON 数组
json.load(io.open(os.path.join(app, "assets", "apps.json"), encoding="utf-8"))
print("  APK 内 assets/index.html、key.txt、apps.json、classes.dex 均在;apps.json JSON 合法")
PY
ls -lh "$OUT/agent.apk"
echo "完成:build/out/agent.apk"
