# 0.2.0 本地交换版验证

验证日期：2026-10-05。正式包为 `app.tongye.diary`，versionCode 3 / versionName 0.2.0，最低 Android 8.0。安装包 `deliverables/同页-0.2.0.apk`，桌面副本 `同页安装包.apk`；两份 SHA-256 相同，摘要见安装包旁的 `.sha256` 文件。

## 构建与检查

运行 `build.ps1 -Verify`：APK 构建成功，16 项单元测试通过（ExchangeTest 12、ModelsTest 4），0 失败。Lint 0 错误、12 条警告，主要是固定依赖存在更新和 SharedPreferences 的 KTX 写法建议。未用基线隐藏检查结果。

`apksigner verify --verbose --print-certs` 验证 v2 签名通过；与旧版签名相同，证书 SHA-256 为 `442970906a708862e02e7bbd75b8eb5671c167529cca48b270892ca0cd36074a`。最终 APK 19,896,975 字节。合并后的 Manifest 不含 INTERNET 权限。

自动测试覆盖私密过滤、错误口令/密文篡改、自收件和陌生身份拒绝、确认版本与照片增量、重复接收、共同画布不同元素合并和删除标记、共享改私密撤回、只读个人页、旧数据字段迁移、空间筛选、ZIP 路径限制、照片根目录限制以及同时间冲突的稳定身份排序。返回原作者的共同画布不重发原作者已有照片。

## 原生运行与双身份收发

使用独立 Android 15 `tongye_test` 模拟器。正式包和 `-PtongyePeerTest=true` 生成的 `.peer` 测试包分别拥有独立 UID、数据库和内部照片目录。测试昵称为 Alice / Bob；没有用户真实数据参与。

- 首次入口只输入一个昵称，点「开始记录」或键盘完成即可进入；不要求账号、密码、TA 昵称、日期或示例。未设置日期时首页不显示虚构天数。
- 一方生成并复制口令，另一方「粘贴并加入」；身份和配对信息在覆盖安装、进程重启后保留。
- Alice 创建个人日记（含一张测试图）、共同手账和私密日记。系统分享向另一隔离应用提供 FileProvider 临时授权；Bob 预览收到 1 篇日记、1 页手账、1 张新照片，确认后合入本机，私密页没有寄出。
- Bob 创建个人日记并在共同手账增加星星贴纸，再寄回。Alice 收下后保留原来的文字元素及新星星，收到 Bob 的个人日记；回信没有重传 Alice 的原图。
- Alice 修改已有个人日记后增量寄出：预览为 1 篇日记、0 页手账、0 张新照片。Bob 收下后看到更新正文和之前的照片。个人页详情没有编辑和删除入口。
- Bob 停留在该只读日记详情时，再次收到相同版本的分享文件。应用自动进入收件预览；确认后顶部显示「这份回忆已经收下，没有重复添加。」
- 旧版升级阶段比较过数据库及私密设置摘要，覆盖安装保留原数据与 PIN；迁移后的本机身份和笔迹 ID 会先保存再发布，避免重启后身份变化。

截图在 `deliverables/screenshots/0.2.0/`：`01-entry.png`（简化入口）、`02-home.png`、`04-exchange.png`、`05-receive-preview.png`、`08-merged-common-page.png`、`09-incremental-preview.png`、`10-received-photo.png`、`11-repeat-receive.png`。历史过程截图移入 `deliverables/verification/previous-ui/`，不作为最终界面证据。

`deliverables/verification/exchange/` 里的测试 APK、口令、测试照片和人工生成的 .couple 文件仅供开发验证，不是内置用户数据；测试 APK 不需要发给使用者。

## 尚未验证与范围

已验证 Android 系统分享与两个隔离安装之间的真实文件授权，尚未在两台实体手机或实际微信/QQ 客户端中完整走通。遇到聊天应用不能直接打开附件时，先保存文件，再用「收下 TA 的生活」选择；发送也提供「保存文件再发送」。Android 8.0 和真实生物识别硬件未实测。

当前通过加密文件主动交换，不自动实时更新。便签与额外纪念日暂为本机功能；近距离发现、Wi-Fi Direct、独立主页编辑器、口令恢复、换手机迁移和备份恢复尚未实现。共同页冲突使用手机时间，严重时钟差异可能影响同一元素的取舍。完整协议和限制见 `local-exchange-guide.md`。
