# jdr 插件：书源包格式与运行时 API

> 状态：已实现（简化版首期范围）。服务端书源保留，jdr 源是并行新增的本地书源类型。

## 1. 一个 .jdr 包是什么

`.jdr` 是一个普通 zip（仅 store 压缩亦可），最多 8 MB，包含：

```
manifest.json   包清单
bundle.js       混淆后的 JavaScript bundle（manifest.entry 指定，默认 bundle.js）
```

`manifest.json`：

```json
{
  "formatVersion": 1,
  "packageId": "my-sources",
  "name": "My Sources",
  "version": "1.0.0",
  "entry": "bundle.js",
  "sources": [
    { "id": "site-a", "name": "Site A" },
    { "id": "site-b", "name": "Site B" }
  ]
}
```

约束：

- `packageId`：`[a-z0-9][a-z0-9-]{0,63}`，包内唯一标识，更新时复用。
- `sources[].id`：`[a-z0-9][a-z0-9_-]{0,63}`，包内唯一；bundle 必须把清单声明的每个源都 `registerSource`，否则安装失败。
- 加载时的防护（见 `core/source/jdr/JdrLoader.kt`）：条目数 ≤ 64、单条目 ≤ 6 MB、条目名不得含 `..`/绝对路径/非法字符、重复条目拒绝、formatVersion 必须为 1。

## 2. bundle.js 运行环境

App 内置 QuickJS（quickjs-kt 1.0.15）。bundle 在全局作用域求值，可用的宿主 API：

| API | 说明 |
|---|---|
| `registerSource(meta, impl)` | 注册一个源。`meta = {id, name}`；`impl = {search, chapters, audio}`，三个都是普通或 `async` 函数。 |
| `api.http.request(options)` | 发起 HTTP 请求。`options = {url, method?, headers?, body?}`，返回 `Promise<{status, headers, body, url}>`，`body` 为 UTF-8 文本。仅支持 http/https。 |
| `api.log(...)` / `console.log(...)` | 写入应用日志（每包限 200 条）。 |

无 `XMLHttpRequest`、无 `localStorage`、无 `setTimeout`；需要网络就用 `api.http.request`。单次源调用（含其中所有 http 请求）总超时 30 秒。

## 3. 源方法契约

字段与服务器书源的三阶段结构保持一致（宽松解析：容器可以是数组或 `{items}` 包装）。

### search(keyword, page) → 书籍数组或 `{items, nextPage, hasMore}`

```js
{ id: '123',            // 源内书籍 id
  title: '书名',         // 必填
  author: '作者', cover: 'https://...', intro: '简介',
  extra: '123' }        // 可选：后续调用想拿回的不透明负载
```

### chapters(bookId) → 章节数组或 `{items}`

```js
{ id: 'c1',             // 源内章节 id，必填
  title: '第一章',       // 必填
  durationSeconds: 300, // 可选：已知时长
  order: 1,             // 可选：缺省按下标+1
  extra: '...'}         // 可选：audio 阶段回传
```

### audio(bookId, chapterId, chapterExtra) → url 字符串或 `{url, headers, expiresAt}`

```js
{ url: 'https://cdn.example.com/c1.mp3',
  headers: { Referer: 'https://example.com/' },  // 播放/缓存/探针都会带上
  expiresAt: 1735689600000 }                     // 可选：链接预期失效时间(ms)
```

`headers` 会透传给 ExoPlayer 数据源与离线缓存下载；若含 `Authorization`，App 不再追加服务器书源的 Bearer token。

## 4. 书籍身份与 URL

- 源在 App 内的路由名：`jdr:{packageId}:{sourceId}`（如 `jdr:my-sources:site-a`），搜索 chips 与服务器源并列展示，互不影响。
- 书籍 canonical id：`online://book/jdr:my-sources:site-a/{bookId}`，与服务器书共用书架、进度、书签、跳过片头片尾和缓存。
- 章节 id：`online://play?s=jdr:my-sources:site-a&b={bookId}&c={chapterId}`。

卸载/停用包不删除书架条目：重新导入同名 `packageId` 即恢复；跨包的书不会混淆（路由名含 packageId）。

## 5. 打包与混淆（source-kit）

```
tools/source-kit
├── bin/source-kit.js      CLI（new / test / pack）
├── lib/zip.js             零依赖 zip 写入（UTF-8 文件名 + CRC32）
├── lib/obfuscate.js       去注释压缩、局部标识符重命名、字符串数组+自解码引导
├── lib/harness.js         Node 冒烟测试宿主（与 App 宿主 API 同形）
└── templates/basic/       双源示例模板（JSON API 源 + HTML 解析源）
```

用法：

```bash
node tools/source-kit/bin/source-kit.js new my-sources
node tools/source-kit/bin/source-kit.js test my-sources [keyword]   # 离线假 http 冒烟
node tools/source-kit/bin/source-kit.js pack my-sources             # 生成 my-sources.jdr
```

混淆保证原始脚本不可直接阅读（自解码字符串表 + 重命名），不承诺抵抗专业逆向——与设计文档第 1.6 条一致。

## 6. 导入与管理（App 内）

设置 → 在线书源 → 「书源插件」：

- URL 导入：输入 `.jdr` 直链，下载（≤8MB）后校验安装。
- 文件导入：系统文件选择器选取 `.jdr`。
- 包级启停、单源启停、删除（删除不动书架/进度/书签/缓存）。
- 包加载失败（脚本错误、缺 registerSource）会在列表中显示 lastError，不影响其他包。
- 开机时 `RestoreJdrPackagesOnAppStart` 自动恢复已安装包。
