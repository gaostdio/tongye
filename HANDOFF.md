# 给下一位智能体的交接说明

基线：同页 **0.2.7 / versionCode 10**。这是可运行的原生 Android 本地交换版，丰富界面但保留奶油纸色、三种主题和原有操作，支持动图与短视频、大量图片流式互传与 ZIP 备份。无需前一段聊天也能接手；本文件说明已经做过什么、不能误改什么，以及怎么运行。

## 当前需求优先于历史草案

用户先考虑过双账号云同步，随后明确决定「不要接入云端」「先简化登录」，改为本地交换式日记，要求互传不复杂、支持删除、能够长期写。应用名已确认「同页」，主要给中国大陆同学使用。

需求阅读顺序：本文件 → README → `docs/local-exchange-guide.md` → `docs/local-exchange-plan.md` → `docs/roadmap.md`。`docs/product-plan.md` 是最早的较大范围方案；云端文档与 backend 规则只是历史资料，不是当前待实现任务。

## 能运行的功能

- 0.2.7 界面：书页线稿、柔和渐变封面、公开回忆数量、三空间入口卡片、带媒体数量/评论数的日记卡片、相纸式相册、主题色图标与导航、清晰的设置分组和长值换行。首页计数不含私密页；三种主题保持原名称。只改展示，不调整数据库、互传规则或身份。视觉检查见 `docs/verification-0.2.7.md`。
- 首次只填昵称，没有邮箱、密码、验证码或云账号。再次打开直接进入。
- 普通日记、自由手账、照片、GIF 动图、视频、模板、文字/贴纸/笔迹、拖动缩放旋转、撤销/重做、收藏、共同回应、纪念日和本机便签。
- 所有非私密页明确的评论区、200 字输入、昵称/时间、按需组成评论列表。评论通过 `ExchangeEngine.addComment` 只递增寄件版本，不修改页面 `updatedAt`/`updatedBy` 或正文；双向追加、ID 去重、稳定排序。TA 的个人页正文只读，但能评论；本人也可回复。个人页读者通过 `CommentUpdate` 单独回传自己的评论，不回传正文、布局或照片。作者页面即使正文时间未变化也会合并评论。编辑保存保留已收到但旧草稿未带的评论。旧评论缺少 `createdAt` 时为 0，显示原日期，数据库版本仍为 2。
- 我的 / TA 的 / 共同空间与私密页；私密锁、可用设备的 AndroidX 生物识别、后台重锁和敏感页防截图。
- 一方生成口令，另一方「粘贴并加入」。寄出加密 `.couple` 文件，使用系统分享或保存文件；对方外部打开或应用内选文件，预览后确认收下。
- 稳定 UUID 身份、一次关联、对方个人页只读、共同页按元素合并、增量版本确认、照片去重、删除/撤回及重复收件去重。
- 明确的删除按钮与确认框。私密删除只影响本机，共享删除在对方收下下次文件后生效。
- 没有记录天数或本机篇数限制。逐页分段数据库、只写变更文档、后台序列化、可见项列表与标题/正文/日期搜索。
- ZIP 备份导出；默认排除私密内容，明确选择后可导出本人私密页。备份不含 PIN 或互传口令。
- 大量图片按文件流式读取与写入，分段认证加密互传、磁盘暂存接收、显示照片处理进度；ZIP 备份完成后才发布到保存位置。原图不缩小，内容摘要去重，没有旧 64 MiB 附件/128 MiB 解压瓶颈。

## 架构与入口

| 文件 | 用途 |
| --- | --- |
| `app/src/main/java/app/tongye/diary/MainActivity.kt` | Compose 入口、单实例外部文件 Intent、缓存 ViewModel |
| `App.kt` | 路由、入口页、首页/日记、私密访问守卫与后台重锁 |
| `Models.kt` | 不可变模型、日期、筛选与保留的旧示例 |
| `DiaryViewModel.kt` | StateFlow、Mutex、持久化、照片、PIN、备份、收发文件 |
| `DiaryStorage.kt` / `WorkspaceDocuments.kt` | Room 版本 2、版本 1 迁移、小段文档与差异写入 |
| `Exchange.kt` | 纯 JVM 协议、加解密、校验、身份/版本、合并、删除标记 |
| `ExchangeFiles.kt` / `BackupFiles.kt` | 有界分段 AES-GCM、磁盘照片暂存、原图流式互传与 ZIP 备份 |
| `MediaViews.kt` | Coil 动图解码与视频封面、应用内播放器、后台停止播放 |
| `ExchangeScreen.kt` | 口令/收发 UI、FileProvider 分享、收件预览、个人空间 |
| `DiaryEditor.kt` / `CanvasPage.kt` | 日记与 DIY 画布编辑、布局、笔迹 |
| `DiaryDetail.kt` | 详情、编辑/删除、收藏、共同页回应 |
| `MemoriesScreen.kt` / `SettingsScreen.kt` / `Design.kt` | 回忆、设置、通用界面 |

