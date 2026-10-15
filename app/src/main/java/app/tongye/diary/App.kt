package app.tongye.diary

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable fun TongyeApp(vm: DiaryViewModel) {
    val snapshot by vm.state.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val state=snapshot
    val primary=when(state?.profile?.theme) { "雾粉" -> Color(0xFF8E6059); "雾蓝" -> Color(0xFF4E6B85); else -> Sage }
    val themePaper=primary.copy(alpha=.12f).compositeOver(Paper)
    MaterialTheme(colorScheme=lightColorScheme(primary=primary,onPrimary=Color.White,primaryContainer=themePaper,onPrimaryContainer=primary,background=Paper,surface=Color.White,onSurface=Ink,onBackground=Ink,onSurfaceVariant=Muted,secondary=Blush,secondaryContainer=themePaper,onSecondaryContainer=primary,surfaceVariant=Color(0xFFEEEFE7),surfaceContainer=Color(0xFFF3F0E9),surfaceContainerHigh=Color(0xFFF3F0E9),surfaceContainerHighest=themePaper,surfaceContainerLow=Color(0xFFFFFDFA),surfaceTint=primary,outline=Muted,outlineVariant=PaperEdge),
        shapes=Shapes(small=RoundedCornerShape(10.dp),medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(22.dp),extraLarge=RoundedCornerShape(28.dp))) {
        if(state==null) { Box(Modifier.fillMaxSize().background(Paper),contentAlignment=Alignment.Center) { if(notice!=null) Text(notice!!,color=Ink) else CircularProgressIndicator() }; return@MaterialTheme }
        if(!state.initialized) { Onboarding(vm); return@MaterialTheme }
        var tab by remember { mutableIntStateOf(0) }
        var route by remember { mutableStateOf<String?>(null) }
        var current by remember { mutableStateOf<Diary?>(null) }
        var diaryFilter by remember { mutableStateOf("MINE") }
        val incoming by vm.incoming.collectAsStateWithLifecycle()
        var createDialog by remember { mutableStateOf(false) }
        var unlocked by remember { mutableStateOf(false) }
        var unlockDialog by remember { mutableStateOf(false) }
        var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
        LaunchedEffect(incoming,route) {
            if(incoming!=null && route!="edit" && route!="exchange") {
                createDialog=false;unlockDialog=false;pending=null;current=null;route="exchange"
            }
        }
        val context=LocalContext.current
        val owner=LocalLifecycleOwner.current
        val scope=rememberCoroutineScope()
        val snackbar=remember { SnackbarHostState() }
        LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); vm.clearNotice() } }
        fun guard(action: ()->Unit) { if(state.profile.lockEnabled && !unlocked) { pending=action; unlockDialog=true } else action() }
        fun open(d: Diary) { if(d.visibility=="PRIVATE") guard { current=d;route="detail" } else { current=d;route="detail" } }
        val privateBlocked=state.profile.lockEnabled && !unlocked && (current?.visibility=="PRIVATE" || (tab==1 && (diaryFilter=="PRIVATE" || diaryFilter=="FAVORITE")))
        DisposableEffect(owner,state.profile.lockEnabled) {
            val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_PAUSE || event==Lifecycle.Event.ON_STOP) {
                unlocked=false; unlockDialog=false; pending=null
            } }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
        DisposableEffect(current?.visibility,diaryFilter,route,tab,unlockDialog) {
            val window=(context as? FragmentActivity)?.window
            val sensitive=current?.visibility=="PRIVATE" || (tab==1 && (diaryFilter=="PRIVATE" || diaryFilter=="FAVORITE")) || unlockDialog
            if(sensitive) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        BackHandler(enabled=route=="detail" || route=="myspace" || route=="partnerspace") { route=null; current=null }
        Scaffold(containerColor=Paper,snackbarHost={ SnackbarHost(snackbar) },bottomBar={ if(route==null) {
            Column {
            HorizontalDivider(color=PaperEdge)
            NavigationBar(containerColor=Color(0xFFFFFDFA),tonalElevation=0.dp) {
                val icons=listOf(Icons.Outlined.FavoriteBorder,Icons.Outlined.MenuBook,Icons.Outlined.PhotoLibrary,Icons.Outlined.PersonOutline)
                listOf("我们","日记","回忆","我的").forEachIndexed { i,label -> NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(icons[i],label,Modifier.size(23.dp))},label={Text(label,fontSize=11.sp,fontWeight=if(tab==i) FontWeight.SemiBold else FontWeight.Normal)},colors=NavigationBarItemDefaults.colors(indicatorColor=accentPaper(),selectedIconColor=primary,selectedTextColor=primary,unselectedIconColor=Muted,unselectedTextColor=Muted)) }
            }
            }
        } },floatingActionButton={ if(route==null && tab==1) ExtendedFloatingActionButton(onClick={createDialog=true},containerColor=primary,contentColor=Color.White,shape=RoundedCornerShape(18.dp),icon={Icon(Icons.Outlined.Add,null)},text={Text("写一页")}) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.fillMaxSize().then(if(privateBlocked) Modifier.clearAndSetSemantics {} else Modifier)) {
                when(route) {
                    "exchange" -> ExchangeScreen(state,vm,onBack={vm.clearIncoming();route=null;current=null})
                    "myspace","partnerspace" -> PersonalSpace(state,partner=route=="partnerspace",onBack={route=null;current=null},onOpen={open(it)})
                    "edit" -> current?.let { diary -> DiaryEditor(diary,vm,privateBlocked=privateBlocked,onBack={route=null;current=null},onPrivacyChange={visibility->current=current?.copy(visibility=visibility)},onSave={ vm.saveDiary(it) { route=null;current=null;vm.notify("这一页，已留在同页里") } }) }
                    "detail" -> current?.let { original -> val d=state.diaries.find { it.id==original.id } ?: original; DiaryDetail(d,state.profile,vm,onBack={route=null;current=null},onEdit={current=d;route="edit"},onDelete={route=null;current=null;vm.notify("已删除这一页")}) }
                    else -> when(tab) {
                        0 -> HomeScreen(state,onWrite={createDialog=true},onOpen={open(it)},onNote={vm.note(it)},onAll={tab=1},onAnniversaries={tab=2},onExchange={route="exchange"},onSpace={space->if(space=="shared") {tab=1;diaryFilter="SHARED"} else route=space})
                        1 -> DiariesScreen(state,diaryFilter,onFilter={ f -> if(f=="PRIVATE" || f=="FAVORITE") guard { diaryFilter=f } else diaryFilter=f },onOpen={open(it)})
                        2 -> MemoriesScreen(state,vm,onOpen={open(it)})
                        3 -> SettingsScreen(state,vm,onProtected={guard(it)},onExchange={route="exchange"})
                    }
                }
                }
                if(privateBlocked) {
                    Column(Modifier.fillMaxSize().background(Paper).clickable {},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                        Icon(Icons.Outlined.Lock,null,tint=Sage,modifier=Modifier.size(35.dp))
                        Text("这里只属于你",fontSize=20.sp,modifier=Modifier.padding(top=18.dp))
                        Button(onClick={pending={};unlockDialog=true},modifier=Modifier.padding(top=20.dp)) {Text("解锁私密空间")}
                        if(route!="edit") TextButton(onClick={route=null;current=null;diaryFilter="MINE"}) {Text("回到我的日记")}
                    }
                }
            }
        }
        if(createDialog) AlertDialog(onDismissRequest={createDialog=false},title={Text("留下今天的一个瞬间")},text={Column {
            Text("一起记录，或者只写给自己。",fontSize=13.sp,color=Muted)
            Spacer(Modifier.height(18.dp))
            listOf("日记" to false,"自由手账" to true).forEach { (label,diy) -> OutlinedButton(onClick={current=Diary(isDiy=diy,visibility=if(diaryFilter=="PRIVATE" && tab==1) "PRIVATE" else if(diaryFilter=="SHARED" && tab==1) "SHARED" else "PARTNER");route="edit";createDialog=false},modifier=Modifier.fillMaxWidth()) { Icon(if(diy) Icons.Outlined.DashboardCustomize else Icons.Outlined.EditNote,null,Modifier.size(19.dp));Text("  $label") } }
        }},confirmButton={TextButton(onClick={createDialog=false}) {Text("再想想")}})
        if(unlockDialog) UnlockDialog(vm,onClose={unlockDialog=false;pending=null},onUnlock={unlocked=true;unlockDialog=false;pending?.invoke();pending=null})
    }
}

