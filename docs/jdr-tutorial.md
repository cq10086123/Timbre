# JDR 书源编写教程（从零到发布）

> 本教程面向**书源作者**。读完你就能独立编写、调试、打包、发布一个 JDR 书源包。
> API 速查见 `docs/jdr-plugin.md`；可直接运行的示例在 `examples/` 目录。

---

## 1. 一个 JDR 书源是什么

JDR 是一个 **zip 包**（后缀 `.jdr`），里面装着：

```
my-sources/
├── manifest.json   包清单（声明包名和里面有哪些源）
└── bundle.js       JavaScript 源码（实现了 1~N 个书源）
```

App 安装这个包后，会在本地用 QuickJS 引擎运行 `bundle.js`。你的代码负责三件事：

| 方法 | 作用 | 什么时候被调用 |
|---|---|---|
| `search(keyword)` | 按关键字搜书 | 用户在「搜索」里输入关键词 |
| `chapters(bookId)` | 拉取某本书的章节列表 | 用户打开章节列表 / 开始播放 / 手动刷新 |
| `audio(bookId, chapterId, chapterExtra)` | 解析某一章的**真实音频直链** | 播放该章、缓存该章、测时长 |

你**不需要**关心：播放器、进度保存、书签、倍速、缓存——这些 App 全部自己处理。搜索结果加入书架后，书的身份是 `jdr:{packageId}:{sourceId}:{bookId}`，跨包永不混淆。

---

## 2. 5 分钟跑通第一个源

```bash
# 1. 从模板创建包
node tools/source-kit/bin/source-kit.js new my-sources
cd my-sources

# 2. 离线冒烟测试（不需要真网站，App 会给你假数据）
node ../tools/source-kit/bin/source-kit.js test .
# 输出 registered: demo-json, demo-html ... smoke test passed

# 3. 打包（自动混淆 → my-sources.jdr）
node ../tools/source-kit/bin/source-kit.js pack .
```

把 `my-sources.jdr` 传到任意静态地址（GitHub Release / 对象存储 / 自建 nginx），App 里「设置 → 在线书源 → 书源插件」输入 URL 导入即可。

**开发循环**：改 `bundle.js` → `source-kit test .` → `pack` → App 里重新导入同 URL（相同 packageId 会原地更新，书架进度书签不丢）。

---

## 3. 运行环境：你的代码能用什么

`bundle.js` 在全局作用域执行一次，之后 App 通过 `registerSource` 注册的对象调用你的方法。

### 3.1 registerSource（必需）

```js
registerSource(
  { id: 'site-a', name: 'Site A' },   // meta：id 必须与 manifest.sources[].id 一致
  { search, chapters, audio }         // impl：三个方法，每个都可为 async
);
```

- `id` 只能是小写 `a-z0-9_-`；`name` 是搜索页 chip 上显示的名字。
- 一个包里 `registerSource` 可以调用多次 = 多个源，各自独立开关。
- **manifest.sources 里声明的每个 id 都必须被 register，否则安装失败**。

### 3.2 api.http.request（发请求）

```js
const res = await api.http.request({
  url: 'https://example.com/api/search?q=' + encodeURIComponent(keyword),
  method: 'GET',            // 可选，GET/POST/PUT/PATCH/DELETE
  headers: {                // 可选
    'User-Agent': 'Mozilla/5.0',
    'Referer': 'https://example.com/',
    'Cookie': 'token=abc',
  },
  body: 'a=1&b=2',          // 可选，POST/PUT/PATCH 时生效
});
// res = { status: 200, headers: {...}, body: '响应文本', url: '最终url' }
```

要点：
- **只支持 http/https**；返回的是**文本** body（JSON 自己 `JSON.parse`，HTML 自己正则）。
- 非 200 不会抛异常，`status` 自己判断。
- 网络错误会抛异常 → 用 `try/catch` 或 `.catch` 返回友好错误。
- 单次源调用（search/chapters/audio 之一）总时长上限 30 秒，超时会被打断。

### 3.3 api.log / console.log（调试）

```js
api.log('搜索关键词:', keyword);      // App 日志里能看到，带包名前缀
console.log(res.status, res.body);    // 等价
```

上限 200 条/次调用，超出丢弃。

### 3.4 没有 XHR / fetch / DOM / 定时器

QuickJS 里没有浏览器环境。所有网络走 `api.http.request`，不要 `setTimeout`（不保证可用），不要 `XMLHttpRequest`。

---

## 4. 三阶段数据契约（最重要的表）

### search(keyword, page) → 数组 或 {items, nextPage, hasMore}

