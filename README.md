# Timbre iOS（免签版）

Android 版 [Timbre 听书](https://github.com/cq10086123/Timbre) 的 **iOS 独立实现**：书架、播放器、接口源、睡眠定时器都按同一套交互重写，播放内核换成苹果原生的 **AVPlayer**，产物是 **未签名 IPA**（免签，TrollStore / AltStore / Sideloadly / 轻松签都能装）。

> 与安卓仓库互不干扰：这是独立工程，不包含任何 Gradle 代码，也不会往安卓仓库推送。

## 功能

| 模块 | iOS 版状态 |
| --- | --- |
| 书架：正在播放中 / 全部书籍、网格与列表切换、排序、搜索 | ✅ |
| 本地导入：整本文件夹、单个音频文件，导入不挡播放 | ✅ |
| 元数据：标题 / 作者 / 内嵌封面，文件夹里的 cover.jpg 优先 | ✅ |
| 封面兜底：没有封面时按书名生成 | ✅ |
| 播放：原生 AVPlayer、后台播放、锁屏 / 控制中心 / 蓝牙耳机控制 | ✅ |
| 断点续播（精确到秒）、暂停自动回退、片头片尾跳过 | ✅ |
| 倍速 0.5x–3.0x、±N 秒快进快退、章节导航、书签 | ✅ |
| 睡眠定时器：定时 / 本集结束停止 / 渐弱淡出 / 延长 15 分钟 | ✅ |
| 接口源 `.jdr`：文件导入、链接导入、启停、删除 | ✅ |
| 接口源沙箱：JavaScriptCore 执行源脚本，HTTP / MD5 / SHA / AES / ChaCha20 / SM4 加密桥 | ✅ |
| 在线书源搜索 → 章节列表 → 加入书架 → 播放直链（带 Referer / Cookie 头） | ✅ |
| WebDAV / NAS 目录浏览 | ⏳ 未实现（下一版） |
| Android Auto / 桌面小部件 | — iOS 上对应的是 CarPlay / 小组件，未实现 |

## 免签 IPA 怎么来

不需要 Mac，也不需要开发者账号：仓库自带 GitHub Actions 工作流，在 macOS runner 上编译并打包成**未签名 IPA**。

1. 把本目录推到你的新仓库（默认分支 `main`）。
2. 打开 **Actions → Build unsigned IPA → Run workflow**（手动触发）。
3. 跑完后在该次运行的 **Artifacts** 里下载 `Timbre-unsigned-ipa`。
4. 如果想直接发版：推一个 `v1.0.0` 这样的 tag，会自动把 IPA 挂到 Release 上。

工作流做的事：`xcodegen generate` → `xcodebuild … CODE_SIGNING_ALLOWED=NO` → 把 `.app` 塞进 `Payload/` 打成 `.ipa`。整个过程**不做任何签名**。

### 装到 iPhone

未签名 IPA 需要自签工具把「你的 Apple ID」签进去：

- **TrollStore**（iOS 14.0–16.6.1 / 17.0）：直接分享到 TrollStore 安装，永久有效，最省事。
- **Sideloadly / AltStore / SideStore**：电脑或手机上用免费 Apple ID 签名，7 天需重签。
- **轻松签 / ESign 等**：手机上直接导入 IPA 自签，注意甄别来源。

> 首次打开若提示「未受信任的开发者」，去 设置 → 通用 → VPN 与设备管理 里信任证书。

### 本地构建（有 Mac 时）

```bash
brew install xcodegen
xcodegen generate
open Timbre.xcodeproj      # 真机运行需要自己配签名
```

纯命令行出未签名 IPA：

```bash
xcodebuild -project Timbre.xcodeproj -scheme Timbre -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath build \
  CODE_SIGNING_ALLOWED=NO build
mkdir -p Payload && cp -R build/Build/Products/Release-iphoneos/Timbre.app Payload/
zip -qry Timbre-unsigned.ipa Payload
```

## 目录结构

```
Timbre-iOS/
├── project.yml                 # XcodeGen 工程描述（真正的工程文件由它生成）
├── Support/Info.plist          # 后台播放、文件共享、ATS 等
├── Timbre/
│   ├── App/                    # @main、AppState（书架/导入/接口源动作）
│   ├── Core/
│   │   ├── Models/             # Book / Chapter / Bookmark / Settings
│   │   ├── Persistence/        # JSON 持久化（library.json / settings.json）
│   │   ├── Library/            # 导入器、自然排序（含中文数字）
│   │   ├── Playback/           # AVPlayer 播放内核 + 锁屏/远程控制/睡眠定时器
│   │   └── Extensions/         # .jdr 解析、JS 沙箱、加密桥、HTTP 桥
│   ├── Features/               # 书架 / 详情 / 播放器 / 搜索 / 接口源 / 设置
│   ├── UI/                     # 主题色与格式化
│   └── Resources/              # AppIcon 等
└── .github/workflows/build-ipa.yml
```

本地数据全部在 App 沙盒 `Documents/` 下：`Books/`（音频）、`Covers/`、`Extensions/`、`library.json`。开启文件共享后，也可以用「文件」App 或 Finder 直接往里拖音频。

## 接口源（.jdr）说明

`.jdr` 的格式与安卓版完全一致：ZIP 里放 `manifest.json` + 源脚本，脚本调用 `registerSource({ id, search, chapters, audio })`。iOS 版把运行时从 QuickJS 换成了**系统自带的 JavaScriptCore**，宿主能力对齐：

- `http.get / http.post`（支持 `params / headers / body / json / form / timeoutMs`，返回 `{status, headers, body}`）
- `md5Hex`、`sha256Hex`、`hmacSha256Hex`
- `base64Encode/Decode/ToUtf8`、`hexToBytes`、`bytesToHex`、`utf8ToBytes`、`bytesToUtf8`
- `aesEcbEncryptB64`、`aesEcbDecrypt`、`aesGcmEncrypt/Decrypt`、`chacha20Poly1305Encrypt/Decrypt`、`sm4EcbEncrypt/Decrypt`
- `randomBytes`、`timestamp`、`timestampMs`、`urlEncode`、`urlDecode`、`log`

差异：**24 字节 nonce 的 XChaCha20 暂不支持**（iOS 版只有标准 ChaCha20-Poly1305），用到它的源会返回明确错误。

导入现成合集：

```
https://cdn.jsdelivr.net/gh/cq10086123/Timbre@main/samples/Timbre.jdr
```

在「接口源 → 从链接导入」里粘贴即可。

## 已知限制

- iOS 沙盒机制决定了「整机文件夹监听」不存在，导入 = 把文件**拷贝**一份进 App 沙盒（与安卓的直接引用不同，删 App 会一起删）。
- 首次导入 1000+ 集的整本大书时，时长在后台慢慢补齐，列表里会先显示 `--:--`。
- 免签安装的 App 无法使用推送、iCloud 同步；本工程本身也不需要这些能力（零账号、零追踪）。

## 许可

GPLv3，与上游 Timbre 一致，见 [LICENSE.md](LICENSE.md)。