@Composable private fun Onboarding(vm: DiaryViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    val valid=name.isNotBlank()
    Column(Modifier.fillMaxSize().background(Paper).safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp)) {
        Spacer(Modifier.height(26.dp)); Text("T O N G Y E  /  同 页",fontSize=11.sp,letterSpacing=2.sp,color=MaterialTheme.colorScheme.primary)
        BookMotif(Modifier.align(Alignment.End).size(138.dp))
        Text("同页，\n记下小日子。",fontSize=36.sp,lineHeight=49.sp,fontWeight=FontWeight.SemiBold,color=Ink,modifier=Modifier.padding(top=4.dp))
        Text("给自己取个昵称，就可以开始。",color=Muted,fontSize=14.sp,modifier=Modifier.padding(top=12.dp,bottom=28.dp))
        OutlinedTextField(name,{name=it.take(12)},label={Text("你的昵称")},singleLine=true,modifier=Modifier.fillMaxWidth(),
            keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions=androidx.compose.foundation.text.KeyboardActions(onDone={if(valid) vm.startLocal(name)}))
        Spacer(Modifier.height(20.dp))
        Button(onClick={vm.startLocal(name)},enabled=valid,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(16.dp)) {Text("开始记录")}
        Row(Modifier.padding(top=22.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {TinyLabel("无需注册");TinyLabel("留在本机");TinyLabel("分享给 TA")}
        Text("日记保存在这台手机。\n想分享时，把最近的生活寄给 TA。",fontSize=11.sp,lineHeight=19.sp,color=Muted,modifier=Modifier.padding(top=14.dp))
        vm.notice.collectAsStateWithLifecycle().value?.let { Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp,modifier=Modifier.padding(top=12.dp)) }
    }
}

