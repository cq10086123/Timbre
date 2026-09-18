# Timbre · WebDAV 在线听书方案

> 状态:**待评审**(未开始编码)
> 目标:对接 NAS 的 WebDAV,有声书放在 NAS 上**在线串流播放**,手机端不再复制整库文件,大幅减少本地空间占用;听书体验尽量与本地导入一致。

---

## 1. 背景与目标

现在 Timbre 只支持本地(SAF)导入:书的音频文件必须完整躺在手机里,一本长篇有声书动辄几个 GB,本地空间压力大。

引入 WebDAV 后:

| 目标 | 说明 |
|---|---|
| 在线听 | 音频留在 NAS,手机只按需拉取正在播放的数据段 |
| 省空间 | 本地只保留极小的元数据(章节表、封面图,单个 KB 级) |
| 体验一致 | 同一个书架、同一套播放器:进度记忆、章节、睡眠定时、跳过片头片尾、书签、Widget、媒体通知全部照旧 |
| 本地/远程共存 | 本地书不受任何影响,WebDAV 书混排在同一书架 |

**非目标(本期不做)**:第三方云盘协议(SMB/FTP/百度云等)、多设备同步、NAS 端转码。

---

## 2. 现状梳理(为什么改造点可以很小)

通读现有代码后,发现架构对 WebDAV 非常友好,关键结论:

1. **书的 ID 就是 URI 字符串**。`BookId` / `ChapterId`(`core/data/api`)本质是 `Uri.toString()`,本地书是 `content://...`。WebDAV 文件天然有一个稳定的 `https://nas.example.com/dav/书名/第01集.mp3`,**可以直接作为章节 ID 存进现有 Room 表**,数据层几乎零改动。

2. **播放链路是"URI 直通 ExoPlayer"**。`MediaItemProvider` 把 `chapter.id.toUri()` 作为 `MediaItem.sourceUri` 交给 ExoPlayer;`PlaybackModule.mediaSourceFactory` 用的是 `DefaultDataSource.Factory(context)`。media3 的 `DefaultDataSource` 本身就支持 http(s) + Range 请求(seek、切章都是天然的范围请求)。**只需把 DataSource 工厂换成"能按 URI 注入 WebDAV 认证头"的版本**,流式播放即通。

3. **扫描/导入链路建立在 `CachedDocumentFile` 抽象上**(`core/documentfile`)。它已有 SAF 实现(`RealCachedDocumentFile`)和 `java.io.File` 实现。给接口加一个 **WebDAV 实现**(`PROPFIND` 列目录、返回 `name/length/lastModified/uri`),`MediaScanner`、`ChapterParser`、`BookParser` 的全部逻辑——自然排序、分批入库、边导边听——**原样复用**。

4. **增量扫描天然可用**。`ChapterParser.parseChapter` 用 `fileLastModified + fileSize` 判断章节是否需要重新分析。WebDAV 的 `PROPFIND` 响应恰好带 `getcontentlength` 和 `getlastmodified`,重扫一次的成本只是目录列表请求,**不会重复下载音频**。

5. **元数据分析底层也是 media3 DataSource**。`MediaAnalyzer` 的 MP4/M4B 快路径(`Mp4BoxInput`,只读 `moov` 头、跳过音频负载)和兜底路径(`MetadataRetriever`)都通过 `DefaultDataSource` 读数据——换成认证工厂后,**远程文件可以只下载几百 KB 的头部分析出时长/标签/章节**,不用下载整本。

6. INTERNET 权限已声明(`app/src/main/AndroidManifest.xml`),无需新增权限。

7. 顺手要修的一处:播放器 `setWakeMode(C.WAKE_MODE_LOCAL)`(`PlaybackModule`)需要改为 `WAKE_MODE_NETWORK`,流式播放灭屏才不会断流。

---

## 3. 总体设计

