---
hide:
  - toc
---

# 提莫听书（Timbre）

一款自由开源、本地优先的 Android 有声书播放器 / 听书软件。

提莫听书把你自己的有声书文件变成一个安静、顺手的听书库：记住每个位置的播放进度、书签、睡眠定时器、倍速播放、跳过片头片尾、自动封面，全部离线可用；还内置 WebDAV 远程书库，把 NAS 上的有声书直接挂进书架；支持导入自定义接口源（.jdr），在线搜索、取章节、拿播放直链全部在本机完成。

[:material-download: &nbsp;下载最新版本](https://github.com/cq10086123/Timbre/releases/latest){ .md-button .md-button--primary }
[:material-github: &nbsp;GitHub 仓库](https://github.com/cq10086123/Timbre){ .md-button }

<div class="timbre-screenshots">
  <img src="screenshots/shot-shelf.png" alt="Timbre 书架" />
</div>

## 为什么选择提莫听书？

<div class="grid cards" markdown>

- :material-shield-check:{ .lg .middle } **本地优先，无广告**

  ---

  不需要账号，没有广告，没有统计与追踪。你的书库只属于你的设备。

- :material-book-open-blank-variant:{ .lg .middle } **为有声书而生**

  ---

  章节导航、断点续播、书签、睡眠定时器、倍速播放，一个不少。

- :material-skip-forward:{ .lg .middle } **跳过片头片尾**

  ---

  每本书独立设置：进集自动跳过开头，播到片尾自动进入下一集。

- :material-image-multiple:{ .lg .middle } **自动封面**

  ---

  文件夹里有图片就自动做封面，多张随机选一张；没有图片也能按书名生成一张。

- :material-cloud-lock:{ .lg .middle } **WebDAV 远程书库**

  ---

  把 NAS 变成书架：浏览目录一键导入，封面自动就位，边下边听，密码失效会明确提示。

- :material-extension:{ .lg .middle } **自定义接口源（.jdr）**

  ---

  导入 .jdr 扩展包即可接入新的有声书接口：搜索、章节、播放直链由内置沙箱在手机本地执行，请求直连源站。支持文件或链接导入、多接口单包、按源启停。

- :material-cellphone-arrow-down:{ .lg .middle } **应用内更新**

  ---

  启动后后台静默检查新版本，发现更新时弹窗提示，一键前往下载。

- :material-cellphone-link:{ .lg .middle } **Android Auto**

  ---

  支持蓝牙耳机与 Android Auto 的媒体控制，开车也能继续听。

</div>

## 使用小贴士

- **封面自动就位**：把封面图片放进音频文件夹即可自动作为书籍封面（多张随机选一张，没有也能按书名生成）；WebDAV 文件夹里的 `cover.jpg / artwork.png` 优先于音频内嵌图
- **长按书架上的书籍**：在线书可**更新章节**（追更）、**缓存全书**（离线下载）、删除，还能改书名、换封面、标记阅读状态
- **在线章节时长**：源站不提供时长的章节先显示 30 分钟占位，播放后自动探测修正

## 它和上游项目的关系

提莫听书基于开源项目 [Voice](https://github.com/PaulWoitaschek/Voice)（GPLv3）二次开发，在全量中文化之外增加了 WebDAV 远程书库、自定义接口源（.jdr）、跳过片头片尾、自动封面、应用内更新等功能。详见[关于页](about.md)。
