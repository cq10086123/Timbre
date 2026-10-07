<div align="center">

<img src="artwork/public/timbre/timbre-icon-512.png" width="96" alt="Timbre 图标" />

# Timbre · 听书

**免费开源的 Android 有声书播放器 —— 本地书、WebDAV、在线书源与自定义接口源，一个书架全搞定。**

[![Release](https://img.shields.io/github/v/release/cq10086123/Timbre?label=%E7%89%88%E6%9C%AC)](https://github.com/cq10086123/Timbre/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE.md)

</div>

<p align="center">
  <img alt="书架界面，多接口源角标与独立播放进度" width="27%" src="docs/screenshots/shot-shelf.png" />
  <img alt="播放界面，章节导航与倍速控制" width="27%" src="docs/screenshots/shot-player.png" />
  <img alt="接口源管理，导入与一键启停" width="27%" src="docs/screenshots/shot-extensions.png" />
</p>

Timbre（听书）是一款免费开源的 Android 有声书播放器，为中文听书场景深度优化：本地音频（MP3 / M4B / FLAC 等）直接导入，WebDAV / NAS 远程书库在线收听，还支持自定义 `.jdr` 接口源——搜索、章节、播放直链全部在本机完成。断点续播精确到秒，千集大书库依旧顺滑，无账号、无广告、无追踪。

> [!TIP]
> **只要你有接口，就能一键生成接口源**：把 [AI 提示词](samples/README.md#-ai-一键编写接口源)复制给任意 AI（ChatGPT / Claude / Gemini 等），附上抓包到的接口信息，AI 直接写好 `manifest.json` + 源脚本；接着还能让 AI [实测验证](samples/README.md#-ai-一键验证接口源)、[一键打包](samples/README.md#-ai-一键打包-jdr)成 `.jdr`。不会编程、零开发环境也能做源，三段提示词一路到导入。

## 功能特性

**播放体验**

- 断点续播精确到秒：每本书、每一集、每一秒都记得，暂停时自动回退几秒接上上下文
- 跳过片头片尾：每本书独立设置，片头每集只跳一次，最后一集完整播完
- 倍速播放、跳过静音、音量增益、章节导航、书签
- 睡眠定时器：渐弱淡出 / 章节结束停止 / 摇一摇续命 / 定时自动开启
- Android Auto、桌面小部件、蓝牙耳机控制

**书库管理**

- 支持主流格式：MP3 / M4B / M4A / OGG / OPUS / FLAC / WAV / MKV / WebM / 3GP
- 千集大书库流畅管理：自然排序（第 2 集排在第 10 集前）、自动分组、网格 / 列表切换、全局搜索
- 导入不挡播放：边导入边听，解析完第一集就能开播
- 封面自动就位：音频文件夹里的图片优先于内嵌封面，一张图都没有时按书名生成

**在线书源与 WebDAV**

- 在线书源：搜索即听，章节时长自动探测，播放成功后后台预热后续章节
- WebDAV / NAS：目录树直接浏览导入，播放边缓存、章节自动预取、WLAN 下自动刷新
- 远程封面自动识别：`cover.jpg / artwork.png` 优先于音频内嵌图

## 接口源（.jdr 扩展）

想听的书源不在列表里？导入 `.jdr` 接口源包即可扩展：搜索、章节、播放直链全部由内置 QuickJS 沙箱在手机上本地完成，请求从你自己的 IP 直连源站，不经任何中间服务器。内置 MD5 / SHA / AES / ChaCha20 / SM4 加密桥与 HTTP 桥，签名类、加密类接口都能写；一个包可封装多个接口源，按源一键启停、随时更新卸载。

**导入现成的接口源合集**：在 App 内选择「从链接导入」，粘贴这条直链：

```
https://raw.githubusercontent.com/cq10086123/Timbre/refs/heads/main/samples/Timbre.jdr
```

> ⚠️ 注意：部分接口源有请求限制，请勿频繁切换；部分接口支持缓存，因接口而异。

🙌 欢迎制作并分享你的接口源：[一键填写分享](https://github.com/cq10086123/Timbre/issues/new?template=1-source-share.yml) · [浏览大家分享的接口](https://github.com/cq10086123/Timbre/issues?q=label%3A%22source-share%22)。想自己做源？不用会编程——[接口源开发套件](samples/README.md)顶部有三段现成 AI 提示词：**编写 → 验证 → 打包**，复制给任意 AI，从你手上的接口一路做到 `.jdr`；套件里也保留了传统路线（空白模板、开发指南、打包脚本）。

## 使用小贴士

- **封面自动就位**：把任意一张封面图片放进音频文件夹，就会自动成为书籍封面；WebDAV 同样支持，`cover.jpg / artwork.png` 优先于音频内嵌图
- **长按书架上的书籍**有更多操作：在线书可更新章节（追更）、缓存全书（提前离线下载）、删除、改书名、换封面、标记"未开始 / 在听 / 听完"
- **在线章节的时长**：源站不提供时长时先显示占位值，播放后自动探测真实时长并修正，无需手动干预
- **接口源导入入口**：首次引导页和书架添加页都可以导入 `.jdr` 包（本地文件或在线链接均可）

## 隐私

无账号、无广告、无统计、无追踪 SDK。书库、进度、书签、缓存全部留在你自己的设备上；联网仅用于连接你配置的 NAS / 在线书源 / 接口源，以及你主动搜索封面。详见[隐私政策](PRIVACY.md)。

界面为全量简体中文，支持 Material You 动态取色与深色 / 浅色 / 跟随系统主题。

## 下载安装

前往 [GitHub Releases](https://github.com/cq10086123/Timbre/releases/latest) 下载最新签名 APK，应用内会自动提示更新。

## 从源码构建

```bash
# Debug 版本（直接可装，无需签名）
./gradlew assembleFreeDebug
```

Release 签名构建、包名覆盖等说明见 [CONTRIBUTING.md](CONTRIBUTING.md)。编译产物为 free 分支：无 Firebase、无统计、零云端依赖。

## 反馈与常见问题

问题与建议请到 [GitHub Issues](https://github.com/cq10086123/Timbre/issues) 提交，常见问题见 [docs/faq.md](docs/faq.md)。

## 许可与致谢

Timbre 基于 [Voice](https://github.com/PaulWoitaschek/Voice)（GPLv3）二次开发，感谢原作者 [Paul Woitaschek](https://github.com/PaulWoitaschek) 与所有上游贡献者。

[GNU GPLv3](LICENSE.md) © Voice 原作者及 Timbre 贡献者

<!-- filler-pr-95 -->

<!-- filler-pr-96 -->

<!-- filler-pr-97 -->

<!-- filler-pr-98 -->

<!-- filler-pr-99 -->
