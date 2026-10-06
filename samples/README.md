# 接口源开发套件（.jdr）

## 🤖 AI 一键编写接口源

不想手写？把下面的提示词**整段复制**给任意 AI（ChatGPT / Claude / Gemini 等），并在末尾追加你目标站点的  
**接口信息**（抓包或接口文档：搜索/章节/音频三个接口的 URL、方法、参数、请求头、签名加密算法、响应 JSON 示例），  
AI 会直接产出 `manifest.json` + 源脚本两个文件，按 [三步上手](#三步上手) 打包导入即可。  
写好后建议先用下方「AI 一键验证」提示词实测一遍再导入。

<details>
<summary><b>点开复制完整提示词</b></summary>

```text
你是 Timbre 播放器「接口源」的开发专家。请根据我在末尾提供的接口信息，编写一个完整可用的
接口源，直接输出两个文件的完整内容（不要省略、不要留占位符）：
  1. manifest.json —— 包描述 + 源声明
  2. 源脚本 xxx.js

【运行环境】
脚本运行在播放器内置的 QuickJS 沙箱：支持现代 JS 语法（async/await、箭头函数、模板字符串），
但没有 DOM、fetch、XMLHttpRequest、setTimeout；网络只能用下面的 http 桥，其他能力只用
下面列出的沙箱全局函数。

【脚本结构】
;(function () {
  'use strict';
  registerSource({
    id: '源id',                     // 必须与 manifest 里 sources[].id 完全一致
    async search(params) { ... },    // 搜索
    async chapters(params) { ... },  // 章节
    async audio(params) { ... },     // 音频直链
  });
})();
任一阶段失败都 throw new Error('中文原因')，会显示在界面上；调试输出用 log(...)。

【三阶段契约】
- search 入参 { keyword, page, limit }
  返回 [{ id: '字符串', bookTitle, bookImage?, bookAnchor?, bookDesc?, count?, heat?, ...自定义字段 }]
- chapters 入参 { bookId, page, size }（已合并 search 返回的自定义字段）
  返回 [{ chapter_id: '字符串', title, order?, duration?, ...自定义字段 }]
- audio 入参 { bookId, chapterId }（已合并 chapters 返回的自定义字段）
  返回 'https://....mp3'
    或 { url: 'https://...' }
    或 { url: 'https://...', headers: { Referer: '...', 'User-Agent': '...' } }
  第三种用于防盗链 CDN 站点：这些头由播放器在拉流时自动携带，脚本不要自己下载音频。
- 跨阶段传值：把下一阶段需要的字段（内部专辑 id、章节序号等）直接放进返回对象，会自动
  透传到下一阶段的 params；透传缓存只在进程内，脚本要做兜底（拿不到就重新请求）。

【沙箱全局 API】
- 网络：await http.get(url, { params, headers, timeoutMs })
        await http.post(url, { params, body, json, headers, timeoutMs })
  返回 { status, headers, body }。params 自动 URL 编码拼 query；json 自动序列化并设
  Content-Type；body 传字符串或 Uint8Array；响应自动解 gzip；响应 headers 键全小写
  （多个 set-cookie 被用 ", " 拼成一个值）；不要手动设 Accept-Encoding。
  超时由脚本自定：App 不限阶段总时长，每个请求默认连接 15s/读取 30s，
  可用 timeoutMs（毫秒，整个请求的总时长）按需放宽或收紧；慢源重试多个地址时注意
  给单个请求设小的 timeoutMs，失败总时长自己算好。
- 摘要：md5Hex(s)、sha256Hex(s)、hmacSha256Hex(key, s)
- 编码：base64Encode / base64Decode / base64ToUtf8、hexToBytes、bytesToHex、
  utf8ToBytes、bytesToUtf8、urlEncode、urlDecode
- 加密：aesEcbEncryptB64(明文, 密钥) / aesEcbDecryptB64（PKCS#7 + base64，密钥传字符串
  或 Uint8Array）；aesGcmEncrypt(key, nonce, data) / aesGcmDecrypt（GCM 返回拼好 ct||tag
  的 Uint8Array）；chacha20Poly1305Encrypt / Decrypt（nonce 12 字节 = ChaCha20，
  24 字节 = XChaCha20）；sm4EcbEncrypt / sm4EcbDecrypt（GB/T 标准 SM4）
- 杂项：timestamp()（秒）、timestampMs()（毫秒）、randomBytes(n)（Uint8Array）、log(...)
- 没有定时器：需要限速/延时时，用 timestampMs() 忙等实现 sleep。

【manifest.json 格式】
{
  "id": "com.example.包名",      // 小写字母/数字开头，仅 a-z 0-9 . _ -，全局唯一
  "name": "包显示名",
  "version": "1.0.0",
  "author": "作者",
  "description": "一句话描述",
  "allowInsecure": false,         // 仅源站证书链损坏时设 true
  "sources": [{
    "id": "源id",                 // 全局唯一，与脚本 registerSource({id}) 一致
    "name": "搜索页 chip 显示名",
    "script": "xxx.js",           // 包内脚本文件名
    "capabilities": ["search", "chapters", "audio"]
  }]
}

【编写要求】
1. 严格按我提供的接口信息实现：参数拼接顺序、加密签名算法、时间戳单位（秒/毫秒）
   都不要改动。响应字段名必须以我粘贴的真实响应 JSON 为准：哪类接口缺响应示例，
   先向我要；我确实给不出时，才允许按常见命名做宽兜底，并在注释里标注这是假设。
2. 每个阶段先检查 resp.status，非 2xx 抛出带状态码的中文错误；解析前用
   log(resp.body.slice(0, 200)) 输出响应片段便于排障。
3. Web 类站点带浏览器 User-Agent；需要 Referer / Cookie 的按接口信息带上；站点有 Cookie
   风控时，先请求一次首页收割 Set-Cookie 再调接口。
4. 输出代码前，先列出三个阶段各自依赖的响应字段路径清单（例如
   search ← data.bookData[] 的 id/bookTitle；audio ← 根层 src），便于我人工核对。
5. 只输出两个文件的完整代码（用注释或文件名标注哪个是哪个），不要输出解释性文字。

========== 接口信息（抓包/文档资料粘贴在下面）==========

（在这里粘贴：站点首页地址、搜索/章节/音频接口的完整 URL、请求方法与参数、
请求头、签名或加密算法说明；每个接口再附一条抓包到的完整响应 JSON（Response 原文）。
响应示例是字段解析的唯一依据——只给接口不给返回值，AI 只能按常见命名猜，多半会对不上。
确实给不出响应时，写完务必用「验证提示词」实测一遍。）
```

</details>

## 🧪 AI 一键验证接口源

写好的源**导入前先实测一遍**——尤其当你当初只给了接口、没给响应 JSON 时，AI 写出的解析  
多半对不上真实字段（表现为搜索 0 条、章节空、播放报错）。把下面的提示词复制给**能执行代码的 AI**  
（Claude Code / ZCode / 带 Code Interpreter 的 ChatGPT 等；纯聊天 AI 只能做静态检查，实测不了），  
附上你的脚本全文和一个测试关键词，它会搭一个沙箱测试桩真调接口、跑完三阶段、定位字段错位并修复。  
修完重新打包导入即可。

<details>
<summary><b>点开复制完整提示词</b></summary>

```text
你是 Timbre 播放器「接口源」的调试专家。我给你一个接口源脚本，请在真实接口上
验证它能否跑通，找出问题并修复，最后输出修复后的完整脚本。
前提：你必须能实际运行代码（编写并执行 Node 脚本）；如果没有执行环境，就只做
第一步的静态检查，并明确告诉我"无法实测"。

【我将提供】
1. 源脚本全文（可能附带 manifest.json）
2. 一个测试用的搜索关键词
3. （可选）接口抓包/文档资料

【第一步：静态契约检查】
- manifest.sources[].id 必须与脚本里 registerSource({ id }) 完全一致
- search / chapters / audio 三个 async 函数齐全；失败路径 throw new Error
- 只使用沙箱提供的全局函数（即第二步测试桩要模拟的那些：http、log、urlEncode、
  timestamp、md5Hex、aesEcb… 等；沙箱里没有 fetch、setTimeout、DOM）
- 阶段入参：search={keyword,page,limit}；chapters={bookId,page,size,+search自定义字段}；
  audio={bookId,chapterId,+chapters自定义字段}
- 阶段出参：search→[{id:'字符串',bookTitle,...}]；chapters→[{chapter_id:'字符串',title,...}]；
  audio→'http...' 字符串 或 {url} 或 {url,headers}
先列出发现的全部契约问题，修掉再进入实测。

【第二步：真实接口实测（Node 测试桩）】
写一个临时 .mjs 测试桩（Node 18+，内建 fetch，无需装依赖），对齐 Timbre 沙箱行为：
- globalThis.registerSource = (s) => { src = s }
- globalThis.log = (...a) => console.log('[log]', a.join(' '))
- globalThis.urlEncode/urlDecode = encodeURIComponent/decodeURIComponent
- globalThis.timestamp/timestampMs = 秒/毫秒时间戳
- globalThis.http = { get(url,opts), post(url,opts) }：用 fetch 发真实请求
  （opts.headers 原样带上），返回 { status, headers, body }：headers 收集为
  "键全小写、同名值用 ', ' 拼接"的普通对象，body 为响应原文文本；
  若脚本用到 opts.params/json/body，按 Timbre 语义实现（params 拼 query 并 URL 编码；
  json 序列化为请求体并设 Content-Type）
- 脚本若还用到其他沙箱全局（md5Hex/sha256Hex/hmacSha256Hex/base64Encode/Decode/
  hexToBytes/bytesToHex/utf8ToBytes/bytesToUtf8/aesEcb*/aesGcm*/chacha20*/sm4*/
  randomBytes），用 node:crypto 等价实现为同名全局
然后 import 加载脚本，按完整链路执行并打印每步结果：
① search({keyword, page:1, limit:20}) → 命中数量 + 前 3 本的 id/bookTitle
② 取第一本 → chapters({bookId, page:1, size:30}) → 章节数 + 前 3 章的 chapter_id/title
③ 取第一章 → audio({bookId, chapterId}) → 直链
④ 对直链发 HEAD（带脚本设置的 User-Agent）→ 确认 2xx 且 content-type 是音频
任何一步抛错、结果为空或字段缺失，都算未通过。

【第三步：定位与修复】
- 最常见的病根是"响应字段名对不上"：把测试桩里 log 出来的真实响应 JSON 与脚本的
  解析逻辑逐一对照，修改字段候选/兜底链去适配真实字段；保持宽兜底，不要删其他分支
- 签名/加密对不上时，核对拼接顺序、时间戳单位（秒/毫秒）、JSON 是否紧凑格式
- 只改与失败相关的逻辑；每处修改先用一句话说明原因再动手

【最终输出】
1. 结论：通过 / 未通过，附各阶段实测数据（命中 X 本、Y 章、直链 HTTP 状态）
2. 问题清单：每条 = 现象 → 原因 → 改动
3. 修复后的完整脚本（不要省略任何行）；若改了 manifest.json 也一并给出
4. 提醒我：改完要重新打包再导入——直接用下方「AI 一键打包 .jdr」提示词，或
   python scripts/pack_jdr.py 源目录 输出.jdr，或把目录"里面的文件"（不是目录本身）
   压成 zip 改后缀 .jdr

========== 源脚本（和 manifest.json，如有）==========
（粘贴在这里）

========== 测试关键词 ==========
（例如：三体）
```

</details>

## 📦 AI 一键打包 .jdr

最后一步——不会压 zip、懒得装 Python？把下面的提示词给 AI：**能执行代码的 AI**（ChatGPT 代码  
解释器 / Claude Code / ZCode 等）会直接产出可下载的 `.jdr` 文件；**纯聊天 AI** 打不出二进制包  
（ZIP 含 CRC32 校验和，凭空生成必然损坏，提示词里已明令禁止它硬造），会退而求其次——给你  
打包好的全部文件 + 一条你系统专属的一键命令（Windows 双击即用的 .bat / macOS 一行 zip 命令，  
均为系统自带、零安装），运行一下就得到 `.jdr`。

至此闭环齐了：🤖 [编写](#-ai-一键编写接口源) → 🧪 [验证](#-ai-一键验证接口源) → 📦 打包 → [导入](#三步上手)。

<details>
<summary><b>点开复制完整提示词</b></summary>

```text
你是 Timbre 播放器「接口源」打包助手。请把我提供的接口源（manifest.json + 脚本文件）
打包成一个可直接导入播放器的 .jdr 文件；如果我只给了接口信息而没有文件，你先按
规范补齐文件再打包。

【.jdr 是什么】
本质是 ZIP 改了后缀。包内根层必须直接是 manifest.json 和脚本文件——不能多套一层
目录，否则导入时报"包内缺少 manifest.json"。

【路线 A：你能执行代码或能产出文件】（ChatGPT 代码解释器 / Claude Code / ZCode 等）
1. 建一个干净目录，写入 manifest.json 和全部脚本，文件名与 manifest.sources[].script
   完全一致
2. 打包任选其一：Python 的 zipfile 模块（把目录"里面的文件"写入 zip 根层，arcname
   不带目录前缀）、系统 zip 命令、或语言自带的压缩库
3. 把生成的 .jdr 交给我下载，或写到我能取到的路径
4. 打包后自检：重新打开 zip，确认根层就是 manifest.json + 全部脚本（没有多套目录）、
   manifest.json 能正常 JSON 解析、每个 sources[].script 在包内都存在

【路线 B：你只能输出文本】（没有执行环境的聊天 AI）
你打不出二进制 zip（ZIP 含 CRC32 校验和与字节偏移，凭空生成必然损坏）——不要尝试
直接输出 .jdr 或它的 base64。改为给我两样东西：
1. 完整的 manifest.json 和每个脚本文件，逐个标注保存文件名
2. 针对我操作系统的"一键打包"产物（我只想要对应我系统的那一种）：
   - Windows：一个双击即可运行的 .bat（用 PowerShell 的 Compress-Archive 把该目录里的
     manifest.json 和脚本压成 zip——它会强制 .zip 后缀，压完重命名为 .jdr），或一条
     可直接粘贴进 PowerShell 窗口的等价命令
   - macOS / Linux：一行终端命令，例如 cd 进目录后 zip -X ../包名.jdr manifest.json *.js
     （系统自带 zip）
   并说明清楚：各文件分别存成什么名字、放进哪个文件夹、在哪运行、运行后去哪拿 .jdr

【打包校验规则（两条路线都必须执行）】
- 包 id：小写字母/数字开头，仅含 a-z 0-9 . _ -，2-64 位
- 源 id：全局唯一，且必须与脚本里 registerSource({ id }) 完全一致（打包前打开脚本核对）
- capabilities 至少包含脚本实际实现的阶段；version 用 1.0.0（以后同名包更新导入时必须更高）
- 多个源可合一包：sources 数组多项，每个 script 文件都要放进包里
- 仅当源站证书链损坏时加 "allowInsecure": true，其余字段保持默认

【我提供的内容】
（粘贴 manifest.json 和各脚本文件；或描述源站接口信息让我先生成再打包；
并注明你的操作系统：Windows / macOS / Linux）
```

</details>

---

这里是播放器「接口源」功能的示例与开发文档目录。一个**接口源** = 一个 `.jdr` 文件  
（本质是 ZIP），里面装着一份 `manifest.json`（接口信息）+ 一个或多个 `.js` 脚本  
（实现 搜索 / 获取章节 / 音频直链 三个阶段）。脚本由播放器内置的 QuickJS 沙箱在  
**设备本地**执行，请求直接发往各接口自己的服务器，与播放器内置的任何在线服务无关。

## 目录导航

| 文件                                                            | 说明                                                                  |
| ------------------------------------------------------------- | ------------------------------------------------------------------- |
| [extension-dev-guide.md](extension-dev-guide.md)              | **接口源开发指南**（主文档）：如何编写接口（含 Python→JS 对照、加密桥用法、防盗链头）、如何打包 .jdr、如何导入验证、常见坑 |
| [abts-bili/](abts-bili/)                                      | **完整真实示例**：B站有声源（Cookie 风控预热 + Wbi 签名 + 防盗链 `{url, headers}`）——Web API 类站点的参考模板 |
| [\_template.js](_template.js)                                 | **空白脚本模板**：复制它开始写你自己的接口，注释里有 manifest.json 的完整字段说明                  |
| [demo-source/](demo-source/)                                  | **最小示例包**：一个只依赖普通 HTTP 接口的完整源（manifest.json + demo.js），演示标准字段与三阶段结构 |
| [../scripts/pack\_jdr.py](../scripts/pack_jdr.py)             | **打包脚本**（Python 版）：把目录压成 .jdr                                       |
| [../scripts/pack\_jdr.main.kts](../scripts/pack_jdr.main.kts) | 打包脚本（Kotlin 版，二选一）                                                  |

## 三步上手

1. **写接口**：复制 `_template.js`（或直接以 [abts-bili/](abts-bili/) 为模板），实现  
   `search / chapters / audio` 三个 `async` 函数。  
   标准字段与三阶段的入参出参，见 [开发指南 · 契约对照](extension-dev-guide.md#一契约对照python--js)。
2. **配 manifest**：包 id 与源 id 的规则、多接口合一的写法，  
   见 [开发指南 · 打包](extension-dev-guide.md#四打包成-jdr) 与 [demo-source/manifest.json](demo-source/manifest.json)。
3. **打包导入**：`python scripts/pack_jdr.py 你的目录 输出.jdr`（或把目录压成 zip 改后缀），  
   播放器 **设置 → 接口源 → 导入 .jdr 文件 / 从链接导入**。

## 音频返回的三种形态（重要）

| 写法 | 用途 |
| --- | --- |
| `return 'https://...mp3'` | 普通CDN:无特殊请求头 |
| `return { url: 'https://...' }` | 等价于上一种 |
| `return { url: '...', headers: { Referer: '...' } }` | **防盗链CDN**(B站等):拉流时播放器自动带上头 |

遇到"搜索正常但播放 403"的站点，就是最后一种：参考 [abts-bili/abts.js](abts-bili/abts.js) 的  
audio 阶段，把站点要求的 Referer/UA/Cookie 放进 `headers` 一起返回，其余交给播放器。  
完整说明见 [开发指南 · 示例源C](extension-dev-guide.md#三五示例源-cb站有声web-api--wbi-签名--防盗链-referer-头)。

## 沙箱能力速览

脚本里可以直接用的全局函数（完整说明见指南）：

- 网络：`http.get(url, opts)` / `http.post(url, opts)` → `{status, headers, body}`
- 摘要：**`md5Hex`&#x20;**`sha256Hex` `hmacSha256Hex`
- 编码：`base64Encode/Decode/ToUtf8` `hexToBytes` `bytesToHex` `utf8ToBytes` `bytesToUtf8`
- 加密：`aesEcbEncryptB64/Decrypt` `aesGcmEncrypt/Decrypt`  
  `chacha20Poly1305Encrypt/Decrypt`（12 字节 nonce = ChaCha20，24 字节 = XChaCha20）  
  `sm4EcbEncrypt/Decrypt`
- 杂项：`randomBytes` `timestamp` `timestampMs` `urlEncode/Decode` `log`

## 注意事项

- 源 id（`sources[].id`）**全局唯一**且须与脚本里 `registerSource({ id })` 一致；  
  同一源 id 不能同时存在于两个已导入的包。
- 包 id（`manifest.id`）可以随意改（小写字母/数字/`._-`），同名包重复导入 = 更新。
- 两个源可以封装进**同一个 .jdr**（`sources` 数组多项、多个脚本文件），参考指南打包一节。
- 源站证书链损坏时，在 manifest 里加 `"allowInsecure": true`（只影响该包）。
