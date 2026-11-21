# 电视Agent(tv-agent-app)

Android TV 上的「无障碍遥控代理」:手机浏览器直连电视,通过 AccessibilityService
替你在电视上点击 / 输入 / 滑动,不依赖电脑、不依赖 adb。纯 Java + Android 自带库,零第三方依赖。

- **AgentService**:无障碍服务,提供返回/主页、合成触摸(tap/swipe)、按文字点击、
  往输入框灌字、读屏幕文字、取前台包名(小爱互斥守卫用)。
- **HttpService**:specialUse 前台服务,内嵌 HTTP 服务器监听 `0.0.0.0:9999`,
  手机同局域网浏览器打开 `http://<TV_IP>:9999` 即得遥控页(assets/index.html)。
- **MainActivity**:状态页,显示电视 IP;按返回退出界面,服务常驻。

## 功能

- 指针触控板(1920×1080 虚拟光标,慢滑移动 / 轻点确认 / 双指返回 / 快滑翻页)
- 返回 / 主页 / 音量(带 from→to 回显)
- 播放控制:唤出菜单、手势快进快退、全屏、清晰度/线路选择
- 指令三模式:🎤 语音(硅基流动 Qwen3-Omni)、📝 文字(魔搭 Qwen3.5)、⚡ 直发搜片(不经模型)
- 键盘输入:「填入」/「填入并搜索」
- **小爱互斥守卫**:检测到小爱在前台(包名含 voiceassist / aiassistant / miai)时,
  所有会动电视界面的操作一律拒绝并提示,两个助手完全独立互不干涉

## 构建

前置:JDK 17 + build-tools(aapt2/d8/zipalign/apksigner)+ android.jar(API 34)。
脚本默认从 `<TOOLCHAIN_DIR>` 取工具链,可用环境变量覆盖:

```bash
bash build_agent.sh                      # 用脚本内默认路径
TV_AGENT_TOOLCHAIN=/path/to/toolchain bash build_agent.sh   # 自定义工具链(含 jdk/bt/plat)
TV_AGENT_CRED=/path/to/credentials.yaml bash build_agent.sh # 自定义凭据文件
```

构建脚本会:

1. 从 `<KEY_FILE>`(默认 `~/.dsh/.credentials.yaml`,YAML 冒号格式)提取
   `SILICONFLOW_API_KEY` 和 `MODELSCOPE_API_KEY`,写成 `app/assets/key.txt`
   (两行 `KEY=value`;**不进 git**,构建时生成);
2. 把 `app/assets/apps.example.json` 复制成 `app/assets/apps.json`
   (example 比 apps.json 新时才覆盖;**不进 git**);
3. aapt2 编译 res + link(带 `-A assets`)→ javac → d8 → zipalign → apksigner 签名,
   产物 `build/out/agent.apk`,并自动跑 apksigner verify 与 index.html 自检。

## 配置你电视上的应用(apps.json)

编辑 `app/assets/apps.example.json`(或直接改生成的 `app/assets/apps.json`)后重打:

```json
[
  {"name": "显示名", "pkg": "应用包名", "search": "首页搜索入口的文字", "submit": "搜索页提交按钮的文字"}
]
```

- `search`/`submit` 缺省按 `"搜索"` 处理。`search` 文字要求**精确**出现在首页导航上
  (按文字点击时精确匹配优先,不会误点「搜索历史」这类包含文字)。
- 想知道包名:电视上装好后 `adb shell pm list packages | grep 关键词`,或看 /api/status 的 topPkg。

不配置 apps.json 时:`/api/apps` 返回空数组,`/api/search`、`/api/launch` 报「apps.json 未配置」。

## 安装与首次使用

1. `adb install build/out/agent.apk`(或 U 盘安装),打开「电视Agent」——状态页会显示电视 IP;
2. 到 **设置 → 无障碍 → 已下载的应用 → 电视Agent** 开启服务(必须,否则所有操作返回未开启错误);
3. 手机连同一局域网,浏览器打开 `http://<TV_IP>:9999`。

## 安全提示

- 9999 端口**无鉴权**,仅限可信局域网使用;用完可在系统设置里强行停止服务。
- `key.txt`、`apps.json` 含敏感信息,已在 `.gitignore` 中排除,不要提交、不要外发 APK 前 check 一下。

## 已知边界

- 搜片流程依赖目标应用是标准 EditText + 可点文字按钮(TVBox 内核普遍满足);
  自绘键盘/焦点式搜索页的应用只能用「填入」半自动方式。
- `dispatchGesture` 合成触摸在无触摸屏电视上有效(Android 9+ 实测设备 Android 14)。
