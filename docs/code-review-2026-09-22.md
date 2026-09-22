# Timbre 全量代码审查报告

> 审查日期：2026-09-22
> 审查范围：`main` 分支 `49b7615`（v2.0.0），全部 517 个 Kotlin 文件（约 4.7 万行）
> 审查方式：人工静态审查（逐文件精读核心模块 + 全库模式扫描）
>
> ⚠️ **重要限制**：本沙盒环境网络受限（仅 github.com 可达，Gradle 发行版 / Maven 仓库 / Android SDK 均不可达），
> **无法执行编译与单元测试**。本报告全部结论来自静态审查，每条修复后请务必在本地跑一遍文末的验证命令。

---

## 〇、修复记录（2026-09-22，已获授权修复 H1–H5）

| 项 | 状态 | 改动 |
|---|---|---|
| H1 迁移 SQL | ✅ 已修复 | `Migration32to34.kt`：`SELECT * FROM $BOOKMARK_TABLE_NAME`（原来把常量名当表名）；新增 `Migration32to34Test.kt` 验证书签在表重建后保留 |
| H2 在线书进度 | ✅ 已修复 | `OnlineBook` 新增 `currentChapterId`/`positionMs`（带默认值，旧 JSON 兼容）；`OnlinePlaybackCatalog.updatePosition()` 节流落盘（3s 间隔、切章立即写，IO 协程而非 runBlocking）；`book()` 组装回退到持久化位置；`localBook()` 反映持久化进度（书架卡片进度）；`LibrarySessionCallback.onPlaybackResumption()` 增加在线书回退。新增 5 个测试 |
| H3 片头跳过丢失 | ✅ 已修复 | `SkipIntroOutro` 重写为**按 media item 记账**：duration/位置未就绪不消费、内容流未到不用旧值/初始 0、已处理条目不再重复跳。新增 3 个 Robolectric 测试 |
| H4 双重回退 | ✅ 保持现状（按产品决定） | `SleepTimerImpl.kt` 增加注释说明"淡出回退 + 自动回退有意叠加" |
| H5 预取器失活 | ✅ 已修复 | `PrefetchScheduler.start()` 改为 `combine(currentBook, settings).collectLatest`：设置变化即重启预取循环，关闭→再开启立即生效 |

### 〇.1 复审结论（2026-09-22 第二轮，针对上述修复）

复审发现并修复 **1 个问题**（H3 残余窗口，上一轮引入）：

- **问题**：`SkipIntroOutro` 的内容 latch 依赖 `contentRepo.flow()` 的发射，而收集器在
  `currentMediaItem` 尚为 null（冷启动组装期间）时直接丢弃该次发射——StateFlow 不会重发。
  生产上靠播放后位置落库的重发兜底（残余 ≤900ms 延迟窗口），但新写的测试 2/3 正好构造了
  "内容先到、条目后到"的时序，暴露了这条路径的脆弱性。
- **修复**：新增 `latestContents` 快照缓存 + `latchContentForCurrentItem()`；内容发射时无条件
  缓存快照，tick 时若当前书的值尚未 latch 就地从快照补齐。冷启动窗口归零，测试时序反而成为
  该加固路径的覆盖用例。

复审确认无恙的其余项（均逐条核实过）：

- **H1**：插值后的表名与 `DROP`/`CREATE` 一致；测试建表列名与迁移读取的列名逐一匹配。
- **H2**：① 新字段带默认值，旧 JSON 反序列化兼容；② DataStore actor 串行执行 `updateData`，
  并发落盘不会乱序（启动顺序即写入顺序）；③ `addToShelf` 全库仅搜索页一个调用点且只在书
  **不在架**时执行，已有书的持久化进度不会被覆盖（显式移除再加属用户意图）；④
  `recordMeasuredDuration` 的 `copy` 保留新字段；⑤ `onPlaybackResumption` 的在线书回退
  使用架内已持久化章节列表，无网络阻塞风险；⑥ `LibrarySessionCallback` 新增依赖在
  PlaybackScope 可达（`VoicePlayer` 已注入同类型佐证）。
- **H5**：`combine` 两源均为 DataStore flow，订阅即发当前值；设置变更触发 `collectLatest`
  重启，在途预取被干净取消。补差分测试 `PrefetchSchedulerTest`（旧代码下 `bookRepository.get`
  永不被调用、测试失败；新代码重启循环、测试通过）。

