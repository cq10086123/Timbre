# JDR 示例包

三个可以直接 `pack` 的完整源码包，与 `docs/jdr-tutorial.md` 一一对应：

| 目录 | 场景 | 教程章节 |
|---|---|---|
| `demo-json/` | 站点有 JSON 接口（最常见） | 5.1 |
| `demo-html/` | 只有网页，正则解析 HTML | 5.2 |
| `demo-headers/` | 防盗链：播放页两段式 + Referer/Cookie 直链 | 5.3 |

快速试用：

```bash
node tools/source-kit/bin/source-kit.js test examples/demo-json   # 离线冒烟
node tools/source-kit/bin/source-kit.js pack examples/demo-json   # 打出 .jdr
```

把示例里的 `https://example.com/...` 换成你的目标站点即可。