@Composable private fun HomeScreen(state: DiaryState,onWrite: ()->Unit,onOpen: (Diary)->Unit,onNote: (String)->Unit,onAll: ()->Unit,onAnniversaries: ()->Unit,onExchange: ()->Unit,onSpace: (String)->Unit) {
    var noteDialog by remember { mutableStateOf(false) }
    var historyDialog by remember { mutableStateOf(false) }
    val date=LocalDate.now()
    val shared=remember(state.diaries) {state.diaries.filter { ExchangeEngine.isPublic(it) }.sortedByDescending { it.date }}
    val mediaCount=remember(shared) {shared.sumOf {it.photos.size+it.videos.orEmpty().size+it.elements.count {e->e.isMedia()}}}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp)) {
        PageTitle("A LITTLE WORLD, JUST FOR US","我们的小日子",date.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日")),action={IconButton(onClick=onWrite) {Icon(Icons.Outlined.EditNote,"记录今天",tint=Sage)}})
        KeepsakeCard {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Row(verticalAlignment=Alignment.CenterVertically) { Avatar(state.profile.name);Text("  ♡  ",color=Blush,fontSize=18.sp);Avatar(state.profile.partner,true) }
                Column(Modifier.weight(1f).padding(start=12.dp),horizontalAlignment=Alignment.End) { Text("${state.profile.name} & ${state.profile.partner}",fontSize=13.sp,lineHeight=19.sp,maxLines=2,overflow=TextOverflow.Ellipsis,color=Ink,textAlign=TextAlign.End);Text(if(state.profile.startDate.isBlank()) "独立本地记录" else "从 ${state.profile.startDate.replace("-",".")} 开始",fontSize=10.sp,lineHeight=15.sp,color=Muted,textAlign=TextAlign.End,modifier=Modifier.padding(top=5.dp)) }
            }
            if(state.profile.startDate.isBlank()) {
                Text("慢慢记录，好好生活。",fontSize=25.sp,color=Ink,modifier=Modifier.padding(top=28.dp,bottom=16.dp))
                Text("在「我的」里设置日期，开始计算在一起的天数。",fontSize=11.sp,color=Sage)
            } else {
            Text("我们在一起",fontSize=12.sp,color=Sage,modifier=Modifier.padding(top=18.dp))
            Row(verticalAlignment=Alignment.Bottom) { Text(daysTogether(state.profile.startDate).toString(),fontFamily=FontFamily.Serif,fontSize=62.sp,color=Ink,letterSpacing=(-2).sp);Text("  天",fontSize=14.sp,color=Sage,modifier=Modifier.padding(bottom=15.dp)) }
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.primary.copy(alpha=.12f),modifier=Modifier.padding(top=8.dp,bottom=14.dp))
            Text("平凡的每一天，都值得被记住。",fontSize=11.sp,color=Sage)
        }
        Button(onClick=onExchange,modifier=Modifier.fillMaxWidth().padding(top=16.dp).height(52.dp),shape=RoundedCornerShape(16.dp)) {Icon(Icons.Outlined.MailOutline,null,Modifier.size(19.dp));Text("  寄给 TA / 收下回忆")}
        MemoryCounts(listOf(shared.size.toString() to "留存页",shared.count {it.visibility=="SHARED"}.toString() to "共同页",mediaCount.toString() to "影像"),Modifier.padding(top=12.dp))
        Row(Modifier.fillMaxWidth().padding(top=14.dp,bottom=4.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            val spaces=listOf(Triple("myspace","我的空间",Icons.Outlined.PersonOutline),Triple("partnerspace","TA 的空间",Icons.Outlined.FavoriteBorder),Triple("shared","共同空间",Icons.Outlined.MenuBook))
            spaces.forEach {(space,label,icon)->Surface(onClick={onSpace(space)},modifier=Modifier.weight(1f),shape=RoundedCornerShape(18.dp),color=Color.White,border=BorderStroke(1.dp,PaperEdge)) {
                Column(Modifier.padding(vertical=15.dp),horizontalAlignment=Alignment.CenterHorizontally) {IconTile(icon);Text(label,fontSize=11.sp,color=Ink,modifier=Modifier.padding(top=9.dp))}
            }}
        }
        state.exchange?.takeIf {it.lastReceivedAt>0}?.let {ex->Text("最近收下 ${state.profile.partner}的 ${ex.lastReceivedCount} 页更新",fontSize=11.sp,color=Sage,modifier=Modifier.padding(bottom=12.dp))}
        SectionHeading("今天想对你说","留一句话",onClick={noteDialog=true})
        val notes=state.notes.filter { it.date==today() }
        PaperCard(color=Color(0xFFF5EDE4)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Text("“",fontFamily=FontFamily.Serif,fontSize=34.sp,color=Blush);Spacer(Modifier.weight(1f));TinyLabel("TODAY'S NOTE",color=Color(0xFF956A59))}
            if(notes.isEmpty()) Text("今天，有什么小事想告诉 TA？",fontSize=17.sp,lineHeight=28.sp,color=Ink)
            notes.forEach { note ->
                Text(note.text,fontSize=17.sp,lineHeight=28.sp,color=Ink)
                Text(if(note.author=="me") "来自 ${state.profile.name}" else "来自 ${state.profile.partner} · 示例",fontSize=10.sp,color=Muted,modifier=Modifier.padding(top=8.dp,bottom=12.dp))
            }
            TextButton(onClick={historyDialog=true},contentPadding=PaddingValues(0.dp)) {Text("翻翻往日便签  →",fontSize=10.sp,color=Blush)}
        }
        Button(onClick=onWrite,modifier=Modifier.fillMaxWidth().padding(top=20.dp).height(50.dp),shape=RoundedCornerShape(15.dp)) {Icon(Icons.Outlined.Add,null,Modifier.size(18.dp));Text("  记录今天")}
        SectionHeading("我们的同一天")
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            listOf("me","partner").forEach { author ->
                val record=shared.firstOrNull {it.date==today() && it.authorId==author}
                val name=if(author=="me") state.profile.name else state.profile.partner
                Surface(onClick={if(record!=null) onOpen(record) else if(author=="me") onWrite()},modifier=Modifier.weight(1f),shape=RoundedCornerShape(18.dp),color=Color.White,border=BorderStroke(1.dp,PaperEdge)) {
                    Column(Modifier.padding(17.dp)) {
                        Avatar(name,author=="partner",32)
                        Text("${name}的今天",fontSize=12.sp,modifier=Modifier.padding(top=12.dp))
                        Text(record?.title ?: if(author=="me") "留下一点今天  +" else "等待 TA 寄来今天",fontSize=11.sp,color=Muted,lineHeight=18.sp,maxLines=2,modifier=Modifier.padding(top=8.dp))
                    }
                }
            }
        }
        SectionHeading("最近的我们","全部日记  →",onAll)
        if(shared.isEmpty()) EmptyState("故事从今天开始","写一篇日记，留下属于你们的瞬间。")
        shared.take(2).forEach { DiaryCard(it,state.profile) {onOpen(it)} }
        state.anniversaries.filter { daysUntil(it)>=0 }.minByOrNull { daysUntil(it) }?.let { a ->
            PaperCard(Modifier.padding(bottom=20.dp).clickable(onClick=onAnniversaries)) { Row(verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Outlined.Event,null,tint=Blush);Column(Modifier.weight(1f).padding(start=14.dp)) {Text(a.title,fontSize=13.sp);Text("下一次仪式感",fontSize=10.sp,color=Muted)};Text(if(daysUntil(a)==0L) "就是今天" else "${daysUntil(a)} 天",color=Sage,fontSize=18.sp) } }
        }
        Text("只属于两个人的生活收藏夹",fontSize=10.sp,color=Muted,modifier=Modifier.align(Alignment.CenterHorizontally).padding(vertical=18.dp))
    }
    if(noteDialog) TextInputDialog("今天想对你说",state.notes.find { it.date==today() && it.author=="me" }?.text ?: "",maxLength=120,onClose={noteDialog=false},onSave={onNote(it);noteDialog=false})
    if(historyDialog) AlertDialog(onDismissRequest={historyDialog=false},title={Text("一句一句，慢慢收藏")},text={LazyColumn(Modifier.heightIn(max=400.dp)) {
        if(state.notes.isEmpty()) item {Text("还没有便签，先给今天留一句话。",fontSize=12.sp,color=Muted)}
        items(state.notes.sortedByDescending {it.date}) {note -> Column {Text(note.date+" · "+if(note.author=="me") state.profile.name else state.profile.partner+"（示例）",fontSize=10.sp,color=Sage);Text(note.text,fontSize=14.sp,lineHeight=23.sp,modifier=Modifier.padding(top=7.dp,bottom=20.dp))}}
    }},confirmButton={TextButton(onClick={historyDialog=false}) {Text("收好")}})
}

