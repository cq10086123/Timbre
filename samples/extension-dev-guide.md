# 接口源开发指南

本文讲解如何把一个"搜索 / 章节 / 音频"三段式接口改写成 Timbre 的 JS 接口源，打包成  
`.jdr` 并导入播放器。文中用三个**脱敏/真实示例**演示常见技术形态：

- **示例源 A**：MD5 时间戳 token + AES-ECB 签名请求（最常见的签名类接口）
- **示例源 B**：guest 授权 + AES-GCM/XChaCha 加密载荷 + 纯算法设备指纹（加密类接口）
- **示例源 C**：B站有声（Cookie 风控预热 + Wbi 签名 + 防盗链 Referer 头）——  
  **完整可运行的源**在 [abts-bili/](abts-bili/) 目录，遇到 Web API 类站点直接抄它

配套文件：空白模板 [\_template.js](_template.js)、最小示例包 [demo-source/](demo-source/)。  
模块与管线的设计规范见 [../docs/plans/extension-sources.md](../docs/plans/extension-sources.md)。

---

## 一、契约对照：Python `parse(params)` ↔ JS `registerSource`

| Python 版（三段式脚本引擎）                            | Timbre JS 版                                         |
| -------------------------------------------- | --------------------------------------------------- |
| 每个阶段一个 `def parse(params):`                  | 一个脚本里写三个 `async` 函数：`search / chapters / audio`     |
| `params.get('keyword')`                      | `params.keyword`（`params` 是对象）                      |
| `requests.get(url, params=..., headers=...)` | `await http.get(url, {params, headers, timeoutMs})` |
| `requests.post(url, data=..., json=...)`     | `await http.post(url, {body, json, headers})`       |
| `return [{'id':..,'bookTitle':..}, ...]`     | `return [{id:.., bookTitle:..}, ...]`               |
| `print(...)` 调试                              | `log(...)`（Logcat 过滤 `jdr:` 可见）                     |
| 抛异常表示失败                                      | `throw new Error('原因')`                             |

**返回字段标准**：

| 阶段       | 必填                                    | 可选                                          | 透传的自定义字段               |
| -------- | ------------------------------------- | ------------------------------------------- | ---------------------- |
| search   | `id`、`bookTitle`                      | `bookImage`、`bookAnchor`、`bookDesc`、`count` | 放进返回对象即可，自动带给 chapters |
| chapters | `chapter_id`、`title`                  | `order`、`duration`                          | 自动带给 audio             |
| audio    | 返回 `http(s)://...` 字符串（或 `{url:...}` 或 `{url:..., headers:{...}}`） | `headers`（拉流时要带的请求头，见下） |                        |

**audio 的三种返回形态**（按需选用，完全向后兼容）：

```js
return 'https://cdn.example.com/a.mp3';                          // ① 纯URL:无特殊头,与旧版行为一致
return { url: 'https://cdn.example.com/a.mp3' };                 // ② 对象:等价于①
return {                                                          // ③ 防盗链CDN:拉流要带请求头
  url: 'https://upos-sz-mirror08c.bilivideo.com/...m4s',
  headers: { Referer: 'https://www.bilibili.com/' },
};
```

形态③解决"CDN 防盗链"类站点（B站/部分网盘直链等）：这类CDN校验请求头（典型是  
`Referer`），播放器直接拉流会 403。脚本在 audio 里把头一起返回，播放器拉流时自动  
带上——**头只作用于该源该章的拉流请求，不会影响其他源**。头名/值会做 CRLF 注入过滤，  
非法头被静默丢弃。需要 UA/Cookie 的站点写法相同：`headers: { Referer: '...', 'User-Agent': '...', Cookie: '...' }`。

透传规则：search 结果的自定义字段会合并进 chapters 的 `params`；chapters 的自定义字段  
会合并进 audio 的 `params`。Python 版里"搜索结果塞自定义字段、章节/音频再取出来"的  
写法可以原样照搬。

**下一阶段拿到的 params**：

- `search`：`{ keyword, page, limit }`
- `chapters`：`{ bookId, page, size, ...search 自定义字段 }`
- `audio`：`{ bookId, chapterId, ...chapters 自定义字段 }`

---

## 二、示例源 A：MD5 token + AES-ECB 签名

