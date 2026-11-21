# tv-agent-remote

手机直连 Android TV 的遥控方案:**电视上装一个无障碍 agent,手机浏览器/壳 app 直连它**,不需要电脑、不需要 root。

```
手机(app / 浏览器)  ──HTTP──▶  电视上的 TVAgent  ──AccessibilityService──▶  任意已安装应用
   触控板·键盘·语音                (无障碍执行 + 内置HTTP服务)              影视仓/网盘/任何app
```

## 能做什么

- **触控板**:滑动=坐标点按/拖动,轻点=确认,双指=返回,长滑=翻页
- **找片/播放**:启动应用 → 找搜索框 → 写入片名 → 点搜索 → 点结果;播放控制(唤菜单/快进/全屏/清晰度)
- **键盘直输**:在电视上点开搜索框,手机上打字「填入并搜索」(ACTION_SET_TEXT,不依赖输入法)
- **语音指令**(三种模式可切换):
  - 🎤 语音:手机录音 → LLM 多模态模型听音出指令 JSON
  - 📝 文字:手机输入法语音转文字/手打 → LLM 文本模型解析
  - ⚡ 直发:文本原样作为片名直接搜索,不经过模型
- **不干扰系统语音助手**:检测到助手类应用在前台时自动让路,只做提示

## 架构

| 目录 | 内容 |
|---|---|
| `agent/` | 电视端 app:AccessibilityService(点按/滑动/写字/读屏)+ 前台 HTTP 服务(9999 端口)+ 内置遥控网页 |
| `phone/` | 手机端 WebView 壳:加载电视 agent 提供的遥控页,麦克风授权 |

无障碍读屏是毫秒级(对比 `uiautomator dump` 的 3.4s/次),所以整个遥控是实时手感。

## 构建

工具链:JDK17 + Android build-tools 34 + platform android-34(无 Gradle/AGP,纯 aapt2+javac+d8+apksigner)。
把三者放在一个目录,结构如下(或用 `TOOLCHAIN_DIR` 环境变量指定):

```
toolchain/
├── jdk/jdk-17.0.2/
├── bt/android-14/        # aapt2.exe zipalign.exe lib/d8.jar lib/apksigner.jar
└── plat/android-34/android.jar
```

```bash
# 电视端(先改 agent/assets/apps.json 填你电视上装的应用)
cd agent && bash build.sh     # 产出 build/out/agent.apk

# 手机端(先改 phone/src/.../MainActivity.java 里的 AGENT_URL 为你电视的 IP)
cd phone && bash build.sh     # 产出 build/out/tvremote.apk
```

## 安装启用(一次性)

1. `adb install agent.apk` 到电视
2. 启用无障碍(绕过 Android 13+ 的受限设置):
   ```bash
   adb shell settings put secure enabled_accessibility_services com.tv.agent/.AgentService
   adb shell settings put secure accessibility_enabled 1
   ```
3. 打开电视上的「电视Agent」应用(启动常驻服务;建议在电视设置里给它开自启动)
4. `adb install tvremote.apk` 到手机;打开 app,或手机浏览器直接访问 `http://<电视IP>:9999`
5. 首次按语音按钮允许麦克风权限

## 配置

| 文件 | 说明 |
|---|---|
| `agent/assets/apps.json` | 你电视上装的应用列表(name/pkg/搜索入口文字/提交按钮文字),从 `apps.example.json` 复制修改 |
| `agent/assets/key.txt` | LLM API 密钥(构建时生成,不进 git)。语音模式用支持音频输入的 OpenAI 兼容端点,文字模式用文本模型,端点/模型名在 `HttpService.java` 顶部常量里改 |
| `phone/.../MainActivity.java` | `AGENT_URL` 改成你电视的局域网 IP |

## 隐私

- API 密钥和应用表**构建时注入,不入库**(`.gitignore` 已排除)
- 不采集任何数据;HTTP 服务只监听局域网

## 免责声明

本项目仅用于**个人自有设备的自动化与无障碍辅助**,请遵守你所在地区的法律法规,以及各应用/内容服务的用户协议。

## License

MIT
