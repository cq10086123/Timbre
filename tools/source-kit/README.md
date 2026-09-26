# source-kit

jdr 书源包的构建与测试工具。零依赖，需要 Node 18+。

```bash
node bin/source-kit.js new my-sources        # 从模板创建包
node bin/source-kit.js test my-sources       # 离线冒烟测试（假 http）
node bin/source-kit.js test my-sources 关键词 # 同上，自定义搜索词
node bin/source-kit.js pack my-sources       # 校验 + 混淆 + 打包 my-sources.jdr
node bin/source-kit.js pack my-sources -o out.jdr
```

## 流程

1. `new`：复制 `templates/basic/`（含两个示例源：JSON API 源 + HTML 解析源）。
2. 编辑 `manifest.json`（packageId / sources）与 `bundle.js`（registerSource 实现）。
3. `test`：在 Node vm 里以与 App 相同的宿主 API（`registerSource`、`api.http.request`、`api.log`）加载 bundle，验证清单声明的源都已注册，并用假 http 数据走一遍 search → chapters → audio。
4. `pack`：混淆（去注释、压缩、局部标识符重命名、字符串数组 + 自解码引导）后写入 zip（UTF-8 文件名），即 `.jdr` 包。

真联网冒烟（可选）：`test` 默认走 fake http；需要真请求时可直接 `node bundle.js` 无 —— 请在 App 内验证，或临时把 `lib/harness.js` 里的 `fakeHttp` 换成 `hostHttpRequest`。

## 目录

```
bin/source-kit.js     CLI
lib/zip.js            零依赖 zip 写入（store + UTF-8 文件名 + CRC32）
lib/obfuscate.js      混淆器（字符串安全，不支持模板字符串内的复杂场景请自行展开）
lib/harness.js        Node 冒烟测试宿主
templates/basic/      manifest.json + bundle.js 双源模板
```

插件契约详见仓库根目录 `docs/jdr-plugin.md`。