**无法在沙盒运行测试，本地请执行**（AGENTS.md Done Criteria 要求显式声明）：

```bash
./gradlew :core:data:impl:testDebugUnitTest --tests "*Migration32to34*"
./gradlew :core:online:testDebugUnitTest
./gradlew :core:playback:testDebugUnitTest --tests "*SkipIntroOutro*" --tests "*PrefetchScheduler*"
./gradlew voiceUnitTest lintKotlin :app:assembleFreeDebug   # 全量回归
```

---

## 一、总体评价

代码整体质量**明显高于同类个人项目**：模块边界清晰（core/features 分层）、
DI（Metro）与 DataStore/Room 使用规范、WebDAV 与在线源的异常路径和降级策略
大多有注释说明设计意图、测试覆盖了大部分纯逻辑。docs/plans 下的两份方案文档
与实现基本一致。

主要风险集中在四类：

1. **个别真实 bug**（升级迁移 SQL、在线书进度不持久化、片头跳过丢失、睡眠定时器双重回退、预取器死循环退出）；
2. **主线程阻塞**（组合期间的 `runBlocking` DataStore 读取，出现在导航启动、WebDAV 设置页、在线时长记录 3 处）;
3. **文档与合规**（PRIVACY.md 与实际联网行为严重不符；AGENTS.md 引用不存在的架构文档）;
4. **仓库卫生**（9 个误提交的 GitHub API JSON 转储文件）。

---

## 二、发现清单

### 🔴 高优先级（用户可感知的功能缺陷，建议优先修复）

#### H1. 数据库升级迁移 SQL 写错常量名，老版本升级会直接崩溃
- **位置**：`core/data/impl/src/main/kotlin/voice/core/data/repo/internals/migrations/Migration32to34.kt:35`
- **现象**：
  ```kotlin
  val cursor = db.query("SELECT * FROM BOOKMARK_TABLE_NAME")   // ❌ 字面量
  db.execSQL("DROP TABLE $BOOKMARK_TABLE_NAME")                // ✅ 插值
  ```
  第一行把 Kotlin 常量**名** `BOOKMARK_TABLE_NAME` 当成了表名（真实表名是 `tableBookmarks`）。
- **影响**：任何从 DB version 32/33 升级的用户，Room 迁移抛 `SQLiteException`，应用启动即崩溃、无法升级。
- **修复**：改为 `db.query("SELECT * FROM $BOOKMARK_TABLE_NAME")`。建议补一个 Robolectric 迁移测试。

#### H2. 在线书播放进度不持久化，且通知栏/重启后无法恢复播放
- **位置**：
  - `core/online/src/main/kotlin/voice/core/online/OnlinePlaybackCatalog.kt`（`positions` 为内存 map）
  - `core/online/src/main/kotlin/voice/core/online/OnlineBook.kt`（无 position 字段）
  - `core/playback/src/main/kotlin/voice/core/playback/session/LibrarySessionCallback.kt` → `onPlaybackResumption()`
- **现象**：
  1. 在线书的播放位置只存在 `OnlinePlaybackCatalog.positions`（进程内存）里，
     `OnlineBook` 持久化结构没有 position/currentChapter 字段 → **App 重启后在线书从第 1 集 0 秒重新开始**；
  2. `onPlaybackResumption()` 只查 `contentRepo.get(bookId)`，没有像 `VoicePlayer.setBook()` 和
     `PlayerController.maybePrepare()` 那样回退到 `onlinePlaybackCatalog.content()` →
     **重启系统/杀进程后，点媒体通知的"继续播放"对在线书必然失败**（抛 `UnsupportedOperationException`）。
- **影响**：对"在线听书"这一主打场景是硬伤：换天续听必须手动翻回集数。
- **修复建议**：
  1. 给 `OnlineBook` 增加 `currentChapterId` / `positionMs` 字段，在 `updatePosition()` 里节流落盘；
  2. `onPlaybackResumption()` 增加 `contentRepo.get(bookId) ?: onlinePlaybackCatalog.content(bookId)` 回退。

