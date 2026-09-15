<div align="center">

<img src="artwork/public/timbre/timbre-icon-512.png" width="96" alt="Timbre 图标" />

# Timbre · 听书

**一款极简、专注、完全本地的开源有声书播放器（Android）**
**A minimal, open-source, fully local audiobook player for Android**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE.md)

</div>

> **开源声明**：Timbre 基于开源项目 [Voice](https://github.com/PaulWoitaschek/Voice)（GPLv3）二次开发，
> 在原作基础上完成了全量中文化与品牌改造。感谢原作者 Paul Woitaschek 及所有贡献者。
>
> **Open Source Notice**: Timbre is a fork of the open-source project
> [Voice](https://github.com/PaulWoitaschek/Voice) (GPLv3), with a complete Chinese
> localization and rebrand. Credits go to Paul Woitaschek and all contributors.

---

## 📖 这是什么？ | What is Timbre?

把你手机里的有声书文件变成一个干净、安静的图书馆。没有账号、没有广告、没有强制云同步，
你的书库只留在你自己的设备上——打开即听，放下即停，下次接着听。

Turn your local audiobook files into a calm, focused listening library.
No account, no ads, no forced cloud sync — your library stays on your device.

<p align="center">
  <img alt="书库界面" width="30%" src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_en-US.png" />
  <img alt="播放界面" width="30%" src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_en-US.png" />
  <img alt="睡眠定时" width="30%" src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_en-US.png" />
</p>

## ✨ 功能特性 | Features

**📚 书库管理 Library**
- 添加本地文件夹，自动扫描整理 **M4B / MP3 / M4A / OGG / OGA / OPUS** 有声书
  Add your folders — M4B / MP3 / M4A / OGG / OGA / OPUS files get organized automatically
- 多集音频按文件名**自然排序**（第 2 集正确排在第 10 集之前）
  Episodes are sorted naturally ("Episode 2" correctly precedes "Episode 10")
- 按「正在听 / 未开始 / 已完成」自动分组，网格 / 列表视图切换，支持搜索
  Auto-grouped into Current / Not Started / Finished, grid or list view, with search

**🎧 为听书而生 Built for listening**
- **断点续播**：永远记住你的位置 Resume exactly where you left off
- **可调跳过秒数**：快进 / 快退按钮的秒数随你定 Customizable seek-forward / rewind intervals
- **暂停自动回退**：继续播放时自动倒退几秒帮你回忆上下文 Auto-rewind after pauses
- **播放速度**、**跳过静音**、**音量增益**、章节导航、书签
  Per-book playback speed, skip silence, volume boost, chapters, bookmarks
- **睡眠定时器**：渐弱淡出 / 章节结束停止 / 摇一摇继续 / 自动定时
  Sleep timer with fade-out, end-of-chapter, shake-to-resume and auto-scheduling
- **Android Auto** 与桌面小部件 Android Auto and home-screen widget

**🎨 界面与主题 Appearance**
- 完整简体中文界面（覆盖全部界面文本）Full Simplified Chinese UI
- 深色 / 浅色 / 跟随系统主题，动态取色（Material You）
  Dark / Light / Follow-system themes, dynamic Material You colors

**🔒 隐私优先 Privacy first**
- **零数据收集**：不含任何统计、分析或行为追踪 SDK
  Zero telemetry — no analytics or tracking SDKs at all
- 唯一联网行为：你**主动**搜索封面时查询 DuckDuckGo
  The only network call is a cover-art search to DuckDuckGo, triggered manually by you
- 详见 [隐私政策 PRIVACY.md](PRIVACY.md) — See [PRIVACY.md](PRIVACY.md)

## 📥 下载 | Download

前往 [GitHub Releases](https://github.com/cq10086123/Timbre/releases) 下载最新签名 APK。

Get the latest signed APK from [GitHub Releases](https://github.com/cq10086123/Timbre/releases).

## 🛠️ 自行编译 | Build from source

**要求**：Android Studio（含 Android SDK）+ JDK 17+
**Requirements**: Android Studio (with Android SDK) + JDK 17+

```bash
# Debug 版本（直接可装，无需签名）
./gradlew assembleFreeDebug

# Release 版本（需要自己的签名密钥）
keytool -genkeypair -v -keystore signing/signing.keystore \
  -alias timbre -keyalg RSA -keysize 2048 -validity 10000

cat > signing/signing.properties <<'EOF'
STORE_PASSWORD=你的密码
KEY_ALIAS=timbre
KEY_PASSWORD=你的密码
EOF

./gradlew assembleFreeRelease -Pvoice.versionName=1.0.0 -Pvoice.versionCode=1
# 产物 Output: app/build/outputs/apk/free/release/app-free-release.apk
```

- 编译产物为 **free** 分支：无 Firebase、无统计、零云端依赖，开箱即用
  The `free` flavor ships without Firebase or any cloud dependency
- 包名可用参数覆盖：`-Pvoice.applicationId=你的包名`
  Override the application id: `-Pvoice.applicationId=your.package.name`
- 密钥文件已被 `.gitignore` 排除，不会被误提交
  Keystores are git-ignored on purpose

## 🔄 与原版的区别 | Differences from Voice

| | Timbre | Voice |
|---|---|---|
| 语言 Language | 完整简体中文 + 英文 | 社区翻译（中文不完整）|
| 统计/遥测 Analytics | 完全移除 Completely removed | 仅 play 商店版含 Firebase |
| 捐赠入口 Donation | 无 None | Ko-fi |
| 品牌 Branding | 独立图标与名称 | 上游品牌 |

## 🤝 反馈与贡献 | Feedback

- 问题与建议：[GitHub Issues](https://github.com/cq10086123/Timbre/issues)
- 常见问题：[docs/faq.md](docs/faq.md)
- 欢迎提交 Issue 和 PR。Issues and PRs are welcome.

## 📄 开源许可 | License

[GNU GPLv3](LICENSE.md) © Voice 原作者及 Timbre 贡献者

Timbre is licensed under [GNU GPLv3](LICENSE.md). By contributing, you agree to license
your work under the same terms. This project is derived from
[Voice](https://github.com/PaulWoitaschek/Voice) — the original copyright notices remain
intact throughout the source tree.
