<div align="center">

<img src="artwork/public/timbre/timbre-icon-512.png" width="96" alt="Timbre 图标" />

# Timbre · 听书

**把有声书装进口袋：本地优先 · WebDAV 加持 · 为「听」而生的 Android 播放器**

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

## ☁️ NAS 变书架：WebDAV 远程书库

这是 Timbre 最拿手的事——**你的有声书在 NAS 上，书架在口袋里**。

- **浏览即导入**：添加服务器（HTTP/HTTPS，Basic / Digest 认证），目录树随便逛，
  「此文件夹作为一本书」或「子文件夹各成一本」一键整库入库
- **封面全自动**：文件夹里的 `cover.jpg / artwork.png` 优先于音频内嵌图；
  你手动选的封面拥有最高优先级，永远不会被覆盖
- **导入即出封面**：章节还在解析，封面已经挂上书架——不用等一千多集读完
- **边下边听**：播放边缓存、章节自动预取、缓存上限可调，WLAN 下自动刷新书库
- **体验与本地无异**：断点续播、自然排序、跳过片头片尾对远程书一样生效
- **错误看得见**：密码失效（如卸载重装后）会红字提示重新输入，绝不"莫名失败"

## 🎧 真正为「听书」设计

市面上的播放器大多是"音乐播放器顺便能放长音频"，Timbre 是反过来的：

- **跳过片头片尾**：每本书、每一集独立设置。片头每集只跳一次，
  不和暂停回退打架；暂停在片尾不触发跳过——那是你自己选的位置；最后一集正常播完
- **断点续播**：每一本书、每一集、每一秒都记得，下次打开接着听
- **暂停自动回退**：恢复播放时往回退几秒，帮你接上上下文
- **睡眠定时器**：渐弱淡出 / 章节结束停止 / 摇一摇续命 / 定时自动开启
- 还有：0.5x~2x 倍速、跳过静音、音量增益、章节导航、书签、
  Android Auto、桌面小部件、蓝牙耳机控制

## 📚 为千集大书库而生

- **自然排序**：第 2 集永远排在第 10 集前面，
  「第二十章」这类中文数字和全角数字也认得
- **边导入边听**：千集长书逐批入库，第一集解析完就能开始听，导入再久不挡道
- **井井有条**：正在听 / 未开始 / 已完成 自动分组，网格 / 列表切换，全局搜索
- 格式通吃：**M4B / MP3 / M4A / OGG / OPUS / FLAC / WAV** 及更多

## 🔒 零隐私顾虑

- 无账号、无广告、无统计、无追踪 SDK——一行都没有
- 书库、进度、书签、缓存全部留在你自己的设备上
- 唯一的联网行为：连你自己的 NAS，以及你**主动**搜索封面
- 详见 [隐私政策 PRIVACY.md](PRIVACY.md)

## 🎨 与系统浑然一体

全量简体中文（覆盖每一个界面文案）· Material You 动态取色 ·
深色 / 浅色 / 跟随系统主题

## 📥 下载 | Download

前往 [GitHub Releases](https://github.com/cq10086123/Timbre/releases/latest) 下载最新签名 APK，
应用内也会自动提示更新。

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