这类接口的典型流程：请求带 `time` 秒级时间戳 + `md5(密钥前缀 + 时间戳)` 动态 token；  
音频接口要求把参数 JSON 用 AES-128-ECB 加密后 base64 放进 query。

### 1. 配置与请求头

```js
;(function () {
  var HOST = 'https://api.example-a.com';
  var TOKET = 'YOUR_TOKEN_PREFIX';
  var DEVICE_KEY = 'YOUR_16CHAR_KEY';          // AES-128 密钥（16 字节）
  var APP_VERSION = '2.6.5';
  var LIMIT = 30;

  function baseHeaders() {
    return {
      'User-Agent': 'Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36',
      Accept: 'application/json',
    };
  }
```

### 2. 搜索：动态 token

Python 原型：

```python
ts = int(time.time())
token = hashlib.md5(f"{TOKET}{ts}".encode()).hexdigest()
requests.get(f"{HOST}/search", params={'key': kw, 'time': ts, 'token': token, ...})
```

JS 对应：

```js
async search(params) {
  var ts = timestamp();                        // 秒级时间戳 = int(time.time())
  var resp = await http.get(HOST + '/search', {
    headers: baseHeaders(),
    params: { key: params.keyword, page: params.page, limit: LIMIT,
              time: ts, token: md5Hex(TOKET + ts), appVersion: APP_VERSION },
    timeoutMs: 15000,
  });
  if (resp.status !== 200) throw new Error('搜索失败: HTTP ' + resp.status);
  var data = JSON.parse(resp.body);            // requests 自动解 JSON，这里要手动
  var result = [];
  for (var i = 0; i < data.list.length; i++) {
    var item = data.list[i];
    result.push({
      id: String(item.id),
      bookTitle: item.name,
      bookImage: item.cover || '',
      bookAnchor: item.author || '',
      bookDesc: item.intro || '',
      count: item.tracks || 0,
    });
  }
  return result;
}
```

要点：`md5Hex(字符串)` 直接可用；`params` 里的 query 会自动 URL 编码（等价 requests）。

### 3. 章节

同构：请求书籍的章节列表，解析后返回 `chapter_id / title / order`，并把后续需要的  
自定义字段（如原始章节号）塞回去透传给 audio。

### 4. 音频：AES-ECB 加密请求体

Python 原型：

```python
inner_md5 = hashlib.md5(f"{book_id}-{chapter_id}.mp3".encode()).hexdigest()
inner_sign = aes_ecb_b64(f"{APP_VERSION}-{ts}-{inner_md5}", DEVICE_KEY)
body = aes_ecb_b64(json.dumps({"bookID":..., "chapterID":...}, separators=(",",":")), DEVICE_KEY)
```

JS 对应：

```js
var ts = timestamp();
var innerMd5 = md5Hex(bookId + '-' + chapterId + '.mp3');
var innerSign = aesEcbEncryptB64(APP_VERSION + '-' + ts + '-' + innerMd5, DEVICE_KEY);
var encrypted = aesEcbEncryptB64(JSON.stringify({
  bookID: bookId, chapterID: chapterId, time: ts, sign: innerSign,
}), DEVICE_KEY);
var resp = await http.get(HOST + '/audio', {
  params: { encrypted: encrypted, time: String(ts) },
});
var data = JSON.parse(resp.body);
if (data && data.data && data.data.src) return data.data.src;
throw new Error('未找到音频URL');
```

要点：

- `aesEcbEncryptB64(明文, 密钥)` = PKCS#7 填充 + AES-ECB + base64；密钥传字符串  
  （按 UTF-8 取字节）或 `Uint8Array` 均可。
- Python `json.dumps(separators=(",",":"))` 的紧凑格式 = JS `JSON.stringify` 默认输出，  
  **不要手动拼 JSON 字符串**。

---

## 三、示例源 B：guest 授权 + 加密载荷 + 设备指纹

这类接口的典型流程：请求体和响应体都要加解密（请求用 AES-GCM 封包，响应用  
XChaCha20-Poly1305 解包）；首次调用需要用纯算法生成的设备指纹换取访客凭证；  
凭证过期（401/403）自动刷新重试。

### 1. 载荷加解密