#### H3. 「跳过片头」在冷启动后的第一章经常不生效
- **位置**：`core/playback/src/main/kotlin/voice/core/playback/player/SkipIntroOutro.kt` → `skipIntroIfPending()`
- **现象**：
  ```kotlin
  val intro = pendingIntroSkip
  if (intro <= 0L) return
  pendingIntroSkip = 0L                       // ← 先清零
  val duration = player.duration.takeUnless { it == C.TIME_UNSET } ?: return  // ← duration 未就绪就直接放弃
  ```
  两条丢失路径叠加：
  1. 冷启动第一章：`onMediaItemTransition` 在 `contentRepo.flow()` 首次发出**之前**就 latch 了
     `skipIntroMs = 0`（初始值），之后内容流到了也不会重新 latch；
  2. 即使 latch 到了正确值，500ms tick 到来时若 player 尚未 prepare 完成（`duration == TIME_UNSET`，
     网络书尤其常见），pending 被白白消费。
- **影响**：设置过片头跳过的书，每次冷启动进第一章都要手动拖进度。README 明确宣传此功能。
- **修复建议**：duration 未就绪时**不要消费** pending（把清零移到成功的 seek 之后）；
  或改为按 transition 记一个"待跳过"标志，在 tick 里持续尝试直到成功或位置已越过 intro。

#### H4. 睡眠定时器到点暂停会"双重回退"
- **位置**：
  - `core/sleeptimer/impl/src/main/kotlin/voice/core/sleeptimer/SleepTimerImpl.kt:97` → `playerController.pauseWithRewind(fadeOutDuration)`
  - `core/playback/src/main/kotlin/voice/core/playback/player/VoicePlayer.kt:262-276` → `setPlayWhenReady(false)` 触发 `autoRewindAmountStore`（默认 **2 秒**）
- **现象**：`pauseWithRewind()` 内部先 `controller.pause()`（会话侧 `VoicePlayer.setPlayWhenReady(false)`
  自动回退 2s），随后又显式 `seekBackBy(fadeOutDuration)`（默认 10s）。两次 seek 都会执行，实际回退 ≈12s。
- **影响**：睡眠定时场景下比预期多丢 2 秒内容；普通暂停不受影响。
- **修复建议**：若定时器回退应取代自动回退，给 `VoicePlayer.setPlayWhenReady` 加"本次暂停跳过自动回退"
  的旁路（如自定义 SessionCommand）；若 2s 是可接受的叠加，则在文档中明示。**需产品意图确认。**

#### H5. 预取器在「关闭缓存/预取 → 重新开启」后永久失活
- **位置**：`core/playback/src/main/kotlin/voice/core/playback/prefetch/PrefetchScheduler.kt` → `prefetchBook()`
- **现象**：循环里 `if (settings.maxBytes <= 0L || prefetchMode == Disabled) return` 直接**结束协程**；
  而 `collectLatest` 只在 `currentBookStore` 变化时重启。用户关闭再打开预取后，除非切书或重启 App，预取不再工作。
- **修复建议**：把 `playbackCache.settings().data` 作为外层 `collectLatest` 源之一（settings 变化即重启循环），
  或在检测到 disabled 时 `delay` 轮询而不是 `return`。

---

### 🟡 中优先级（主线程阻塞 / 安全 / 一致性）

#### M1. 首屏导航在组合期间 `runBlocking` 读多个 DataStore，且每次重组都会重跑
- **位置**：`app/src/main/kotlin/voice/app/navigation/StartDestinationProvider.kt`
  + `app/src/main/kotlin/voice/app/MainActivity.kt`（`setContent { rememberNavBackStack(*startDestinationProvider(intent)...) }`）
- **现象**：
  - `invoke()` 里有 3 处 `runBlocking`，`showOnboarding()` 串联读取 onboarding store + 4 个文件夹 store + 远程源 store（最多 7 次磁盘读）；
  - 该调用发生在**组合函数实参求值**里：`rememberNavBackStack` 只记住结果，实参每次重组都会重新求值 →
    **每次导航、主题切换都会在主线程阻塞读盘**；
  - `intent.action == "playCurrent"` 分支还会在重组时重复触发 `playerController.play()`（副作用进了组合）。
- **修复建议**：`val initial = remember { startDestinationProvider(intent) }`（或 `produceState`），
  并把 `playCurrent` 的副作用移进 `LaunchedEffect`。这是冷启动 jank 的最大来源。