@Composable private fun DiariesScreen(state: DiaryState,filter: String,onFilter: (String)->Unit,onOpen: (Diary)->Unit) {
    var search by rememberSaveable {mutableStateOf("")}
    val listState=rememberLazyListState()
    val all=remember(state.diaries,filter) {visibleDiaries(state.diaries,"me",filter)}
    val diaries=remember(all,search) {val query=search.trim();if(query.isBlank()) all else all.filter {it.title.contains(query,true) || it.body.contains(query,true) || it.date.contains(query)}}
    LaunchedEffect(filter,search) {listState.scrollToItem(0)}
    LazyColumn(Modifier.fillMaxSize(),state=listState,contentPadding=PaddingValues(horizontal=24.dp)) {
        item("heading") {
        PageTitle("PAGES OF OUR LIFE","一页一页，都是我们","一些瞬间，一些心情，一些不想忘记的事。")
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("MINE" to "我的","PARTNER" to "TA 的","SHARED" to "共同","PRIVATE" to "私密","FAVORITE" to "收藏").forEach { (value,label)->FilterChip(selected=filter==value,onClick={onFilter(value)},label={Text(label)},leadingIcon={if(value=="PRIVATE") Icon(Icons.Outlined.Lock,null,Modifier.size(14.dp))}) } }
        Text(when(filter) {"PRIVATE"->"仅自己可见 · 不会放进寄给 TA 的文件";"PARTNER"->"TA 主动寄来的生活，收下文件后更新";"SHARED"->"双方可继续编辑，寄出和收下后合入更新";else->"记录先留在本机，想分享时再寄给 TA"},fontSize=10.sp,color=Muted,modifier=Modifier.padding(top=10.dp,bottom=20.dp))
        OutlinedTextField(search,{search=it.take(100)},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),label={Text("搜索标题、正文或日期",fontSize=12.sp)},singleLine=true,leadingIcon={Icon(Icons.Outlined.Search,null,Modifier.size(20.dp))},trailingIcon={if(search.isNotEmpty()) IconButton(onClick={search=""}) {Icon(Icons.Outlined.Close,"清空搜索")}},colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=PaperEdge,unfocusedContainerColor=Color.White))
        Row(Modifier.fillMaxWidth().padding(vertical=18.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(if(search.isBlank()) "共 ${all.size} 页记录" else "找到 ${diaries.size} 页记录",fontSize=11.sp,color=Muted,modifier=Modifier.weight(1f))
            TinyLabel(when(filter) {"PRIVATE"->"只属于自己";"PARTNER"->"来自 TA";"SHARED"->"一起续写";"FAVORITE"->"珍藏瞬间";else->"我的生活"})
        }
        }
        if(diaries.isEmpty()) item("empty") {EmptyState(if(search.isNotBlank()) "没有找到这页" else if(filter=="FAVORITE") "还没有收藏的页" else "这一页，还等你来写",if(search.isNotBlank()) "试试标题里的字，或输入年份、月份。" else if(filter=="FAVORITE") "在日记里点星星，珍藏喜欢的瞬间。" else "点右下角的 +，写下今天。")}
        items(diaries,key={"diary-${it.id}"}) {DiaryCard(it,state.profile) {onOpen(it)}}
        item("footer") {Spacer(Modifier.height(90.dp))}
    }
}

