# 上传这份源码到 GitHub

本目录是同页 0.2.7 的独立源码副本。主项目的最新版本仍为 0.2.9；上传时选择这份独立目录即可。

## 已包含

Android 源码、Room 1 / 2 schema、Gradle Wrapper、构建入口、48 项原版测试、需求 / 协议 / 历史验证说明。保留 0.2.7 的普通内置贴纸、GIF / 视频、界面美化和本地加密交换，没有 0.2.8 的个人卡通素材或 0.2.9 的连续翻阅。

不含个人原始照片、卡通 PNG、媒体截图、APK、测试输出、数据库、互传文件、PIN、本机配置、构建缓存或签名私钥。原版交接包已通过 CRC 和逐文件 SHA-256 校验；本次仅整理源码分发，没有重新编译 0.2.7。PUBLIC_SOURCE_MANIFEST.json 记录本副本的完整文件清单及摘要。

## 网页上传

1. 解压 ZIP，进入这份目录，确认看到 README.md、app/、gradle/ 和 build.ps1。
2. 在自己的 GitHub 仓库中点击 Add file → Upload files。
3. 拖入本目录中的项目文件和子目录；源码文件放在仓库根目录，README.md 可直接显示。
4. 写提交说明，例如 Add Tongye 0.2.7 source，再提交。

GitHub 官方说明：https://docs.github.com/en/repositories/working-with-files/managing-files/adding-a-file-to-a-repository

## 构建

接手机器准备 JDK 17+（原版验证使用 21）和 Android SDK 36。Windows 运行 ./build.ps1 -Verify；其他系统按 README 使用 Gradle Wrapper。APK 由构建生成，运行时无需账号或云服务。

另一个环境产生的调试签名可能不同；如要给现有用户做覆盖升级，沿用 HANDOFF.md 的签名约定。此源码包没有替用户选择新的授权许可证。
