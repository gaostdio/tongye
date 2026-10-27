# 同页项目：智能体工作约定

先读 `HANDOFF.md`、`README.md`、`docs/local-exchange-guide.md`，再根据用户后续要求工作。

## 已确认的产品方向

- 原生 Android 情侣日记；名称「同页」。目标用户在中国大陆。
- 无云端、无注册账号。首次只填昵称，双方各有独立本机身份。
- 一次复制/粘贴口令关联，以 `.couple` 加密文件主动互传。操作要简单。
- 我的 / TA 的 / 共同空间；私密页绝不互传，TA 的个人页正文只读、可以评论，共同页可续写。所有非私密日记与手账均可评论。
- 日记和手账有明显删除入口；可长期记录，没有天数或本机篇数配额。
- `docs/cloud-design.md` 和 `backend/` 是未部署的历史草案。不要据此自行接入 Firebase 或改回复杂登录。

## 修改时必须保留

- 当前正式包名 `app.tongye.diary`；版本、数据库迁移和签名关系见交接说明。
- Room 使用版本 2，保留旧库迁移；不要用 destructive migration 或重新初始化身份来绕过错误。
- 序列化/数据库工作在后台，通过事务保存；写入成功后才通知界面。大量记录列表保持按可见项组成。
- `.couple` 的 AES-GCM 验证、身份绑定、私密隔离、路径限制、删除标记、重复收件去重及确认版本逻辑。
- 修改共享合并规则时检查双向收敛；同时间冲突比较持久 UUID，不比较相对的 me/partner。
- 不清空用户手机/数据库，不以卸载作为升级步骤。开发测试使用自己创建的隔离模拟器或测试包。
- `-PtongyePeerTest=true` 只用于第二个隔离测试安装，正式交付不要带 `.peer` 后缀。

## 验证与交付

- 交付文件统一放在项目 `deliverables/` 中，并给出可点击链接；不要复制到桌面根目录。
- Windows：`./build.ps1 -Verify`；其他系统：`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:streamingLowMemory`。
- Android SDK/JDK 由接手机器提供；不要提交 `local.properties`、缓存或其他签名私钥。用户于 2026-10-27 明确要求公开的 `signing/debug.keystore` 是例外，用途与公开影响见 `signing/README.md`。
- 当前 48 项测试、96 MiB 堆内存下的 8 项流式测试与 Lint 结果是基线。大量图片必须使用 `ExchangeFiles` / `BackupFiles` 的流式磁盘路径，不得恢复全量 `readBytes` 或 `ByteArrayOutputStream`。根据变更运行相关检查，不把模拟器结果描述为已验证实体手机。
- 更新 APK 时同步版本号与说明；脚本会按 versionName 命名 APK 并生成 SHA-256。
- 打包交接用 `tools/package_handoff.py`，压缩包校验用 `tools/verify_handoff.py`；先看参数帮助。打包前更新交接说明和检查记录。
