package app.tongye.diary

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun ExchangeScreen(state: DiaryState, vm: DiaryViewModel, onBack: ()->Unit) {
    val ex=requireNotNull(state.exchange)
    val context=LocalContext.current
    val clipboard=LocalClipboardManager.current
    val scope=rememberCoroutineScope()
    var code by rememberSaveable {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("")}
    var opened by remember {mutableStateOf<OpenedExchange?>(null)}
    var outgoingPath by rememberSaveable {mutableStateOf<String?>(null)}
    val incoming by vm.incoming.collectAsState()
    val progress by vm.transferProgress.collectAsState()
    DisposableEffect(Unit) {onDispose {vm.discardExchange(opened)}}
    fun read(uri: android.net.Uri) { scope.launch {
        busy=true;message="正在打开 TA 的回忆…"
        vm.discardExchange(opened);opened=null
        try {opened=vm.readExchange(uri);message=""} catch(e: Exception) {message=e.message ?: "文件没能打开，请重新选择"}
        finally {busy=false;vm.clearIncoming()}
    } }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->uri?.let {read(it)}}
    val saveFile=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {uri->if(uri!=null) scope.launch {
        busy=true
        try {withContext(Dispatchers.IO) {
            val file=File(requireNotNull(outgoingPath) {"请重新装好回忆再保存"})
            requireNotNull(context.contentResolver.openOutputStream(uri)) {"无法保存到这个位置"}.use {output->file.inputStream().use {it.copyTo(output)}}
        };message="已保存回忆文件，在微信或 QQ 选择这个文件发给 TA。"}
        catch(e: Exception) {message=e.message ?: "文件没有保存成功，请重试"}
        finally {busy=false}
    } }
    LaunchedEffect(incoming,ex.code) {if(incoming!=null && ex.code.isNotBlank() && !busy) read(incoming!!)}
    BackHandler {if(!busy) {vm.clearIncoming();onBack()}}
    fun connect(value: String) {scope.launch {
        busy=true
        try {vm.connect(value);message="口令准备好了，把它发给 TA 就可以开始互传。"} catch(e: Exception) {message=e.message ?: "口令未保存，请重试"}
        finally {busy=false}
    } }
    fun send(complete: Boolean) {scope.launch {
        busy=true;message="正在装好这次回忆…"
        try {
            val (file,prepared)=vm.prepareExchange(complete)
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
            val intent=Intent(Intent.ACTION_SEND).apply {
                type="application/octet-stream";putExtra(Intent.EXTRA_STREAM,uri)
                clipData=ClipData.newUri(context.contentResolver,"同页回忆",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser=Intent.createChooser(intent,"把最近的生活寄给 TA").putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,arrayOf(ComponentName(context,MainActivity::class.java)))
            context.startActivity(chooser)
            message="已装好 ${prepared.diaryCount} 页记录、${prepared.photoCount} 个图片 / 视频文件" + (if(prepared.removalCount>0) "、${prepared.removalCount} 页删除或撤回" else "") + "，在分享窗口选择 TA。"
        } catch(e: Exception) {message=e.message ?: "这次没能寄出，请稍后重试"}
        finally {busy=false}
    } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={vm.clearIncoming();onBack()},enabled=!busy) {Icon(Icons.Outlined.ArrowBack,"返回")}
            Text("交换最近的生活",fontSize=17.sp)
        }
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp))
        if(busy && progress!=null) Text(progress!!,fontSize=12.sp,color=Sage,modifier=Modifier.padding(horizontal=24.dp,vertical=6.dp))
        if(message.isNotBlank()) Text(message,fontSize=13.sp,lineHeight=23.sp,color=Sage,modifier=Modifier.padding(horizontal=24.dp,vertical=10.dp))
        Column(Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState()).padding(horizontal=24.dp)) {
            PageTitle("A LITTLE LETTER FOR YOU","把生活，寄给 TA","各自记录，想分享的时候再寄出。")
            Row(Modifier.fillMaxWidth().padding(bottom=20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                IconTile(Icons.Outlined.MailOutline)
                Column {TinyLabel("一份只给 TA 的回忆");Text("照片、动图、视频，都可以一起寄。",fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=6.dp))}
            }
            if(ex.code.isBlank()) {
                PaperCard {
                    Text("第一次，选一个人生成口令",fontSize=17.sp)
                    Text("把口令发给 TA，TA 在这里粘贴加入。只需要设置这一次。",fontSize=13.sp,lineHeight=23.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
                    Button(onClick={connect(ExchangeEngine.newCode())},enabled=!busy,modifier=Modifier.fillMaxWidth().padding(top=18.dp)) {Text("生成我们的口令")}
                    Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("已收到 TA 的口令？",fontSize=12.sp,color=Sage,modifier=Modifier.weight(1f))
                        TextButton(onClick={val pasted=clipboard.getText()?.text;if(pasted.isNullOrBlank()) message="先复制 TA 发来的口令，再点这里。" else {code=pasted;connect(pasted)}},enabled=!busy) {Text("粘贴并加入",fontSize=12.sp)}
                    }
                    OutlinedTextField(code,{code=it.take(100)},label={Text("粘贴 TA 的口令")},modifier=Modifier.fillMaxWidth().padding(top=8.dp),singleLine=true,
                        keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions=androidx.compose.foundation.text.KeyboardActions(onDone={if(code.isNotBlank() && !busy) connect(code)}))
                    OutlinedButton(onClick={connect(code)},enabled=code.isNotBlank() && !busy,modifier=Modifier.fillMaxWidth().padding(top=12.dp)) {Text("加入 TA")}
                }
            } else {
                KeepsakeCard {
                    Text(if(ex.peerId.isBlank()) "我们的口令已准备好" else "和 ${state.profile.partner}，交换小日子",fontSize=17.sp)
                    Text(if(ex.peerId.isBlank()) "让 TA 粘贴相同口令，就能收下彼此的回忆。" else "口令设置一次就够了，之后直接寄出或收下。",fontSize=12.sp,lineHeight=22.sp,color=Muted,modifier=Modifier.padding(top=10.dp))
                    Text(ex.code,fontSize=12.sp,color=Sage,modifier=Modifier.padding(top=14.dp))
                    TextButton(onClick={clipboard.setText(AnnotatedString(ex.code));message="口令已复制，粘贴发给 TA 即可。"}) {Text("复制口令")}
                    Text("口令只给 TA，用来打开你们的加密回忆。",fontSize=10.sp,color=Muted)
                }
                Button(onClick={send(false)},enabled=!busy,modifier=Modifier.fillMaxWidth().padding(top=24.dp).height(52.dp)) {Icon(Icons.Outlined.Send,null);Text("  寄给 TA")}
                OutlinedButton(onClick={picker.launch(arrayOf("*/*"))},enabled=!busy,modifier=Modifier.fillMaxWidth().padding(top=12.dp).height(52.dp)) {Icon(Icons.Outlined.FileOpen,null);Text("  收下 TA 的生活")}
                Text("寄出后，用微信或 QQ 发给 TA。收到文件后，选择用同页打开，或点上面的按钮选择文件。",fontSize=12.sp,lineHeight=23.sp,color=Muted,modifier=Modifier.padding(top=16.dp))
                Text("私密页不会寄出。重复收到的文件不会增加重复日记；收到 TA 的回信后，只寄出尚未确认的新内容。",fontSize=11.sp,lineHeight=21.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
                TextButton(onClick={send(true)},enabled=!busy,modifier=Modifier.padding(top=12.dp)) {Text("重新寄完整回忆",fontSize=12.sp)}
                TextButton(onClick={scope.launch {
                    busy=true
                    try {val (file,_)=vm.prepareExchange();outgoingPath=file.absolutePath;saveFile.launch(file.name)}
                    catch(e: Exception) {message=e.message ?: "回忆文件未生成，请重试"}
                    finally {busy=false}
                }},enabled=!busy) {Text("保存文件再发送",fontSize=12.sp)}
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    opened?.let { packet ->
        val pages=packet.bundle.diaries
        AlertDialog(onDismissRequest={if(!busy) {opened=null;vm.discardExchange(packet)}},title={Text("${packet.bundle.profile.name}寄来了一段生活")},text={Column {
            Text("${pages.count {!it.isDiy}} 篇日记 · ${pages.count {it.isDiy}} 页手账\n${packet.mediaCount} 个新图片 / 视频文件",fontSize=15.sp,lineHeight=26.sp)
            if(packet.bundle.commentUpdates.isNotEmpty()) Text("${packet.bundle.commentUpdates.size} 页评论更新 · ${packet.bundle.commentUpdates.sumOf {it.comments.size}} 条评论\n只追加评论，保留你的正文和手账。",fontSize=13.sp,lineHeight=23.sp,color=Sage,modifier=Modifier.padding(top=12.dp))
            if(packet.bundle.removals.isNotEmpty()) Text("${packet.bundle.removals.size} 页删除或撤回\n收下后，会移除对应的已分享页面。",fontSize=13.sp,lineHeight=23.sp,color=Blush,modifier=Modifier.padding(top=12.dp))
            Text("收下后会合入对应页面，你的私密页会保留。",fontSize=12.sp,lineHeight=22.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
        }},confirmButton={TextButton(onClick={scope.launch {
            busy=true
            try {val result=vm.receiveExchange(packet);message=if(result.duplicate) "这份回忆已经收下，没有重复添加。" else "已收下 ${result.changedCount} 页更新，到对应日记查看吧。";opened=null}
            catch(e: Exception) {message=e.message ?: "这份回忆没能收下，请重试";opened=null}
            finally {busy=false;vm.discardExchange(packet)}
        }},enabled=!busy) {Text("收下这份回忆")}},dismissButton={TextButton(onClick={opened=null;vm.discardExchange(packet)},enabled=!busy) {Text("稍后再收")}})
    }
}

@Composable fun PersonalSpace(state: DiaryState, partner: Boolean, onBack: ()->Unit, onOpen: (Diary)->Unit) {
    val profile=if(partner) state.exchange?.peerProfile else state.profile
    val name=if(partner) state.profile.partner else state.profile.name
    val pages=remember(state.diaries,partner) {state.diaries.filter {it.authorId==if(partner) "partner" else "me"}.filter {it.visibility!="PRIVATE"}.sortedByDescending {it.date}}
    val color=when(profile?.theme) {"雾粉"->androidx.compose.ui.graphics.Color(0xFFF3E7E3);"雾蓝"->androidx.compose.ui.graphics.Color(0xFFE5EDF3);else->androidx.compose.ui.graphics.Color(0xFFEAEEE5)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=24.dp)) {
        item("header") {
        IconButton(onClick=onBack) {Icon(Icons.Outlined.ArrowBack,"返回")}
        PageTitle(if(partner) "A LITTLE WORLD FROM YOU" else "MY LITTLE WORLD","${name}的小世界",if(partner) "这里收藏 TA 主动寄来的生活。" else "记录生活，也设计属于自己的页面。")
        PaperCard(color=color) {
            Avatar(name,partner,58)
            Text(if(partner) "${name}喜欢的颜色：${profile?.theme ?: "还没有收到"}" else "你的颜色：${state.profile.theme}",fontSize=13.sp,modifier=Modifier.padding(top=16.dp))
            Text(if(partner) "每次收下回忆，这里都会更新。" else "私密页在「日记 → 私密」里，只留给自己。",fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=10.dp))
        }
        val cover=pages.find {it.id==profile?.homePageId && it.isDiy}
        cover?.let {SectionHeading("${name}设计的主页");CanvasPage(it)}
        SectionHeading(if(partner) "TA 寄来的日记与手账" else "我的日记与手账")
        if(pages.isEmpty()) EmptyState(if(partner) "等一份来自 TA 的回忆" else "从今天开始记录",if(partner) "收下 TA 的 .couple 文件后，就能在这里看到。" else "记录会先保存在本机，点「寄给 TA」再分享。")
        }
        items(pages,key={it.id}) {d->DiaryCard(d,state.profile) {onOpen(d)}}
        item("footer") {Spacer(Modifier.height(28.dp))}
    }
}