@Composable fun TextInputDialog(title: String,initial: String="",maxLength: Int=500,onClose: ()->Unit,onSave: (String)->Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest=onClose,title={Text(title)},text={OutlinedTextField(value,{value=it.take(maxLength)},modifier=Modifier.fillMaxWidth(),minLines=3,supportingText={Text("${value.length} / $maxLength")})},confirmButton={TextButton(onClick={onSave(value)},enabled=value.isNotBlank()) {Text("保存")}},dismissButton={TextButton(onClick=onClose) {Text("取消")}})
}

@Composable private fun UnlockDialog(vm: DiaryViewModel,onClose: ()->Unit,onUnlock: ()->Unit) {
    var pin by remember { mutableStateOf("") };var error by remember { mutableStateOf("") };var attempts by remember { mutableIntStateOf(0) };var blockedUntil by remember { mutableLongStateOf(0L) }
    val activity=LocalActivity.current as? FragmentActivity ?: return
    val biometric=BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)==BiometricManager.BIOMETRIC_SUCCESS
    AlertDialog(onDismissRequest=onClose,title={Text("打开自己的小角落")},text={Column {
        Text("请输入私密 PIN",fontSize=13.sp,color=Muted)
        OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(8)},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.padding(top=14.dp),isError=error.isNotEmpty(),supportingText={Text(error)},keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.NumberPassword))
        if(biometric) TextButton(onClick={
            val prompt=BiometricPrompt(activity,ContextCompat.getMainExecutor(activity),object: BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onUnlock() }
                override fun onAuthenticationError(code: Int, message: CharSequence) { error=message.toString() }
            });prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("打开同页的私密日记").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("使用 PIN").build())
        }) {Icon(Icons.Outlined.Fingerprint,null);Text("  使用指纹 / 生物识别")}
    }},confirmButton={TextButton(onClick={
        if(System.currentTimeMillis()<blockedUntil) error="尝试过多，请稍后再试"
        else if(vm.checkPin(pin)) onUnlock()
        else {error=if(vm.pinWaitSeconds()>0) "请等待 ${vm.pinWaitSeconds()} 秒后再试" else "PIN 不正确";pin=""}
    },enabled=pin.length>=4) {Text("解锁")}},dismissButton={TextButton(onClick=onClose) {Text("取消")}})
}