```js
// 请求封包：[0x02] + 12字节随机nonce + reverse(GCM密文+tag) 的 hex
function encryptPayload(plaintextBytes) {
  var nonce = randomBytes(12);
  var cipherTag = aesGcmEncrypt(KEY, nonce, plaintextBytes);  // 返回 ct||tag
  return bytesToHex(concatBytes([Uint8Array.of(2), nonce, reverseBytes(cipherTag)]));
}

// 响应解包：第1字节是版本，[1..25) 是 24 字节 nonce（24 字节 → 自动走 XChaCha20）
function decryptPayload(hex) {
  var payload = hexToBytes(hex);
  var nonce = payload.subarray(1, 25);
  var body = payload.subarray(25);
  if (payload[0] === 2) body = reverseBytes(body);
  return chacha20Poly1305Decrypt(KEY, nonce,
    concatBytes([body.subarray(0, body.length - 16), body.subarray(body.length - 16)]));
}
function decryptJson(hex) { return JSON.parse(bytesToUtf8(decryptPayload(hex))); }
```

要点：`aesGcmEncrypt(KEY, nonce, data)` 的密钥/nonce 收 `Uint8Array`，返回拼好  
`ct||tag` 的 `Uint8Array`；`chacha20Poly1305Decrypt` 识别 12 字节（ChaCha20）与  
24 字节（XChaCha20）nonce；字节工具用 `hexToBytes/bytesToHex/utf8ToBytes/bytesToUtf8`。

### 2. 设备指纹（非标准 SM4 变体）

有些 app 用**自有魔改分组加密**生成设备指纹（S 盒轮函数像 SM4，但密钥扩展与状态轮转  
被改过）。这类算法**必须在脚本里忠实复现原实现**，不能用宿主的 `sm4EcbEncrypt`  
（那是 GB/T 标准 SM4，给正常接口用的）。照抄原 Python/参考实现的三段即可：

- SBOX / 轮常数表**原样照抄**（逆向算法常有实现差异，以"能跑通的原脚本"为准）；
- `struct.pack/unpack("<I")` 换成手写的 `le32()`（小端取 4 字节）与逐字节写入；
- 时间戳字符串（如 `yyyy/MM/dd HH:mm:ss`）用 `new Date()` 手工拼接。

### 3. guest 授权与凭证缓存

```js
var credCache = null;
async function getCredentials(force) {
  if (!credCache || force) credCache = await refreshCredentials();
  return credCache;
}

async function refreshCredentials() {
  var dfp = genDfp();                                   // 纯算法指纹，每次可新生成
  var resp = await http.post(BASE + '/auth/guest', {
    headers: { 'User-Agent': UA, Cookie: dfp, ... },
    body: encryptPayload(utf8ToBytes('{}')),
    timeoutMs: 15000,
  });
  if (resp.status !== 200) throw new Error('授权失败: HTTP ' + resp.status);
  var token = decryptJson(JSON.parse(resp.body).payload).auth_token;
  var session = parseSessionCookie(resp.headers['set-cookie']);  // 响应头键都是小写
  return { token: token, session: session, dfp: dfp };
}
```

要点：Python `requests` 自动解 gzip，`http` 桥同样自动处理——**不要**手动设  
`Accept-Encoding` 头。

### 4. 401/403 自动重试 + 参数反查

```js
async function postEncrypted(url, bodyBytes) {
  var cred = await getCredentials();
  var resp = await http.post(url, { headers: authHeaders(cred), body: encryptPayload(bodyBytes) });
  if (resp.status === 401 || resp.status === 403) {
    cred = await getCredentials(true);                  // 凭证失效：刷新后重试一次
    resp = await http.post(url, { headers: authHeaders(cred), body: encryptPayload(bodyBytes) });
  }
  if (resp.status !== 200) throw new Error(url + ' 失败: HTTP ' + resp.status);
  return resp;
}

// 播放接口若只认"集序号"而非章节 id，就用 chapters 阶段透传来的 order；
// 没有时再拉一次章节列表反查（逻辑同原脚本的 _chapter_id_to_idx）。
```

---

## 三·五、示例源 C：B站有声（Web API + Wbi 签名 + 防盗链 Referer 头）

**完整可运行的参考实现在 [abts-bili/](abts-bili/) 目录**（与哔哩听书APP同源接口），这里讲清它  
用到的三个通用技术点，遇到同类站点照抄套路即可。

