<div align="center">

<img src="artwork/public/timbre/timbre-icon-512.png" width="96" alt="Timbre 图标" />

# Timbre · 听书

**本地书 + WebDAV + 在线书源，一部为中文有声书优化的 Android 播放器**

[![Release](https://img.shields.io/github/v/release/cq10086123/Timbre?label=%E7%89%88%E6%9C%AC)](https://github.com/cq10086123/Timbre/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE.md)

</div>

<p align="center">
  <img alt="首次导入" width="19%" src="docs/screenshots/onboarding-cn.png" />
  <img alt="书库界面" width="19%" src="docs/screenshots/shelf-cn.png" />
  <img alt="播放界面" width="19%" src="docs/screenshots/player-cn.png" />
  <img alt="WebDAV 服务器" width="19%" src="docs/screenshots/webdav-servers-cn.png" />
  <img alt="WebDAV 浏览导入" width="19%" src="docs/screenshots/webdav-browse-cn.png" />
</p>

---

## 🎧 三种书源，一处书架

Timbre 把「本地文件、NAS、在线源」统合成一个书架，换源不换体验。

### 📁 本地文件
- 支持 **M4B / MP3 / M4A / OGG / OPUS / FLAC / WAV / MKV / WebM / 3GP** 等主流格式
- 自然排序：第 2 集永远排在第 10 集前面，中文数字、全角数字都能认
- 千集大书边导入边听，解析完第一集就能开始播放

### ☁️ WebDAV / NAS
- 添加服务器（HTTP/HTTPS，Basic / Digest 认证），目录树直接浏览导入
- 远程封面自动识别：`cover.jpg / artwork.png` 优先于音频内嵌图
- 导入即出封面：章节还在解析，书架封面已经挂上
- 播放边缓存、章节自动预取、WLAN 下自动刷新书库
- 断点续播、跳过片头片尾、进度记忆对远程书和本地书一样生效

### 🌐 在线书源
- 搜索即听：在线搜索有声书，直接加入书架播放
- 第三方源站直链直接播放，新增书源自动适配
- 章节时长自动探测，无信息时也不影响播放
- 播放成功后后台预热后续章节，切集更顺滑
- 错误透传：服务端失败原因直接告诉用户，不再“加载环转 90 秒”

## 🎯 真正为「听书」设计

市面上的播放器大多是“音乐播放器顺便能放长音频”，Timbre 是反过来的：

- **跳过片头片尾**：每本书独立设置。片头每集只跳一次，暂停回退不打架；最后一集正常播完，不会被“跳没了”
- **断点续播**：每一本书、每一集、每一秒都记得，下次打开接着听
- **暂停自动回退**：恢复播放时往回退几秒，帮你接上上下文
- **睡眠定时器**：渐弱淡出 / 章节结束停止 / 摇一摇续命 / 定时自动开启
- **倍速、跳过静音、音量增益、章节导航、书签、Android Auto、桌面小部件、蓝牙耳机控制**

## 📚 为千集大书库而生

- **自然排序**：第 2 集永远排在第 10 集前面
- **井井有条**：正在听 / 未开始 / 已完成 自动分组，网格 / 列表切换，全局搜索
- **在线搜索**：按书名直接搜索在线源，结果一键入库
- **导入不挡播放**：播放线程优先，边导入边听也能秒切章节

## 🔒 零隐私顾虑

- 无账号、无广告、无统计、无追踪 SDK
- 书库、进度、书签、缓存全部留在你自己的设备上
- 联网行为只用于：连接你自己的 NAS / 在线书源，以及你**主动**搜索封面
- 详见 [隐私政策 PRIVACY.md](PRIVACY.md)

## 🎨 与系统浑然一体

全量简体中文 · Material You 动态取色 · 深色 / 浅色 / 跟随系统主题

## 📥 下载 | Download

Timbre 提供**两个独立维护的版本线**，可按需选择：

| 版本线 | 适合谁 | 功能 | 最新 APK | 更新文件 |
|--------|--------|------|----------|----------|
| **Timbre**（完整版） | 想使用全部功能的用户 | 本地 + WebDAV NAS + 在线书源 | [`timbre-vX.Y.Z.apk`](https://github.com/cq10086123/Timbre/releases/latest) | `update.json` |
| **Timbre Pure**（纯净版） | 只听本地或 NAS、不需要在线源的用户 | 本地 + WebDAV NAS，**无在线书源** | [`timbre-pure-vX.Y.Z.apk`](https://github.com/cq10086123/Timbre/releases?q=timbre-pure) | `update-pure.json` |

> 💡 **为什么分两个版本？** 在线书源需要连接第三方源站，部分用户只希望使用纯本地 / NAS 方案。两条线代码独立、更新通道独立，纯净版不会被提示升级到完整版。

前往 [GitHub Releases](https://github.com/cq10086123/Timbre/releases) 查看全部版本，
应用内也会根据你安装的版本自动提示更新。

## 🛠️ 自行编译 | Build from source

```bash
# Debug 版本（直接可装，无需签名）
./gradlew assembleFreeDebug
```

Release 签名构建、包名覆盖等说明见 [CONTRIBUTING.md](CONTRIBUTING.md)。
编译产物为 free 分支：无 Firebase、无统计、零云端依赖。

## 🤝 反馈 | Feedback

问题与建议请到 [GitHub Issues](https://github.com/cq10086123/Timbre/issues)，
常见问题见 [docs/faq.md](docs/faq.md)。

## 📄 许可与致谢 | License

Timbre 基于 [Voice](https://github.com/PaulWoitaschek/Voice)（GPLv3）二次开发，
感谢原作者 [Paul Woitaschek](https://github.com/PaulWoitaschek) 与所有上游贡献者。

[GNU GPLv3](LICENSE.md) © Voice 原作者及 Timbre 贡献者


## JDR 书源插件

支持导入本地运行的 `.jdr` 书源包（多源、无需登录服务器）。

### ✍️ 想自己写书源？一键下载开发工具包（约 30 KB）

**不需要下载本项目源码**，工具包里含教程、三个可改的示例和全部工具（只需 Node.js 18+）：

<p align="center">
  <a href="dist/jdr-kit.zip"><b>⬇️ 下载 JDR 书源开发工具包 (jdr-kit.zip)</b></a>
</p>

> 直链：https://github.com/cq10086123/Timbre/raw/jdr-plugin/dist/jdr-kit.zip
>
> 解压后看 `README.md`：`new` 创建 → 改 `bundle.js` → `test` 冒烟 → `pack` 打包 → App 导入。

### 📚 文档与示例

- 编写教程（从零到发布）：[docs/jdr-tutorial.md](docs/jdr-tutorial.md)
- API 与包格式速查：[docs/jdr-plugin.md](docs/jdr-plugin.md)
- 可直接修改的示例源码：[examples/](examples/)（demo-json / demo-html / demo-headers，附预打包 .jdr）
- 打包与冒烟测试工具：[tools/source-kit/](tools/source-kit/)