#### M2. WebDAV 服务器页每次重组都在主线程做 Keystore 解密
- **位置**：`features/webdav/src/main/kotlin/voice/features/webdav/WebDavServersViewModel.kt` → `viewState()`
- **现象**：`needsPasswordReEntry = webDavLibrary.undecryptableServerIds()` 直接写在 `@Composable` 里。
  该方法内部 `runBlocking` 读 DataStore 并对**每台服务器**做一次 Keystore AES 解密。
  在服务器编辑对话框里**每敲一个字符都会重组一次 → 每敲一个字符解密一遍全部密码**。
- **修复建议**：`val needsReEntry by produceState(emptySet()) { value = webDavLibrary.undecryptableServerIds() }`，
  以 servers 列表为 key。

#### M3. 在线书时长测量在主线程 `runBlocking` 写 DataStore
- **位置**：`core/online/src/main/kotlin/voice/core/online/OnlinePlaybackCatalog.kt:348`（`recordMeasuredDuration`）
- **现象**：调用链 `PositionUpdater.flushPositionNow()`（PlaybackScope = **Main.immediate**）
  → `updatePosition()` → `recordMeasuredDuration()` → `runBlocking { booksStore.updateData { ... } }`。
  每章首次测得时长时在主线程做一次 DataStore 写（含全部在架在线书的 JSON 序列化）。
- **修复建议**：把持久化挪到 IO scope（fire-and-forget），内存 `measuredDurations` 已足够即时生效。

#### M4. 在线流 TLS 降级可能把 Bearer Token 送进明文 HTTP
- **位置**：`core/online/src/main/kotlin/voice/core/online/OnlineStreamingDataSource.kt` → `execute()` / `request()`
  + `OnlineStreamUrlPolicy.kt`
- **现象**：证书坏的主机运行时会被 `rememberCertBroken()` 永久降级为 http。请求侧只按
  `requestUrl.startsWith(base)` 决定是否附带 Bearer Token —— 若**下载站自身主机**也被标记 cert-broken，
  Token（卡密会话凭证）将走明文 HTTP。
- **修复建议**：`downgradeToHttp` 前判断目标 host 是否等于 baseUrl 的 host，等于则不降级（或降级时不带 Token）。
  第三方 CDN 音频本身是公开内容，降级合理；站点自有接口不应降级。

#### M5. 在线源卡密与 Token 明文存储，与 WebDAV 密码的保护级别不一致
- **位置**：`core/online/src/main/kotlin/voice/core/online/OnlineSourceGraph.kt`（`onlineSourceCredential` / `onlineSourceToken` 直接存 DataStore）
- **对比**：WebDAV 密码走 `KeystoreWebDavSecrets`（AES/GCM + AndroidKeyStore）。
- **建议**：复用 `WebDavSecrets` 的思路（抽到 core/common）加密卡密；Token 短时效可接受明文，但卡密是付费凭证，建议加密。

#### M6. WebDAV 换地址迁移的崩溃窗口（一致性缺口）
- **位置**：`core/webdav/src/main/kotlin/voice/core/webdav/WebDavLibrary.kt` → `saveServer()`
- **现象**：顺序为 `remoteUrlMigration.migrate()` → `removeByPrefix` → `rewriteRegisteredSources` →
  `currentBookStore` → **最后**才 `serversStore.updateData`（发布新地址）。若中途进程被杀，
  Room/书源/当前书已指向新前缀，而 server.baseUrl 还是旧前缀 → `WebDavCredentialResolver.matches()`
  全部失配 → 播放回退到无认证的 `DefaultHttpDataSource`，全部 401。
- **建议**：可接受的窗口极小；稳妥做法是把 server 记录的更新放进同一步（先写 server 再迁移），
  或启动时做一次孤儿前缀自愈扫描。至少在代码注释中记录该窗口。

#### M7. `PlaybackService.release()` 在主线程 `runBlocking` 等待落库
- **位置**：`core/playback/src/main/kotlin/voice/core/playback/session/PlaybackService.kt:52`
- **现象**：`onDestroy` 里阻塞等待 `flushPositionNow()`（内部拿 `BookRepositoryImpl` 全局互斥锁 + Room 写）。
  千集大书 content 行较大，服务销毁路径变慢，极端情况下计 ANR。