### 1. 会话预热：先"种 Cookie"再调接口

不少站点（B站尤甚）用 Cookie 风控：没有 `buvid3/buvid4` 这类设备 Cookie，接口直接  
412。做法是首次请求前先访问一次主页拿 Set-Cookie，把要紧的头收割进本地状态，之后  
每个请求带上：

```js
var cookies = {};                       // 模块级缓存,进程存活期内复用
function harvestCookies(headers) {      // set-cookie 会被 http 桥用 ", " 拼成一个字符串
  var sc = headers && headers['set-cookie']; if (!sc) return;
  ['buvid3', 'buvid4'].forEach(function (n) {
    var m = sc.match(new RegExp('(?:^|[,;\\s])' + n + '=([^;,\\s]+)'));
    if (m) cookies[n] = m[1];
  });
}
async function ensureSession() {
  if (cookies.buvid3 && cookies.buvid4) return;
  var r1 = await http.get('https://www.bilibili.com/', { headers: { 'User-Agent': UA } });
  harvestCookies(r1.headers);
  var r2 = await http.get('https://api.bilibili.com/x/frontend/finger/spi', { headers: baseHeaders() });
  var j = JSON.parse(r2.body);
  if (j.code === 0 && j.data) { cookies.buvid3 = j.data.b_3; cookies.buvid4 = j.data.b_4; }
}
```

要点：**请求必须带浏览器 UA**（SDK 默认 UA 会 412）；连续请求间留 ≥300ms 间隔，防止触发风控。

### 2. Wbi 签名：参数排序 + md5

B站的搜索/取流接口要求 `w_rid`/`wts` 两个签名参数（公开算法，见 bilibili-API-collect）：
从 `/x/web-interface/nav` 拿 `img_key/sub_key` → 按固定重排表取 32 位 `mixin_key` →  
参数里加 `wts`（秒级时间戳）、排序、剔除 `!'()*` → `w_rid = md5(排序后query + mixin_key)`。
沙箱自带 `md5Hex`，几十行搞定，完整实现在 `abts-bili/abts.js` 的 `wbiSign()`。

### 3. 防盗链 CDN：audio 返回 `{url, headers}`（本示例的核心）

B站音频直链（upos 镜像）拉流必须带 `Referer: https://www.bilibili.com/`，否则 403。  
这是 `{url, headers}` 契约的典型场景：

```js
async audio(params) {
  var body = await apiGet('/x/player/wbi/playurl', {          // Wbi 签名接口
    bvid: params.bookId, cid: params.chapterId,
    qn: '80', fnval: '4048', fnver: '0', fourk: '1',
    try_look: '1', gaia_source: 'prefer-ua',
  }, { useWbi: true });
  var tracks = body.data.dash.audio || [];
  var best = tracks.slice().sort(function (a, b) { return b.bandwidth - a.bandwidth; })[0];
  return {
    url: best.base_url,                                        // m4s 音频直链
    headers: { Referer: 'https://www.bilibili.com/' },         // 播放器拉流时自动带上
  };
}
```

要点：

- 头是**拉流时**由播放器附上的，不是脚本自己去下载音频——脚本只负责"告诉播放器要带什么"；
- 不需要头的站点返回纯字符串即可，两种写法可以共存（老脚本零改动）；
- 直链有时效（几小时），失效后播放器会自动让脚本重新解析一次，无需自己缓存。

---

## 四、打包成 .jdr

一个源包 = 一个目录（**一个包可以装多个接口**，`sources` 数组每项一个）：

```
my-source/
├── manifest.json      ← 包描述 + 源声明
├── source1.js         ← sources[].script 指向的脚本（可多个）
└── source2.js
```

单源 manifest：

```json
{
  "id": "com.example.mysource",
  "name": "我的接口源",
  "version": "1.0.0",
  "author": "you",
  "description": "一句话描述",
  "allowInsecure": false,
  "sources": [{ "id": "mysource", "name": "我的源", "script": "source1.js",
                "capabilities": ["search", "chapters", "audio"] }]
}
```

多接口合一（一个包两个源、两个脚本，导入后搜索页出现两个源 chip，可单独开关）：

