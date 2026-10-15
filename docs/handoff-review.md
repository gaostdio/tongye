# 本次交接检查

日期：2026-10-05；项目版本 0.2.2 / versionCode 5，Room 版本 2。

检查范围：源码与目录结构、构建入口及依赖、数据库迁移与持久化、私密隔离、文件权限、互传格式/版本及删除逻辑、历史文档与当前实现的一致性，以及交接包完整性。

## 检查结论与处理

- 当前实现是原生 Android 本地交换，不依赖 Firebase；Manifest 未申请 INTERNET，FileProvider 仅授权寄件缓存。
- 保留历史草案但明确标注，不将云端/账号/后台实时同步描述为已实现。
- 把 `build.ps1` 的机器配置错误提示补全：支持 JAVA_HOME 或 PATH、ANDROID_HOME / ANDROID_SDK_ROOT / Android Studio local.properties；保留 Codex runtime 的可选自动发现，不需要携带原机的配置文件。
- APK 命名从 Gradle versionName 读取，构建自动写 SHA-256，避免下一位智能体只改版本后仍输出旧文件名。
- 新增 AGENTS.md 和 HANDOFF.md，记录现有需求、源码入口、数据库/协议约束、签名限制、测试步骤及未完成项。
- 交接包采用文件白名单，排除缓存、SDK、local.properties、签名私钥、模拟器数据库、旧安装包及大型生成测试数据。测试源码保留，可重新生成虚构数据。
- 包含最新 APK、需求和验证文档、真实模拟器截图、紧凑测试报告及长期使用结果摘要；每个文件都有摘要清单。

## 本次复验

在不带 `local.properties`、项目 `.gradle` 或编译输出的解压副本中运行 `build.ps1 -Verify`，实际重新执行 53 个任务，47 秒完成。使用本机已安装的 JDK/SDK 与系统级依赖缓存；这不是在另一台实体电脑上测试，也不声称交接包内含离线 SDK。

| 本次检查 | 结果 |
| --- | --- |
| 解压副本 assembleDebug | 成功，生成 0.2.2 正式包 |
| 解压副本单元测试 | 24 通过，0 失败；Exchange 17 / Models 4 / WorkspaceDocuments 3 |
| 解压副本 lintDebug | 0 错误、12 条既有警告 |
| gradlew.bat --version | Wrapper 实际下载并启动 Gradle 8.14.3 |
| 新构建 APK 签名 | v2 校验通过，与原机证书相同 |
| 合并后的 Manifest | 不含 INTERNET 权限 |
| 构建输入核对 | 35 个源码、资源、schema、Wrapper 和构建配置文件与打包清单逐字节一致 |
| ZIP 完整性 | CRC、逐文件 SHA-256、清单与文件集合一致性校验通过 |

交接包保留原先已在模拟器运行过的最新 APK；本次新编译证明所附源码可构建，不把未经实测的重编译 APK 替换到用户手机。紧凑测试 XML 与 Lint 文本取自本次解压副本的新结果。既有模拟器与长期使用证据见各版本 verification 文档，本次没有再宣称实体手机测试。

压缩包内 `PACKAGE_MANIFEST.json` 记录文件路径、字节数、SHA-256，清单自身不列入自身摘要。ZIP 外有 `.sha256`；可用 `tools/verify_handoff.py` 重新核验。SDK、JDK、Gradle 下载依赖与签名私钥不随包提供。

## 仍然存在的边界

24 项 JVM 测试和 Android 15 模拟器记录不能代替两台实体手机及真实微信/QQ 的完整验证。备份恢复、迁移/口令找回、大量原图自动拆包、生产签名尚未完成；数据库与 ZIP 备份并未做内容加密，私密锁是应用内保护。完整限制见 HANDOFF.md 与 README。