```
┌────────────────────────── 手机 ──────────────────────────┐
│                                                          │
│  features/webdav (新)      服务器管理 + 远程浏览选书 UI    │
│        │ 注册"NAS 上的书"                                    │
│        ▼                                                 │
│  core/webdav (新)                                        │
│   ├─ WebDavClient        PROPFIND / GET(Range) / Basic 认证 │
│   ├─ WebDavServerStore   服务器配置(DataStore+Keystore 加密)│
│   ├─ WebDavFolders       已注册的远程书/库根 → 展开成书列表   │
│   ├─ WebDavDocumentFile  实现 CachedDocumentFile(目录树抽象)│
│   └─ AuthDataSource      media3 DataSource,按 URI 注入认证  │
│        │                    │                           │
│        ▼                    ▼                           │
│  现有扫描链路 ──────────▶ 现有播放链路(ExoPlayer)          │
│  MediaScanner/ChapterParser   MediaItemProvider/Player    │
│  (几乎不动)                    (换 DataSource 工厂即可)     │
└──────────────────────────┬───────────────────────────────┘
                           │ HTTP(S) + Basic Auth + Range
                           ▼
                     NAS 的 WebDAV 服务
                     (群晖/威联通/Alist/Nextcloud …)
```

核心思路一句话:**把 NAS 当成"另一种存放位置的文件夹"接进现有书架模型,把 ExoPlayer 的数据源换成"认识 WebDAV 认证的数据源"。**

### 3.1 新模块

| 模块 | 职责 |
|---|---|
| `core/webdav`(新) | WebDAV 客户端、配置存储、`CachedDocumentFile` 实现、认证 DataSource、文件夹注册(`WebDavFolders`) |
| `features/webdav`(新) | 服务器增删改查 UI、远程目录浏览/选书 UI |

符合仓库规则:可复用服务进 `core`,界面进 `features`,`core` 不依赖 `features`。用 `./scripts/new_module.main.kts` 创建注册。

### 3.2 依赖策略:手写 WebDAV 客户端,零新依赖

`gradle/libs.versions.toml` 里已有 OkHttp 5.5(`okhttp-coroutines`)。WebDAV 用到的命令很少:

- `PROPFIND`(Depth: 1)列目录 + 解析 `207 Multi-Status` XML(`XmlPullParser`,Android 自带)
- `GET` + `Range` 拉数据(实际音频数据由 ExoPlayer 自己拉,客户端只负责列目录)
- `Basic` 认证头

手写约 200 行,完全可控,不需要引入 sardine-android(那要加 JitPack 仓库,违背当前 `settings.gradle.kts` 只用 google+mavenCentral 的约定)。测试用 OkHttp 自带的 `mockwebserver`(加一条 test 依赖)。

---

## 4. 详细设计

### 4.1 配置存储与安全

```kotlin
// core/webdav
@Serializable data class WebDavServer(
  val id: String,              // uuid
  val name: String,            // "我的群晖"
  val baseUrl: String,         // https://nas.example.com:5006
  val username: String,
  val password: ByteArray,     // Keystore 加密后的密文
  val trustAllCertificates: Boolean = false,
)

@Serializable data class WebDavBookSource(
  val serverId: String,
  val remotePath: String,      // /dav/有声书/三体
  val mode: Mode,              // SingleBook | LibraryRoot
  val displayName: String,
)
```

- 两个 DataStore(Kotlinx Serialization,沿用 `StoreModule`/`VoiceDataStoreFactory` 现有模式)。
- **密码用 Android Keystore(AES/GCM)加密后落盘**,不进日志;minSdk 28 直接可用,无需新增依赖。
- `trustAllCertificates` 默认关;开启时仅在访问该服务器时使用宽松 SSL(自签名证书常见于 NAS),UI 上红字提示风险。
- 纯 `http://`(无 TLS)允许用于局域网,但添加时提示"明文传输,建议启用 HTTPS"。

### 4.2 文件抽象:WebDavDocumentFile

`WebDavDocumentFile : CachedDocumentFile`,挂在现有的工厂分发机制后面:

- `CachedDocumentFileFactory` 的绑定改为**按 scheme 分发**:`http/https` → WebDAV 实现(按 URL 反查所属服务器、带上认证),其余 → 原 SAF 实现;`file://` → `FileBasedDocumentFile`。
- `children` = `PROPFIND Depth:1`;`length/lastModified` 直接来自列表结果。
- **目录列表会话级缓存**(内存,TTL 约 30 秒):扫描时 `walk()` 一棵树不会对同一目录反复发请求;同一目录下的多个文件共享一次列表。
- 约束沿用现状:`children` 是同步属性,但扫描全程跑在 `Dispatchers.IO`,且现有 `MediaScanInitializer` → `MediaScanTrigger` → `MediaScanner` 的调度、`PlaybackIoGate`(导入给播放让路)和"同时只分析一个文件"的信号量,**对远程同样成立,防止把 NAS 或弱网打爆**。

### 4.3 书架接入:注册模式