```json
{
  "id": "com.example.collection",
  "name": "接口源合集",
  "version": "1.0.0",
  "sources": [
    { "id": "sourcea", "name": "源A", "script": "source1.js",
      "capabilities": ["search", "chapters", "audio"] },
    { "id": "sourceb", "name": "源B", "script": "source2.js",
      "capabilities": ["search", "chapters", "audio"] }
  ]
}
```

id 规则：

- 包 `id`：小写字母/数字开头，只能含 `a-z 0-9 . _ -`，2–64 位；随意起名，全局唯一。  
  同一包 id 再次导入 = **更新**（version 必须更高，保留各源启用开关）。
- 源 `id`（`sources[].id`）：**全局唯一**，即路由键 `jdr:<源id>`；必须与脚本里  
  `registerSource({ id })` 完全一致。同一源 id 不能同时存在于两个已导入的包。

打包三选一：

```bash
# 1) 仓库自带（python，需已安装 python）
python scripts/pack_jdr.py my-source my-source.jdr

# 2) 仓库自带（kotlin，需已安装 kotlin 运行器）
kotlin scripts/pack_jdr.main.kts my-source my-source.jdr

# 3) 手动（零环境）：把目录内文件用任意压缩工具压成 zip，后缀改为 .jdr
```

方式 3 的关键坑：**压缩"目录里面的文件"，不要压缩整个目录**。右键压缩文件夹会让  
zip 里多出一层（`my-source/manifest.json`），导入时报"包内缺少 manifest.json"。  
正确做法是进入目录全选文件后压缩，得到 zip 的根上直接就是 `manifest.json`。  
打包本身不需要开发环境——.jdr 就是普通 ZIP 改了后缀；导入时播放器会做全套校验，  
有问题会弹出具体原因（manifest 格式、源 id 冲突、脚本缺失等），改完重新打包即可。

## 五、导入与验证

1. 把 `.jdr` 传到手机 → 播放器 **设置 → 接口源 → 导入 .jdr 文件**；或把文件放到任意  
   http(s) 服务器上，用 **从链接导入** 填 URL。
2. 管理页能看到包名/版本/作者，每个源有启用开关（`jdr:源id` 就是路由键）。
3. 回到搜索页输入关键词——顶部出现你的源 chip，选中即用本地接口搜索；  
   点结果 → 章节弹窗 → 加入书架 → 播放。
4. 出错时信息以 Snackbar 显示；脚本里 `log(...)` 的内容在  
   `adb logcat | grep "jdr:"` 可见。

## 六、常见坑

| 现象                        | 原因                                                                   |
| ------------------------- | -------------------------------------------------------------------- |
| "脚本注册的 id(x) 与配置的(y) 不一致" | `registerSource({id})` 与 manifest 的 `sources[].id` 不一致               |
| "源 id xxx 已被其他扩展包占用"      | 源 id 全局唯一，换一个或先卸载旧包                                                  |
| "manifest.id 非法"          | 包 id 含大写/中文/空格/斜杠，只允许 `a-z 0-9 . _ -`                                |
| 搜索没结果也没报错                 | `resp.status` 没判断，或 JSON 层级取错（先 `log(resp.body.slice(0,200))`）       |
| 签名/加密对不上                  | 拼接顺序、时间戳是**秒**还是毫秒（`timestamp()` 秒 / `timestampMs()` 毫秒）、JSON 是否紧凑格式 |
| 章节透传字段拿不到                 | 确认上一阶段返回对象里确实带了该字段（透传缓存只在进程内，脚本要做兜底）                                 |
| 证书报错                      | 源站证书链损坏时在 manifest 加 `"allowInsecure": true`（只影响该包）                  |
| 播放时 403 / 搜索有结果但无法播放      | CDN 防盗链：audio 改返回 `{url, headers:{Referer:...}}`（见示例源 C），release 版看不了脚本日志，需 debug 版排障 |
| 接口 412 / 搜出来全是空           | 站点 Cookie 风控：先访问主页预热 Cookie（见示例源 C 第 1 点），并确认 UA 是浏览器 UA、请求间隔 ≥300ms  |
| 想看请求长什么样                  | 脚本里 `log(url)`、`log(resp.body.slice(0, 200))`，Logcat 过滤 `jdr:`（**debug 版 APK 才有**） |
