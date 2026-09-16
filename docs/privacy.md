# 隐私政策 / Privacy Policy

**最后更新：2026-09-16**

Timbre（以下简称"本应用"）是一款**自由开源**的本地有声书播放器（GPLv3），基于开源项目 [Voice](https://github.com/PaulWoitaschek/Voice) 二次开发。完整源代码公开于：
https://github.com/cq10086123/Timbre

## 我们的承诺

**本应用不收集、不存储、不传输任何个人信息。**

- ❌ 没有账号系统，无需注册
- ❌ 没有广告
- ❌ 没有内置任何统计、分析或行为追踪 SDK（如 Firebase / 友盟 / 广点通等）
- ❌ 不获取精确位置，不读取通讯录、短信、通话记录
- ✅ 你的所有书籍、播放进度、书签和设置都只保存在你自己的设备上

## 本应用使用的权限及用途

| 权限 | 用途 |
|---|---|
| 读取音频文件 / 存储 | 扫描并播放你设备上的有声书文件，这是本应用的核心功能 |
| 通知 | 播放时显示媒体控制通知（暂停 / 快进等） |
| 前台服务（媒体播放） | 保证音频在后台持续播放 |
| 唤醒锁（Wake Lock） | 播放时防止设备休眠导致播放中断 |
| 蓝牙 | 支持蓝牙耳机 / Android Auto 的媒体控制 |

以上权限仅用于实现对应功能，相关数据不会离开你的设备。

## 唯一的联网行为

本应用仅有一项联网功能：**检查更新**。

- 应用启动后约 10 秒，会在后台尝试获取仓库中的 `update.json` 版本文件（优先走 jsDelivr CDN：`cdn.jsdelivr.net` / `fastly.jsdelivr.net`，失败则尝试 GitHub API：`api.github.com`），用于对比最新版本号。
- **每天最多检查一次**，请求超时即静默放弃，不影响任何功能。
- 请求中**不包含任何账号、设备标识或个人信息**，也不会自动下载或安装任何文件。
- 检测到新版本时仅弹窗提示，是否前往下载页面由你自己点击决定（前往下载同样是打开浏览器访问 GitHub Release 页面）。

除此之外没有任何网络请求。

## 数据存储与备份

所有数据（书库信息、播放位置、书签、设置）均存储于本应用的私有目录中。如果你开启了 Android 系统自带的自动备份功能，系统可能会按你自己的 Google/厂商账号设置备份应用数据，该过程由操作系统控制，与本应用无关。

卸载本应用即可删除全部应用数据。

## 儿童隐私

本应用不面向 13 岁以下儿童收集任何信息（我们实际上不收集任何人的信息）。

## 隐私政策变更

如本政策更新，会在源代码仓库中公布。请通过以下地址查看历史版本：
https://github.com/cq10086123/Timbre/blob/main/PRIVACY.md

## 联系我们

如对本政策或应用有任何疑问，请在 GitHub 提交 Issue：
https://github.com/cq10086123/Timbre/issues

---

## English Summary

Timbre is a free and open-source (GPLv3) local audiobook player, forked from the
[Voice](https://github.com/PaulWoitaschek/Voice) project.

- **No data collection.** The app contains no accounts, no ads, and no analytics or tracking SDKs.
- **Everything stays on your device:** your library, playback positions, bookmarks, and settings.
- **Only network call:** an update check that runs at most once per day in the background. It downloads a small version file from the jsDelivr CDN (fallback: the GitHub API) and sends nothing - no account, no device identifier, no personal data. Nothing is ever downloaded or installed automatically; visiting the download page is your choice.
- Permissions (media files, notifications, foreground service, wake lock, Bluetooth) are used solely to provide audiobook playback.
- Uninstalling the app removes all of its data.

Source code: https://github.com/cq10086123/Timbre
Questions: open an issue at https://github.com/cq10086123/Timbre/issues