包名 `app.tongye.diary`，最低 Android 8.0 / API 26，compileSdk / targetSdk 36。技术栈：Kotlin 2.1.20、Compose BOM 2025.04.01、AGP 8.11.1、Gradle 8.14.3、Room 2.7.1、Coil 2.7.0、Gson 2.11.0、JUnit 4.13.2。已验证 JDK 21；JDK 最低 17。

Room 文档以 `workspace` 保存不带日记列表的资料，以 `diary:<id>` 保存每页，超过 32,768 个 UTF-16 单元再拆段，不能拆断代理对。旧库整行通过 substr 分段读取；新文档在事务内完整保存后才清空旧表。schema 1 和 2 都保留在 `app/schemas/`。不要更换包名、清库或生成新身份来处理旧数据问题。

`.couple` 新格式标记 `TONGYE3\n`：随机 128 位口令派生 AES-256-GCM 密钥，随机 12 字节 nonce 前缀、最多 1 MiB 明文一段，每段绑定序号/长度，必须有认证结尾且不能带尾随字节；完整认证后才打开临时 ZIP。`ExchangeFiles` 只寄新格式，仍读旧 `TONGYE2\n` 文件。包含视频时写内部 bundle 3，否则写 2；读取 1/2/3；版本 2 的 `commentUpdates` 严格验证所有者为收件人、评论者为寄件人、页面/评论 ID 去重与数量/长度；只对现有本人非私密页追加，已删/私密页忽略，不能复活或覆盖正文。双方需一起覆盖升级至 0.2.6 再互传视频。旧 `ExchangeEngine.prepare/open` 保留小型旧格式兼容与测试，新产品路径不得用它处理大图集合。`.couple` 与未加密 ZIP 备份是两个不同入口。完整规则见协议说明，不要只依赖 UI 过滤。

`Diary.videos` 保存普通日记的视频列表，`Element.type=VIDEO` 保存手账视频。`mediaPaths()` 统一列举所有附件，互传、备份、原作者文件复用与回忆列表均使用它。老 JSON 没有 videos 时规范化为空列表，仍用 Room 2，不清库。GIF 沿用 photos/IMAGE，通过内容解码，收件的 .bin 后缀不会使动图失效；视频封面显式指定解码器，不能按 .bin 后缀选择。视频播放器在应用内打开，关闭/后台停止，不用外部播放器读取私密文件。

## 在另一台机器运行

压缩包不带 SDK、JDK、Gradle 缓存、`local.properties` 或签名私钥。首次下载 Gradle 和 Maven 依赖需要联网；应用本身没有 INTERNET 权限。

推荐先用 Android Studio 打开根目录，安装 Android SDK Platform 36、Build Tools 和 Platform Tools；配置 Gradle JDK 17+，生成自己的 `local.properties`。也可以设置 `JAVA_HOME` 和 `ANDROID_HOME`。

Windows PowerShell：

```powershell
# 路径换成接手机器实际安装位置
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:ANDROID_HOME = 'C:\path\to\Android\Sdk'
.\build.ps1 -Verify
```

脚本在没有独立 Gradle 时用项目自带 Wrapper；也兼容原开发机的 Codex Android runtime 自动发现。在其他系统上：

```sh
chmod +x gradlew
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:streamingLowMemory
```

Windows 脚本会把正式 APK 复制到 `deliverables/同页-<versionName>.apk` 并写 `.sha256`。Wrapper 标准输出在 `app/build/outputs/apk/debug/app-debug.apk`。测试会生成虚构 `.couple` 与约 23 MB 的长期使用 JSON 到 `deliverables/verification/exchange/`，不是用户数据，也不需要交付给用户。

`streamingLowMemory` 用 96 MiB JVM 堆重跑 8 项流式测试，包括 150 MiB 图片集合、65 MiB 单图、加密完整性及旧格式兼容。图片集合不能重新 `readBytes()` 或累计在 `ByteArrayOutputStream`；两者仅保留在受限旧格式兼容路径中。`tools/check_large_photos.py` 是只允许自建 `tongye_test` 模拟器的原生压力辅助工具，保留原始虚构身份并在测试后恢复；生成的 120 MiB 图片与测试数据库不打入交接包。

用户要求以后交付文件不要放在桌面根目录；统一放本项目 `deliverables/`，并提供可点击链接。

如要在一个模拟器测试两套独立数据：

```sh
./gradlew -PtongyePeerTest=true :app:assembleDebug
```