```js
return [
  {
    id: '12345',              // 必填。源内唯一，用来查章节
    title: '凡人修仙传',       // 必填
    author: '忘语',            // 可选
    cover: 'https://.../x.jpg',// 可选，封面直链
    intro: '一个山村小子的修仙故事', // 可选
    extra: '12345',           // 可选！想回传什么就放什么，App 原样还给你
  },
  // ...
];
// 或分页形式：{ items: [...], nextPage: '2', hasMore: true }
```

- 返回数组或 `{items}` 都行；`hasMore/nextPage` 目前 App 首版 UI 只取第一页，字段先按协议返回（未来版本直接支持翻页）。

### chapters(bookId) → 数组（或 {items}）

```js
return [
  {
    id: '1001',            // 必填。源内唯一，audio 调用时会传回
    title: '第1集 二愣子',  // 必填
    durationSeconds: 1800, // 可选。能拿到就填，App 播放一次后会自动校正
    order: 1,              // 可选。不填按数组顺序
    extra: '1001',         // 可选。常用于存放音频页 id / 播放页路径
  },
];
```

- **章节顺序就是数组顺序**，App 用它生成播放队列。
- 章节数量大时一次全量返回即可（App 有缓存，切章节不重复请求）。

### audio(bookId, chapterId, chapterExtra) → 字符串 或 {url, headers, expiresAt}

最简单：直接返回直链字符串

```js
return 'https://cdn.example.com/audio/1001.mp3';
```

推荐：带请求头（防盗链站点必需）

```js
return {
  url: 'https://cdn.example.com/audio/1001.m4a',
  headers: {
    'Referer': 'https://example.com/',
    'User-Agent': 'Mozilla/5.0 (Linux; Android 13)',
    'Cookie': 'play_token=xyz',
  },
  expiresAt: Date.now() + 20 * 60 * 1000, // 可选：链接预计 20 分钟后过期
};
```

- `headers` 会原样用于**播放、缓存、测时长**的所有请求。
- 直链过期没关系：App 播放失败会自动重新调 `audio` 拿新链接。
- **不要**返回页面 URL，必须是真实可下载的音频地址（mp3/m4a/aac/...）。

### extra 的设计思想

`extra` 是你自己的私有通道：search 时把"以后要用的信息"塞进去，audio 时拿出来用，App 永远不看它的内容。常见用法：

- search 阶段把**详情页路径**放 `book.extra` → chapters 里先请求详情页再解析章节
- chapters 阶段把**音频页 id** 放 `chapter.extra` → audio 里直接用它拼播放页 URL，省一次查询

---

## 5. 完整示例

### 示例 1：JSON API 源（`examples/demo-json/`）

```js
function search(keyword) {
  return api.http.request({
    url: 'https://example.com/api/search?q=' + encodeURIComponent(keyword),
  }).then(function (res) {
    if (res.status !== 200) throw new Error('http ' + res.status);
    var data = JSON.parse(res.body);
    return (data.results || []).map(function (item) {
      return {
        id: String(item.id),
        title: item.title,
        author: item.author || '',
        cover: item.cover || '',
        intro: item.intro || '',
        extra: String(item.id),
      };
    });
  });
}

function chapters(bookId) {
  return api.http.request({
    url: 'https://example.com/api/book/' + encodeURIComponent(bookId) + '/chapters',
  }).then(function (res) {
    var data = JSON.parse(res.body);
    return (data.chapters || []).map(function (ch, i) {
      return {
        id: String(ch.id),
        title: ch.title,
        durationSeconds: ch.duration || 0,
        order: i + 1,
        extra: String(ch.id),
      };
    });
  });
}

function audio(bookId, chapterId, chapterExtra) {
  return api.http.request({
    url: 'https://example.com/api/play?book=' + encodeURIComponent(bookId) +
      '&chapter=' + encodeURIComponent(chapterExtra || chapterId),
  }).then(function (res) {
    var data = JSON.parse(res.body);
    return { url: data.url, headers: data.headers || {} };
  });
}

registerSource({ id: 'demo-json', name: 'Demo JSON Source' }, { search, chapters, audio });
```

### 示例 2：HTML 正则解析源（`examples/demo-html/`）

没有 JSON API 的网站，抓 HTML 用正则：

```js
function htmlSearch(keyword) {
  return api.http.request({
    url: 'https://example.com/search?q=' + encodeURIComponent(keyword),
  }).then(function (res) {
    var items = [];
    var re = /<a class="book" href="\/book\/(\d+)"[^>]*>([\s\S]*?)<\/a>/g;
    var m;
    while ((m = re.exec(res.body))) {
      items.push({
        id: m[1],
        title: m[2].replace(/<[^>]*>/g, '').trim(),  // 去掉内嵌标签
        extra: m[1],
      });
    }
    return items;
  });
}
```