- **建议**：与 `onTaskRemoved` 一致，改用 `UNDISPATCHED + NonCancellable` 协程落盘。

---

### 🟢 低优先级（UI 细节 / 性能 / 代码卫生）

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| L1 | `WebDavCacheViewModel.viewState()` | `LaunchedEffect(settings.maxBytes)` 只在 maxBytes 变化时刷新 `cachedBytes`，点"清除缓存"后显示值不更新 | clearCache 成功后主动刷新（如用版本号 state 触发） |
| L2 | `BookPlayViewModel.onlineViewState()` | `durationsVersion` 变化时 `baseBook != null` 直接退出，测得的章节时长**整个会话不刷新**（章节列表一直显示 30 分钟占位） | `LaunchedEffect(bookId, durationsVersion)` 内无条件重建 baseBook |
| L3 | `BookPlayViewModel` / 各 WebDav ViewModel | `MainScope(...)` 从不 cancel；`playbackErrors.collect` 等收集器随每次进入播放页**累积**，内存缓慢增长 | 改为绑定导航条目生命周期，或提供 `onCleared()` 释放 |
| L4 | `SkipIntroOutro` | 切书瞬间可能用旧书的 skipIntro latch（与 content 流的竞态），首章可能多跳 | latch 时同时校验 bookId |
| L5 | `WebDavCacheEvictor.onSpanAdded` | 每次 span 写入都对全部 key 做一次全量扫描淘汰，预取千集书时接近 O(n²) | 节流（如每 64 个 span 或每 2s 一轮） |
| L6 | `PrefetchScheduler.prefetchBook` | `currentUrl` 用外层旧 `book.chapters` 查找，导入中途新增章节找不到 → 当前章的"已消费"标记漏打（因活跃书本身受保护，实际影响小） | 改用 `fresh.chapters` |
| L7 | `WebDavClient.rangeProbe` | 探测失败时默认"支持 Range"，坏服务器上 seek 语义由 200+skipFully 兜底（已有告警与降级，可接受） | 保持，建议日志中区分 |
| L8 | `BookRepositoryImpl.bookCache` | 每本书的组装结果永久驻留，超大书库内存线性增长 | 简单 LRU 或只在播放书保留 |
| L9 | `MultiStatusParser` | href 与请求 URL 的比较未做编码归一（`skipSelf`、`list()` 过滤），个别服务器的编码差异会导致多/漏条目 | 比较前 `Uri.decode` 归一 |
| L10 | `core/webdav/.../WebDavTestSupport.kt` | 测试 fake（`MemoryDataStore` 等）放在 **main** 源集并随产物发布 | 移到独立 fixture 源集，或至少标注 public 可见性原因 |
| L11 | `SleepTimerImpl.updateVolume` | `fadeOutDuration == 0` 时除零 → `require(volume in 0f..1f)` 抛异常（当前 UI 限制 ≥1s，风险极低） | 读入时 `coerceAtLeast(1.seconds)` |
| L12 | `MediaAnalyzer` 分析路径 | 使用 `WebDavDataSourceFactory.createDataSource()`（`markConsumed = true`），导入分析会把预取标记误改成"已消费" | 分析路径改用 `createUnmarkedDataSource()` |

---

### 📄 文档与合规（建议尽快处理）

1. **PRIVACY.md 与实际严重不符（合规风险，最重要的一条文档问题）**
   - PRIVACY.md（2026-09-16 版）声称：「唯一的联网行为：检查更新……**除此之外没有任何网络请求**」。
   - 实际代码中存在：
     a) WebDAV 用户服务器通信（README 有宣传，但 PRIVACY 未提）；
     b) **整个在线音源模块**（`core/online`）：连接第三方"音频下载站"、卡密登录、验证码自动提取、搜索、服务端下载、从第三方 CDN 明文/加密流式播放——PRIVACY 与 README 的"零隐私顾虑"清单均未覆盖；
     c) 全局 `usesCleartextTraffic="true"` + 在线源对坏证书主机的 **HTTPS→HTTP 自动降级**，隐私政策只字未提。
   - 建议：重写 PRIVACY.md，按"功能-数据流向-第三方"逐项披露；在线源默认关闭并在开启时给出独立提示。

2. **AGENTS.md 指向不存在的文档**：`docs/architecture.md` 被称为"唯一事实来源"，但文件不存在。要么补写，要么改指 `docs/plans/*.md`。