注册入口在"添加内容"流程,新增「WebDAV」按钮 → 选服务器 → 远程浏览目录 → 二选一:

| 模式 | 行为 | 对应本地概念 |
|---|---|---|
| **作为一本书添加** | 选中的文件夹(或单个音频文件)= 一本书 | 现有书架模式(`SingleFolder`/`SingleFile`) |
| **作为书库根添加** | 选中文件夹的**每个子文件夹 = 一本书**(扫描时动态展开,NAS 上加新书自动出现) | 类似原 `Root` 模式 / `LegacyFolderExpander` 的展开思路 |

接入点收敛在 `AudiobookFoldersImpl`(core/data/impl):

- `all()`:在现有本地文件夹基础上,合并 `WebDavFolders` 的远程书(库根在此展开成书列表)。
- `removeBookRegistration(bookId)`:现在用 `DocumentsContract` 处理,对 `http` URI 直接放行即可;补充一条:若 bookId 命中某个 WebDAV 注册,从 `WebDavBookSource` 中移除注册。这样**书架长按删除书的现有流程(`DeleteBookViewModel`)不用改一行**——删除只移除注册和本地元数据,永远不碰 NAS 文件。
- `hasAnyFolders()` 把远程书也算上(引导页逻辑)。

扫描触发:沿用现有机制(App 启动扫描 + 添加/删除书触发)。为避免每次冷启动都白打一轮网络请求:

- 未变化的章节靠 `lastModified+fileSize` 跳过(只有目录列表流量);
- WebDAV 的自动扫描增加开关(默认:**仅 Wi-Fi 自动刷新**,流量网络下只手动/进书时刷新);
- 远程书在本地的 DB 记录(`book_content`/`chapters2`)**不因断网消失**,只是不刷新;断网时照常显示书架(封面是本地缓存的小图),点开播放时提示无法连接。

### 4.4 播放链路(改动最小、风险最高的一环,重点保障)

1. **认证 DataSource**:`AuthDataSource` 包装 `DefaultDataSource`——`open(dataSpec)` 时若 URI 属于某台已配置服务器,则在 `DataSpec.httpRequestHeaders` 注入 `Authorization: Basic ...`(预置认证,绝大多数 NAS WebDAV 支持)与 `User-Agent`,其余 URI 原样透传。
   - `DefaultHttpDataSource` 参数:连接超时 10s / 读取超时 30s、允许跨协议重定向(处理 http→https 跳转)。
2. **四处注入同一个工厂**(全走 DI,替换 `DefaultDataSource.Factory(context)`):
   - `PlaybackModule.mediaSourceFactory`(播放)
   - `Mp4BoxInput.create`(MP4/M4B 头解析)
   - `MediaAnalyzer.mediaSourceFactory`(兜底时长/标签分析)
   - `CoverExtractor.retrieveMetadata`(内嵌封面提取)
3. `PlaybackModule.player`:`setWakeMode(C.WAKE_MODE_LOCAL)` → `WAKE_MODE_NETWORK`(播放缓冲期间自动持有 Wi-Fi 锁)。
4. 缓冲策略先用 ExoPlayer 默认值,实测弱网不理想再调 `DefaultLoadControl`(预留参数,不预先复杂化)。
5. 切章 = 新 URI = 新请求,行为与本地切章一致;进度保存、跳过片头片尾、倍速、音量增益等全部与 URI 来源无关,自动生效。

### 4.5 格式支持矩阵(在线模式)

| 格式 | 在线播放 | 在线分析(时长/章节/标签) | 说明 |
|---|---|---|---|
| M4B / M4A / MP4 | ✅ | ✅ 快路径,只读头部,流量极小 | **最推荐** |
| MP3(带 Xing/VBRI 或 CBR) | ✅ | ✅ 范围请求估算时长 | 常规有声书 MP3 没问题 |
| MP3(VBR 无索引) | ✅ | ⚠️ 时长可能偏差 | 已知限制;播放中会以实际时长校正 |
| OGG / OGA / OPUS / FLAC / WAV | ✅ | ✅ 只读头部 | |
| MKA / MKV(Matroska) | ✅ ExoPlayer 原生支持流式 | ⚠️ 一期不解析远程章节(见已知限制) | 播放正常,章节标记暂缺 |