测试包名为 `app.tongye.diary.peer`，入口 Activity 类仍为 `app.tongye.diary.MainActivity`。先复制测试 APK，再重新不带参数构建正式包。模拟器测试不是实体手机微信/QQ 已验证的证明。

可选开发工具使用 Python 3.9+ 标准库，不影响 Android 构建：`python tools/package_handoff.py` 生成交接 ZIP，`python tools/verify_handoff.py <ZIP>` 校验；加 `--unpack-to <新的空目录>` 可安全解压。长期数据库辅助脚本为 `tools/check_storage.py`，先运行 JVM 测试生成虚构 JSON，再用 `fixture` 生成版本 1 测试库；pull 模式只允许 `tongye_test` 模拟器。原始大库与完整测试输入未随包携带，已有结果摘要不是可脱离测试输入复验的数据集。

## 签名与已有安装

附带的正式 APK 已签名，可安装。它使用原开发机的调试证书，SHA-256：`442970906a708862e02e7bbd75b8eb5671c167529cca48b270892ca0cd36074a`。

另一台机器默认生成不同的 debug.keystore，因此重新构建的 APK **不能保证覆盖原手机安装**。不要要求用户卸载来绕过签名不一致；在隔离测试包上开发，并在需要给原用户发升级时回到原签名环境，或由用户安排受控的签名配置。包内没有签名私钥。尚未配置生产 release 签名或应用商店发布流程。

## 已完成的验证和证据

- 0.2.7 美化及原生界面检查见 `docs/verification-0.2.7.md`，16 张模拟器截图包含三主题和小屏大字体；正式 APK 覆盖安装前后逐文档一致。截图使用临时虚构示例，测试后恢复原虚构数据库；不向新用户自动添加示例。
- 当前 48 项 JVM 测试通过，8 项流式测试额外在 96 MiB 堆内存通过，Lint 0 错误、12 条既有警告。0.2.6 动图、视频验证见 `docs/verification-0.2.6.md`。0.2.5 的大量图片修复、原生 120 张/120 MiB 互传与备份验证见 `docs/verification-0.2.5.md`。0.2.4 评论验证见 `docs/verification-0.2.4.md`。0.2.2 曾从不含本机配置/项目缓存的压缩包解压目录重新构建，Wrapper 也实际下载并启动，历史结果见 `docs/handoff-review.md`。
- 曾在 Android 15 独立模拟器中验证两套隔离安装的真实 FileProvider 授权、双向收发、照片增量、共同手账合并、重复收件、删除传播及详情页接收。
- 10,000 篇（约 23 MB）旧库真实迁移；正文、身份及 1,000 篇私密页逐条一致。搜索并修改 1999 年旧日记，新增第 10,001 篇后重启，其他旧页不变。
- 详细记录在 `docs/verification*.md`，实际 UI 截图在 `deliverables/screenshots/`。压缩包不带旧 APK、模拟器数据库或运行缓存；长期数据可由测试生成。

## 已知边界与尚未完成

- 双方主动用文件交换，没有后台实时同步或近距离发现。尚未在两台实体手机上的实际微信/QQ 完整验证。
- 新互传 ZIP 与解压内容各最多 8 GiB，加密文件最多 8 GiB + 16 MiB，单类 JSON 32 MiB，最多 20,000 页记录/删除记录/评论更新及 5,000 个照片摘要。ZIP 备份没有上述互传大小限制；缓存与目标位置需要足够空间。超大附件尚不自动拆包；这些不限制本机写日记。
- 手机时间严重偏差影响同元素/整页冲突选择；没有冲突历史 UI。
- 评论开放全部非私密页，单页最多 2000 条；暂不支持评论编辑/删除。双方同时改共同正文仍可能覆盖，追加评论不是正文冲突恢复功能。个人页内容仍仅作者编辑。双方升级到 0.2.6 后再互传视频。
- 私密锁是应用内访问保护，数据库未加密；备份 ZIP 未加密。备份只支持导出，尚无恢复、换手机、解绑或口令找回流程。
- 便签和额外纪念日只在本机；主页设计仅为主题及公开手账封面，不是独立主页编辑器。
- 照片删除暂不物理清理原图，避免破坏其他页面的引用；拍照、裁剪、透明度、自定义图片贴纸与 PDF 回忆册未做。
- 私密 PIN 存在应用 SharedPreferences，普通资料在 Room；卸载、清数据或设备故障仍可丢数据。未保存编辑草稿暂不持久化，低存储失败和实体生物识别仍需更多实测。
- 大量日记仍在内存中加载模型；优化了单行存储和列表，不等同无限容量或所有低端手机都已压力验证。

未完成项是供后续需求判断的记录，不代表已经授权自动新增功能。按用户下一条要求继续，先沿用现有结构，不需要从头创建项目。