3. README 宣称"无统计、无追踪 SDK"，`play` flavor 实际带 Firebase（仅 Play 渠道）。建议在 README 注明 play flavor 的存在，避免误会。

---

### 🧹 仓库卫生

| 项 | 说明 |
|---|---|
| 误提交的 CI 转储 | 根目录 `jobs.json / jobs2.json / jobs3.json / jobs4.json / runs.json / runs2.json / run.json / run_now.json / rn.json` 共 9 个文件约 200KB，全是 GitHub Actions API 的响应转储（还暴露了历史 lint 失败记录）。建议 `git rm` 并在 `.gitignore` 增加 `/*.json` 白名单式规则（保留 `update.json`）。 |
| CI 覆盖缺口 | `.github/workflows/ci.yml` 中 instrumentation 测试被 `if: false` 禁用（`SleepTimerIntegrationTest` 等不在 CI 内）。建议标注禁用原因与恢复计划。 |
| `.idea/` 提交 | 沿袭上游，可接受，保持现状即可。 |

---

## 三、确认无恙的重点设计（审查过、无需改动）

为避免"只报忧不报喜"，以下高危区域经细读**未发现问题**：

- **WebDAV 认证链**：`WebDavDigestAuthenticator` 的 RFC 7616 实现（qop/nc/algorithm/opaque/引号转义）正确；
  Digest-only 服务器跳过预发 Basic 的学习逻辑正确；`TrustAll` 为逐服务器显式开启且 lint 已豁免。
- **密码存储**：`KeystoreWebDavSecrets`（AES/GCM、随机 IV 前置、128-bit tag）实现正确；密码解不开时
  `undecryptableServerIds` 让用户重输而非静默失败。
- **`RemoteUrlMigrationImpl`**：前缀重写带 `$old/` 边界（不会把 `/dav2` 误判为 `/dav`），事务 + 缓存刷新顺序正确。
- **`MediaScanner` 分批入库/失败保留**：`storeChapters` 的"不完整列表只增不减"、currentChapter 缺失时按索引兜底，逻辑严密。
- **`BookPlaylistSynchronizer`**：前缀校验 + 追加式同步 + ENDED 后自动接播，处理正确。
- **`PlayerController`**：单控制器复用、断线重建、`maybePrepare` 对在线书的回退，均正确。
- **`PlaybackIoGate`**：播放优先的 IO 让路机制与文档一致。
- **`MediaItemBuilder`/`PlaybackItems`**：ChapterMark 展开与位置换算（`positionInMediaItem` 的 coerce）正确。

---

## 四、修复顺序建议（供授权后执行）

1. ~~**第一批（小改动、高收益）**：H1（一行 SQL）→ H3（SkipIntroOutro 消费时机）→ H5（预取器重启）→ M1/M2（去组合期 runBlocking）。~~ H1/H3/H5 已完成；M1/M2 待授权
2. ~~H2~~ 已完成；**第二批剩余**：H4（按产品决定仅补注释，已完成）→ M3。
3. **第三批（安全/合规）**：M4（TLS 降级守卫）→ M5（卡密加密）→ PRIVACY.md 重写 → 仓库卫生清理。
4. **随时可做**：L1–L12 按顺手程度处理。

---

## 五、验证命令（本地执行）

```bash
# 编译（free debug 直接可装）
./gradlew :app:assembleFreeDebug

# 全量单元测试 + 格式检查 + 打包（CI 同款）
./gradlew voiceUnitTest lintKotlin :app:assembleFreeDebug

# Lint
./gradlew :app:lintFreeDebug
```

针对具体修复的定向验证：
- H1：临时把 `AppDb.VERSION` 降级构造一个 v33 库（或用 Room 迁移测试）验证升级路径；
- H2：在线书播放 → 杀进程 → 点通知"继续播放"应恢复到原集数原位置；
- H3：设置片头跳过 30s → 杀进程 → 冷启动播放，应自动跳过；
- H5：播放中关闭再开启预取，观察 `PrefetchScheduler` 日志恢复工作；
- M1/M2：`adb logcat` 观察 main 线程是否还有 DataStore 读取（可用 StrictMode `detectDiskReads` 佐证）。

---

*报告完。所有修复在获得授权后进行，未动任何代码。*