NAS 端要求:WebDAV 服务支持 `Range` 部分 GET(标准 WebDAV 语义,飞牛 fnOS、群晖、威联通、Alist/OpenList、Nextcloud、dufs 等主流实现都支持,**不绑定任何 NAS 品牌,只要说标准 WebDAV 就能接**)。首次添加书时做一次探测,不支持则在 UI 明确提示该服务器不适合在线听。

### 4.6 封面

复用现有 `CoverScanner` 优先级,远程同样成立:

1. 音频同目录下的图片文件(`PROPFIND` 列表即可发现,下载一张小图,本地保存);
2. 音频内嵌封面(APIC/cover atom,走 4.4 注入后的分析通道);
3. 都没有 → 现有的按书名绘制封面兜底。

封面无论哪种来源都落成本地小文件,书架/通知/Widget 的图片路径不变,`ImageFileProvider` 不需要动。

### 4.7 UI

1. **设置页**新增「WebDAV」入口:
   - 服务器列表(名称、地址、在线状态);
   - 添加/编辑对话框:名称、地址、账号、密码、「信任自签名证书」开关;「测试连接」按钮(发一次 PROPFIND 验证 401/网络/证书);
   - 全局偏好:仅 Wi-Fi 自动刷新(默认开)。
2. **添加内容**流程:现有 SAF 选文件夹旁边加「WebDAV」按钮 → 选服务器 → 远程目录浏览器(面包屑 + 目录列表,交互风格复用 `folderPicker`)→ 选模式 → 立即触发扫描,沿用现有"边导边听"进度展示。
3. **书架**:远程书卡片加一个小云朵角标(可关),其余(分组、搜索、排序)不变。
4. **删除书**:文案明确"仅从书架移除,不会删除 NAS 上的文件"。

### 4.8 错误处理

统一在 WebDAV 层把异常翻译成用户能懂的文案(strings 模块):

| 场景 | 表现 |
|---|---|
| 无法解析主机 / 连接超时 | 「无法连接到 NAS,请检查网络与地址」 |
| 401 | 「用户名或密码错误」 |
| 404 | 「文件可能已被移动或删除,刷新书架试试」 |
| 证书错误 | 「证书校验失败,可在服务器设置中允许自签名证书」 |
| 播放中断网 | ExoPlayer 错误回调 → 播放页 Snackbar 提示,进度已保存,恢复网络后从断点继续 |
| 服务器不支持 Range | 添加书时即提示,并阻止注册 |

断网时书架正常可浏览(元数据在本地 DB),章节表/封面齐备,只是播放器起不来——错误提示清晰即可,不做复杂的"可用性状态机"。

### 4.9 服务器地址变更迁移(NAS 换 IP / 换域名的保命设计)

`BookId`/`ChapterId` 是完整 URL,IP 一变书就"失联"。对策:编辑服务器地址保存时,若域名/端口/前缀变化,自动做一次 **DB 前缀重写迁移**(Room 事务内):`book_content.id`、`chapters2.id`、`current_chapter`、`bookmarks` 里匹配旧前缀的 URL 全部替换为新前缀。听书进度、书签无损。日常建议用户填 DDNS 域名,此迁移作为兜底。

---

## 5. 缓存与带宽(二期)

- **播放缓存**:media3 `SimpleCache` + `CacheDataSource` 叠在 `AuthDataSource` 外层,存 `cacheDir`(系统空间紧张时可回收),**LRU 上限默认 1GB,设置里可调(关/512M/1G/2G)**。收益:重听/回跳秒开、切回上一章不再重新下载、弱网体验明显变好。缓存只加速,不改变"在线"本质,不会悄悄吃满存储。
- 带宽参考:128kbps 的 M4B ≈ **57MB/小时**,只下当前播放段,对比整本复制(动辄数 GB)是数量级的下降。
- 目录列表缓存见 4.2。

---

## 6. 已知限制与风险

| 风险/限制 | 影响 | 对策 |
|---|---|---|
| 远程 MKA/MKV 章节不解析(现有解析器基于文件描述符,不支持 http) | 章节标记缺失,播放正常 | 一期接受;二期给 jebml 解析器实现一个基于 Range 的 HTTP Seekable 数据源即可补齐 |
| VBR MP3 无索引时 HTTP 下时长估算不准 | 进度条/总时长略偏 | 播放中自动校正;文档建议 M4B/带索引 MP3 |
| Basic 认证 + 纯 http 在公网明文暴露密码 | 安全 | UI 提示;建议 HTTPS;密码 Keystore 加密存储;日志脱敏 |
| 非 Basic 认证的服务器(Digest 等,少数) | 无法登录 | 一期支持 Basic(标准 WebDAV 服务端的事实标准);遇到再按 401 响应头补 Digest |
| NAS 被扫描并发打满 | 卡顿 | 沿用"分析并发=1"+ 目录缓存;播放单流 |
| 局域网 IP 变动 | 书失联 | 4.9 迁移 + 建议用域名 |

