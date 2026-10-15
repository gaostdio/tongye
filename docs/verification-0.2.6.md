# 0.2.6 动图与视频验证

2026-10-10。正式包名 `app.tongye.diary`，versionCode 9，versionName 0.2.6。交付 APK：`deliverables/同页-0.2.6.apk`，20,521,372 字节，SHA-256 为 `ce102c78d79fa0d11cf4baecae0688150ac8c55c1f0f01b1f8e51484a28c0b4c`。

## 行为与实现

用户要求添加动图或短视频。普通日记的「添加照片、动图或视频」与手账底部「图片/视频」使用同一系统选择器，每次最多选 8 个；可再次添加。逐个在后台复制原始文件到本机内部目录，视频检查可读的尺寸和时长，失败文件移除且保留同批成功项。无云端、注册、拍摄、剪辑或转码，也不设置使用天数和本机篇数配额。

GIF 继续存于 `photos` / `IMAGE`，全局 Coil 2.7.0 配置 GIF 解码：Android 9+ 使用 ImageDecoderDecoder，Android 8 使用 GifDecoder。视频以 `Diary.videos` 或画布 `VIDEO` 元素区分；Coil 生成封面，点开在应用内 VideoView 播放，提供 MediaController 控制。竖屏视频在黑底容器居中；关闭、离开详情或应用进入后台会停止并释放播放器。手账视频沿用元素选择、拖动、缩放、旋转和移除入口，编辑时选中视频后可点「播放视频」按钮。共同回忆相册包含视频封面，点开对应日记观看。

统一 `mediaPaths()` 列举静态图片、GIF 和视频，接入流式互传、备份、接收校验、原作者文件复用和回忆列表。附件仍按 SHA-256 放到 `photos/<hash>.bin`，保留原字节并去重；GIF 按内容解码，视频封面强制使用视频解码器，不能靠收到后的 .bin 扩展名判断。私密页及独占附件不会读取或寄出，默认备份也排除私密；TA 个人页正文只读、允许评论，共同页仍按持久身份合并。

含视频的文件写内部 bundle 3，其他文件写 2，读 1/2/3；拒绝在版本 1/2 中夹带 videos 或 VIDEO。旧 App 会拒绝视频版文件，双方应一起覆盖升级到 0.2.6。外层继续使用 TONGYE3 分段 AES-GCM，保留旧 TONGYE2 读取。Room 仍为 2；旧 JSON 缺 videos 时按空列表处理，不换身份或清库。原来每类 JSON、页数、附件数和 8 GiB 协议上限保留，图片与视频共同计入附件数量，普通页合计最多 100 个附件。

参考已核对的主来源：[Coil 2.7.0 GIF 解码源码](https://github.com/coil-kt/coil/blob/2.7.0/coil-gif/src/main/java/coil/decode/ImageDecoderDecoder.kt)、[视频封面解码源码](https://github.com/coil-kt/coil/blob/2.7.0/coil-video/src/main/java/coil/decode/VideoFrameDecoder.kt)、[Android VideoView](https://developer.android.com/reference/android/widget/VideoView)。

## 构建、单元测试与签名

最终 `./build.ps1 -Verify` 成功；48 项 JVM 测试通过，0 失败、0 错误：ExchangeTest 28、ModelsTest 4、WorkspaceDocumentsTest 3、StreamingFilesTest 8、VideoMediaTest 5。96 MiB 最大堆的同组 8 项流式测试再次通过，保留 150 MiB 文件集合和 65 MiB 单文件压力验证。Lint 0 错误、12 条既有警告。

新增 5 项测试验证普通视频列表、GIF、视频画布的加密往返及原字节、私密隔离、个人页权限；ZIP 原视频及显式私密选择、去重与路径映射；拒绝旧版夹带视频和路径越界；缺 videos 的旧 Room/交换 JSON 可继续读用且身份不变；双向同时添加视频元素收敛、删除标记不被旧元素复活。

APK 签名核验通过，证书 SHA-256 仍为 `442970906a708862e02e7bbd75b8eb5671c167529cca48b270892ca0cd36074a`。AAPT 核对包名、0.2.6 / 9、minSdk 26、targetSdk 36，无 INTERNET 权限。构建隔离 .peer 包后已恢复正式构建，正式交付不含 .peer 后缀。

## Android 15 两套隔离安装

使用自建 tongye_test AVD 的 Alice 正式 App 和 Bob 测试 App。升级不卸载，测试前保存两个虚构数据库快照；全程没有连接用户实体手机。素材是双色 2 帧循环 GIF（487 字节）及模拟器录制的短 H.264 MP4（14,864 字节）。录屏素材含此前冷启动的系统等待对话框，该对话框已关闭；它是测试视频中的画面，播放器测试没有记录同页的崩溃。

1. 在 Alice 普通日记 Media-test 通过系统选择器同时添加 GIF 和视频，保存；覆盖升级后仍能读取。连续抓取实际屏幕，GIF 区域出现红色及蓝色两帧，证明自动播放。视频打开应用内播放器，系统媒体状态表明 video/avc 解码并正常播放至结尾，无错误提示。
2. 新建共同手账 Canvas-video，经同一选择器添加视频和 GIF，保留 VIDEO/IMAGE 元素，操作拖动后保存。视频及动图在画布中呈现，不作为普通文字或文件名显示。
3. Alice 实际系统分享经 FileProvider 授权寄给 Bob，预览显示「1 篇日记 · 1 页手账 · 2 个新图片 / 视频文件」，确认收下成功。逐项读取双方数据库并校验附件，GIF 和 MP4 的 SHA-256 与原始素材一致，原有页面内容未改变。旧页缺字段与空视频列表按同一含义比较。
4. Bob 搜索并打开 TA 的 Media-test，保留正文只读，没有编辑/删除入口。收到的 .bin GIF 连续屏幕取样同样交替红/蓝，视频有正常封面，点开后在应用内解码到结尾。返回系统主页后播放器客户端释放，未留后台播放。
5. 最终正式 APK 覆盖安装到 Alice 后，在「我的 → 导出数据与照片」默认不勾选私密，生成 17,329 字节 ZIP，界面提示导出成功。ZIP CRC 通过，GIF/MP4 原字节摘要一致，videos 和 VIDEO 内容使用可移植摘要路径，未包含私密页或互传口令。
6. 测试后恢复两个原始虚构数据库并关闭模拟器。`tools/check_media.py` 每次执行都检查 AVD 为 tongye_test；测试数据库、素材、附件及 .peer APK 不放入交接包。

截图为 `deliverables/screenshots/0.2.6/gif-*.png`、`peer-gif-*.png`、`02-media-exchange-preview.png` 和 `03-media-backup.png`；已查看界面。素材仅证明 GIF/H.264 的模拟器路径，不等同所有编码、两台实体手机微信/QQ 或 Android 8 实机已验证。视频支持程度取决于手机解码能力，失败时提示换常见 MP4；没有备份恢复、视频转码或冲突副本功能。

## 交接

README、HANDOFF、AGENTS、本地协议和推进清单同步 0.2.6。用 `tools/package_handoff.py` 生成交接包，`tools/verify_handoff.py` 检查 CRC、清单、逐文件摘要和 APK 校验文件；包括最新源码、正式安装包、48 项测试及 8 项低内存报告，不含本机配置、缓存、签名私钥、旧 APK 或模拟器数据库。