技巧：
- 用 `while ((m = re.exec(body)))` 而不是 `match`，方便取捕获组；
- 标题先 `replace(/<[^>]*>/g, '')` 再 `trim()`；
- 列表页只有书名没有作者/简介时，`chapters(bookId)` 里先请求详情页补齐。

### 示例 3：防盗链音频（`examples/demo-headers/`）

很多音频站的直链必须带 Referer/Cookie，否则 403：

```js
function audio(bookId, chapterId, chapterExtra) {
  // 第一步：请求播放页
  return api.http.request({
    url: 'https://example.com/play/' + encodeURIComponent(chapterExtra),
    headers: { 'Referer': 'https://example.com/book/' + bookId },
  }).then(function (res) {
    // 第二步：从页面里抠出直链
    var m = /data-src="([^"]+\.m4a[^"]*)"/.exec(res.body);
    if (!m) throw new Error('audio link not found');
    // 第三步：直链要带同样的 Referer 才能下载
    return {
      url: m[1],
      headers: { 'Referer': 'https://example.com/' },
    };
  });
}
```

**判断是否需要 headers**：在电脑浏览器直接打开直链能播，不代表手机 App 能播（浏览器自动带 Referer）。最稳的做法是都带上。

---

## 6. 调试

### 6.1 离线冒烟（不开网络）

```bash
node tools/source-kit/bin/source-kit.js test examples/demo-json 关键词
```

- 会验证 manifest 声明的源全部被 register；
- 用假 http 数据走一遍 search → chapters → audio；
- 语法错误、漏方法、返回格式不对在这一步就暴露。

### 6.2 App 内排错

- 源管理页每个包下面会显示 **lastError**（脚本错误、没注册的源、加载失败原因）。
- `api.log` 输出进 App 日志（adb logcat 过滤包名 / 开发者选项里看日志）。
- 常见报错对照：

| lastError / 现象 | 原因 |
|---|---|
| `bundle did not register sources: xxx` | manifest 声明了 `xxx` 但代码没 register / id 拼错 |
| `source xx does not implement chapters` | impl 少了方法 |
| `search returned invalid json` | 你的方法返回了非 JSON 可序列化的东西（函数、循环引用） |
| `invalid items: Missing required...` | 书/章节缺 `id` 或 `title` |
| `source call timed out` | 单次调用超 30 秒；检查是否死循环、网站是否太慢 |
| 播放 403 | 直链缺 headers；或 headers 名写错 |

### 6.3 真网络调试

`source-kit test` 是离线的。要看真实响应，可以临时在 `audio` 里 `api.log(res.body.slice(0, 500))`，打真机日志；或先用 curl 验证接口返回结构再写解析。

---

## 7. 打包与发布

```bash
node tools/source-kit/bin/source-kit.js pack my-sources            # 生成 my-sources.jdr
node tools/source-kit/bin/source-kit.js pack my-sources -o out.jdr # 指定输出名
```

发布前检查清单：

- [ ] `manifest.json` 的 `packageId` 全小写、发布后**永不更改**（改了用户书架会变成两份）
- [ ] 更新版本时 `packageId` 不变、`version` 递增
- [ ] `source-kit test` 通过
- [ ] 搜索结果、章节、播放各验证过一次真站

托管：任意静态 URL（GitHub Release 附件、jsDelivr、对象存储）。用户导入后，同 URL 重新导入即是升级。

---

## 8. 规则与限制（务必阅读）

| 项目 | 限制 |
|---|---|
| 包大小 | ≤ 8 MB；单文件 ≤ 6 MB；条目数 ≤ 64 |
| packageId | `[a-z0-9][a-z0-9-]{0,63}`，发布后不可改 |
| source id | `[a-z0-9][a-z0-9_-]{0,63}` |
| 运行环境 | QuickJS（ES2023 大部分特性、模板字符串、Promise、async/await 可用）；无 DOM/XHR/定时器 |
| 网络 | 仅 http/https；单次源调用总时长 30 秒 |
| 日志 | 每次 API 调用最多 200 条 |
| 安全 | 你的代码在 App 本地沙箱运行；**不要**把用户数据发往第三方 |
| 混淆 | `pack` 会重命名标识符并加密字符串——你的原始注释会丢失，发布前保留好源码 |

---

## 9. 下一步

- 直接读 `examples/` 三个示例包的源码（每个都是完整可打包的）；
- 把示例改成你的目标站点，`test` → `pack` → 导入，就这么简单；
- 字段速查 / 包格式细节：`docs/jdr-plugin.md`。