**隐私说明**:连接完全发生在手机 ↔ 你自己的 NAS 之间,无任何第三方中转;`PRIVACY.md` 补一段说明。

---

## 7. 测试计划

- **core/webdav 单测**:OkHttp `MockWebServer` 模拟 PROPFIND/GET/Range/401/证书错误,覆盖客户端、`WebDavDocumentFile`、`AuthDataSource` 头注入、Keystore 加解密(robolectric)。
- **扫描回归**:现有 `ChapterParser`/`MediaScanner` 测试模式不变,用 WebDAV fake 验证展开、增量跳过、删除注册。
- **迁移测试**:4.9 前缀重写的 Room 迁移单测(沿用 `Migration*Test` 模式)。
- **UI 测试**:`features/webdav` ViewModel 用 Molecule + Turbine(仓库既有模式)。
- **真机矩阵**:飞牛 fnOS(或手头任意 WebDAV 服务端)为主 + MockWebServer 回归;飞行模式断网、切 Wi-Fi/流量、灭屏长播、后台被杀恢复。

---

## 8. 实施计划

**一期(MVP,先跑通核心体验)**
1. `core/webdav` 骨架:客户端 + 配置存储 + Keystore 加密(+单测)
2. `WebDavDocumentFile` + 工厂分发 + `AudiobookFoldersImpl` 合并远程书(注册/展开/删除)
3. 播放链路:四处 DataSource 工厂注入 + `WAKE_MODE_NETWORK`
4. `features/webdav` UI:设置页服务器管理、添加流程远程浏览、strings
5. 错误文案、Range 探测、断网表现打磨

**二期**
6. `SimpleCache` 播放缓存(可调上限,默认 1GB LRU)
7. 远程 MKA/MKV 章节解析(HTTP Seekable 数据源)
8. 服务器地址变更 DB 迁移(换 IP/域名的保险);仅 Wi-Fi 自动刷新策略细化

**远期可选(暂不排期)**
9. 「整本下载到本地」按需离线——已确认以"在线 + 播放缓存"为准,此项仅在将来有明确需求时再评估

**涉及文件清单(概览)**:`settings.gradle.kts`(+2 模块)、`gradle/libs.versions.toml`(+mockwebserver 测试依赖)、`core/webdav/*`(新)、`features/webdav/*`(新)、`core/playback/di/PlaybackModule.kt`、`core/scanner` 的 `MediaAnalyzer/Mp4BoxInput/CoverExtractor`(注入点)、`core/data/impl/.../AudiobookFoldersImpl.kt`、`core/documentfile`(工厂分发)、`features/settings`(入口)、`features/folderPicker`(添加入口)、`navigation`、`core/strings`、`PRIVACY.md`/`README.md`。

一期预估 3–5 个工作日,二期 2–3 天,三期 2 天。

---

## 9. 备选方案(为什么不这么做)

| 方案 | 否决/降级理由 |
|---|---|
| 写一个 DocumentsProvider 把 WebDAV 伪装成 SAF | 理论上复用最多,但实现成本高、认证/缓存/错误传播都要塞进 provider,调试黑盒;直接实现 `CachedDocumentFile` 接口更薄、更可控 |
| 引入 sardine-android | 要加 JitPack 仓库与传递依赖;需求面窄,手写 200 行零依赖更符合仓库现状 |
| 先整本下载再播 | 最稳但与"省空间"目标相反;降级为三期可选的离线功能 |

## 10. 决议(已确认)

| 问题 | 结论 |
|---|---|
| 服务端绑定 | **不绑定任何 NAS 品牌**,按标准 WebDAV 协议实现(Basic 认证 + Range);用户自行填地址/账号/密码 |
| 离线能力 | **在线 + 播放缓存(LRU 可调上限)即可**,不做整本下载(保留为远期可选) |
| 地址形态 | 用户自行配置(域名或 IP 皆可);地址变更的 DB 迁移作为保险保留在二期 |
| 认证 | Basic 预置认证;密码 Keystore 加密存储;支持自签名证书开关 |

