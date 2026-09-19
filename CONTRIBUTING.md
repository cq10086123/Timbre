# 参与贡献 | Contributing

欢迎 Issue 与 PR！

## 反馈问题 | Reporting issues

提交 [GitHub Issues](https://github.com/cq10086123/Timbre/issues) 时请附上：

- 设备 / 系统版本与 App 版本号（关于页可见）
- 复现步骤；本地书问题请说明文件格式，WebDAV 问题请说明服务器类型（飞牛 fnOS / 群晖 / Alist 等）

## 翻译 | Translations

Timbre 暂不使用 Weblate 等翻译平台，全部界面文案直接维护在仓库里：

- 基准文案：`core/strings/src/main/res/values/strings.xml`（英文）
- 简体中文：`core/strings/src/main/res/values-zh-rCN/strings.xml`

想新增或修正一种语言：复制一个 `values-xx` 目录完成翻译后提交 PR 即可。
注意每个语言包需要对基准文案 **100% 覆盖**（CI 的 "Check locale config" 会校验，
缺失的 key 会导致该语言从可用语言中掉出去）。

## 提交代码 | Code

1. Fork 本仓库并新建分支；
2. 本地验证（需要 Android SDK 与 JDK）：

   ```bash
   ./gradlew voiceUnitTest lintKotlin :app:lintFreeDebug
   ```

3. 向 `main` 提交 PR，CI 全绿后合并。

## 许可 | License

Timbre 基于 [Voice](https://github.com/PaulWoitaschek/Voice)（GPLv3）二次开发，
向本项目贡献代码即表示同意以 [GPLv3](LICENSE.md) 授权你的贡献。
