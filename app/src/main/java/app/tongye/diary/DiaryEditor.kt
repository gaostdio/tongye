package app.tongye.diary

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable fun DiaryEditor(initial: Diary,vm: DiaryViewModel,privateBlocked: Boolean,onBack: ()->Unit,onPrivacyChange: (String)->Unit,onSave: (Diary)->Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    val baseline = remember(initial.id) { initial }
    var selected by remember { mutableStateOf<String?>(null) }
    var tool by remember { mutableStateOf<String?>(null) }
    var leaveDialog by remember { mutableStateOf(false) }
    var shareDialog by remember { mutableStateOf(false) }
    var shareTarget by remember {mutableStateOf("SHARED")}
    var drawing by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var previewVideo by remember {mutableStateOf<String?>(null)}
    var penWidth by remember { mutableFloatStateOf(3f) }
    var penColor by remember { mutableLongStateOf(0xFF728477) }
    var past by remember { mutableStateOf<List<Diary>>(emptyList()) }
    var future by remember { mutableStateOf<List<Diary>>(emptyList()) }
    var lastGesture by remember { mutableLongStateOf(0L) }
    var sliderStart by remember { mutableStateOf<Diary?>(null) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(draft.visibility) {onPrivacyChange(draft.visibility)}
    val validDate=runCatching {LocalDate.parse(draft.date)}.isSuccess
    fun rememberChange() {past=(past+draft).takeLast(40);future=emptyList()}
    fun change(next: Diary) {rememberChange();draft=next}
    fun close() {if(draft!=baseline) leaveDialog=true else onBack()}
    fun addElement(e: Element) {change(draft.copy(elements=draft.elements+e.copy(zIndex=(draft.elements.maxOfOrNull { it.zIndex } ?: -1)+1)));selected=e.id;drawing=false}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris -> if(uris.isNotEmpty()) scope.launch {
        importing=true
        try {
            val imported=mutableListOf<ImportedMedia>();var failed=0
            uris.forEach {uri->try {imported+=vm.importMedia(uri)} catch(_: Exception) {failed++}}
            if(draft.isDiy) imported.forEachIndexed {index,media->addElement(Element(type=if(media.isVideo) "VIDEO" else "IMAGE",content=media.path,x=35f+index*14,y=60f+index*14,width=200f,height=190f))}
            else if(imported.isNotEmpty()) change(draft.copy(photos=draft.photos+imported.filterNot {it.isVideo}.map {it.path},videos=draft.videos.orEmpty()+imported.filter {it.isVideo}.map {it.path}))
            if(failed>0) vm.notify("已添加 ${imported.size} 个文件，$failed 个未能读取；视频请使用常见的 MP4 格式")
        } finally {importing=false}
    } }
    BackHandler {close()}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={close()}) {Icon(Icons.Outlined.ArrowBack,"返回")}
            Text(if(draft.isDiy) "我们的手账" else "记录今天",fontSize=17.sp,modifier=Modifier.weight(1f))
            TextButton(onClick={onSave(draft)},enabled=draft.title.isNotBlank() && validDate && !importing) {Text("保存")}
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=22.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=draft.visibility=="PRIVATE",onClick={change(draft.copy(visibility="PRIVATE"))},enabled=draft.authorId=="me",label={Text("仅自己",fontSize=11.sp)},leadingIcon={Icon(Icons.Outlined.Lock,null,Modifier.size(14.dp))})
                FilterChip(selected=draft.visibility=="PARTNER",onClick={if(draft.visibility=="PRIVATE") {shareTarget="PARTNER";shareDialog=true} else change(draft.copy(visibility="PARTNER"))},enabled=draft.authorId=="me",label={Text("寄给 TA",fontSize=11.sp)})
                FilterChip(selected=draft.visibility=="SHARED",onClick={if(draft.visibility=="PRIVATE") {shareTarget="SHARED";shareDialog=true} else change(draft.copy(visibility="SHARED"))},label={Text("共同页",fontSize=11.sp)},leadingIcon={Icon(Icons.Outlined.FavoriteBorder,null,Modifier.size(14.dp))})
            }
            Text(when(draft.visibility) {"PRIVATE"->"只留在本机，不会寄出。";"PARTNER"->"点击「寄给 TA」时加入文件，TA 收下后可阅读。";else->"双方都能继续编辑，交换文件时合入修改。"},color=Muted,fontSize=10.sp,modifier=Modifier.padding(vertical=9.dp))
            OutlinedTextField(draft.title,{change(draft.copy(title=it.take(80)))},placeholder={Text("给今天起个名字")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp))
            Row(Modifier.padding(top=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(draft.date,{if(it.length<=10) change(draft.copy(date=it))},label={Text("日期")},singleLine=true,modifier=Modifier.weight(1f),isError=runCatching {LocalDate.parse(draft.date)}.isFailure)
                FilterChip(selected=true,onClick={tool="心情"},label={Text(draft.mood,fontSize=11.sp)})
            }
            if(draft.isDiy) {
                Row(Modifier.fillMaxWidth().padding(top=16.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(drawing) "画笔模式 · 在纸上画一画" else "拖动位置 · 双指缩放、旋转",fontSize=10.sp,color=Muted,modifier=Modifier.weight(1f))
                    IconButton(onClick={if(past.isNotEmpty()) {future=future+draft;draft=past.last();past=past.dropLast(1);selected=null}},enabled=past.isNotEmpty(),modifier=Modifier.size(32.dp)) {Icon(Icons.Outlined.Undo,"撤销",Modifier.size(18.dp))}
                    IconButton(onClick={if(future.isNotEmpty()) {past=past+draft;draft=future.last();future=future.dropLast(1);selected=null}},enabled=future.isNotEmpty(),modifier=Modifier.size(32.dp)) {Icon(Icons.Outlined.Redo,"重做",Modifier.size(18.dp))}
                }
                CanvasPage(draft,selected=selected,interactive=true,drawing=drawing,penColor=penColor,penWidth=penWidth,onSelect={selected=it},onGestureStart={
                    val now=System.currentTimeMillis();if(now-lastGesture>500) rememberChange();lastGesture=now
                },onTransform={id,dx,dy,zoom,rot -> draft=draft.copy(elements=draft.elements.map {e->if(e.id==id) e.copy(x=(e.x+dx).coerceIn(-e.width/2,340f),y=(e.y+dy).coerceIn(-e.height/2,460f),scale=(e.scale*zoom).coerceIn(.3f,2.5f),rotation=e.rotation+rot,updatedBy="me",updatedAt=System.currentTimeMillis()) else e}) },onStroke={change(draft.copy(strokes=draft.strokes+it))})
                selected?.let { id -> draft.elements.find {it.id==id}?.let { e ->
                    PaperCard(Modifier.padding(top=12.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(when(e.type) {"IMAGE"->"照片 / 动图";"VIDEO"->"视频";"STICKER"->"贴纸";else->"文字"},fontSize=12.sp,modifier=Modifier.weight(1f))
                            if(e.type=="VIDEO") IconButton(onClick={previewVideo=e.content},modifier=Modifier.size(34.dp)) {Icon(Icons.Outlined.PlayCircleOutline,"播放视频",Modifier.size(18.dp))}
                            if(e.type=="TEXT") IconButton(onClick={tool="编辑文字"},modifier=Modifier.size(34.dp)) {Icon(Icons.Outlined.Edit,"修改文字",Modifier.size(18.dp))}
                            IconButton(onClick={change(draft.copy(elements=draft.elements.map {if(it.id==id) it.copy(zIndex=(draft.elements.maxOfOrNull {it.zIndex} ?: 0)+1) else it}))},modifier=Modifier.size(34.dp)) {Icon(Icons.Outlined.FlipToFront,"移到最上层",Modifier.size(18.dp))}
                            IconButton(onClick={change(draft.copy(elements=draft.elements.filterNot {it.id==id}));selected=null},modifier=Modifier.size(34.dp)) {Icon(Icons.Outlined.DeleteOutline,"删除元素",Modifier.size(18.dp),tint=Blush)}
                        }
                        Text("大小",fontSize=10.sp,color=Muted)
                        Slider(value=e.scale,onValueChange={v->if(sliderStart==null) sliderStart=draft;draft=draft.copy(elements=draft.elements.map {if(it.id==id) it.copy(scale=v,updatedBy="me",updatedAt=System.currentTimeMillis()) else it})},valueRange=.3f..2.5f,onValueChangeFinished={sliderStart?.let {past=(past+it).takeLast(40);future=emptyList()};sliderStart=null})
                        Text("旋转 ${e.rotation.toInt()}°",fontSize=10.sp,color=Muted)
                        Slider(value=e.rotation.coerceIn(-180f,180f),onValueChange={v->if(sliderStart==null) sliderStart=draft;draft=draft.copy(elements=draft.elements.map {if(it.id==id) it.copy(rotation=v,updatedBy="me",updatedAt=System.currentTimeMillis()) else it})},valueRange=-180f..180f,onValueChangeFinished={sliderStart?.let {past=(past+it).takeLast(40);future=emptyList()};sliderStart=null})
                        if(e.type=="TEXT") {
                            Text("字号 ${e.fontSize.toInt()}",fontSize=10.sp,color=Muted)
                            Slider(e.fontSize,{v->if(sliderStart==null) sliderStart=draft;draft=draft.copy(elements=draft.elements.map {if(it.id==id) it.copy(fontSize=v,updatedBy="me",updatedAt=System.currentTimeMillis()) else it})},valueRange=12f..40f,onValueChangeFinished={sliderStart?.let {past=(past+it).takeLast(40);future=emptyList()};sliderStart=null})
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf(0xFF444B43,0xFF728477,0xFFCA9987,0xFF74899E,0xFFF8F3E9).forEach { c -> FilledIconButton(onClick={change(draft.copy(elements=draft.elements.map {if(it.id==id) it.copy(color=c) else it}))},colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(c)),modifier=Modifier.size(24.dp)) {} } }
                        }
                        Text("${if(e.createdBy=="me") "你" else "TA"}添加的元素",fontSize=10.sp,color=Muted,modifier=Modifier.padding(top=8.dp))
                    }
                } }
                if(drawing) PaperCard(Modifier.padding(top=10.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) { Text("画笔粗细",fontSize=11.sp,modifier=Modifier.weight(1f));TextButton(onClick={drawing=false}) {Text("完成绘画")};IconButton(onClick={if(draft.strokes.isNotEmpty()) change(draft.copy(strokes=draft.strokes.dropLast(1)))}) {Icon(Icons.Outlined.Backspace,"擦除上一笔",Modifier.size(18.dp))} }
                    Slider(penWidth,{penWidth=it},valueRange=1f..12f)
                    Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) {listOf(0xFF728477,0xFFCA9987,0xFF444B43,0xFF74899E).forEach {c->FilledIconButton(onClick={penColor=c},modifier=Modifier.size(28.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(c))) {}}}
                }
            }
            OutlinedTextField(draft.body,{change(draft.copy(body=it.take(20000)))},placeholder={Text(if(draft.isDiy) "为这一页留下一句话…" else "今天，有哪些想留下的小事？")},modifier=Modifier.fillMaxWidth().padding(top=16.dp),minLines=if(draft.isDiy) 2 else 8,shape=RoundedCornerShape(12.dp))
            if(!draft.isDiy) {
                draft.photos.forEach {path->Box(Modifier.padding(top=12.dp)) {PhotoView(path,Modifier.fillMaxWidth().height(220.dp));FilledIconButton(onClick={change(draft.copy(photos=draft.photos-path))},modifier=Modifier.align(Alignment.TopEnd).padding(8.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color.White)) {Icon(Icons.Outlined.Close,"移除照片")}}}
                draft.videos.orEmpty().forEach {path->Box(Modifier.padding(top=12.dp)) {VideoPreview(path,Modifier.fillMaxWidth().height(220.dp));FilledIconButton(onClick={change(draft.copy(videos=draft.videos.orEmpty()-path))},modifier=Modifier.align(Alignment.TopEnd).padding(8.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color.White)) {Icon(Icons.Outlined.Close,"移除视频")}}}
                OutlinedButton(onClick={picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))},enabled=!importing,modifier=Modifier.fillMaxWidth().padding(top=14.dp)) {Icon(Icons.Outlined.AddPhotoAlternate,null);Text("  添加照片、动图或视频")}
                Text("动图自动播放，视频点击播放；文件保存在本机。",fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=8.dp))
            }
            if(importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=14.dp))
            Spacer(Modifier.height(24.dp))
        }
        if(draft.isDiy) Surface(color=Paper,tonalElevation=2.dp) {
            Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.SpaceEvenly) {
                val items=listOf("图片/视频" to Icons.Outlined.AddPhotoAlternate,"文字" to Icons.Outlined.Title,"贴纸" to Icons.Outlined.AutoAwesome,"画笔" to Icons.Outlined.Brush,"背景" to Icons.Outlined.Palette,"模板" to Icons.Outlined.DashboardCustomize)
                items.forEach { (label,icon)->Column(horizontalAlignment=Alignment.CenterHorizontally) {IconButton(onClick={when(label) {"图片/视频"->if(!importing) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo));"画笔"->{drawing=!drawing;selected=null};else->{tool=label;drawing=false}}}) {Icon(icon,label,tint=if(label=="画笔" && drawing) Blush else Sage,modifier=Modifier.size(21.dp))};Text(label,fontSize=10.sp,color=Muted)} }
            }
        }
    }
    if(!privateBlocked && leaveDialog) AlertDialog(onDismissRequest={leaveDialog=false},title={Text("这一页还没有保存")},text={Text("返回会丢弃本次修改。")},confirmButton={TextButton(onClick=onBack) {Text("丢弃修改")}},dismissButton={TextButton(onClick={leaveDialog=false}) {Text("继续写")}})
    if(!privateBlocked && shareDialog) AlertDialog(onDismissRequest={shareDialog=false},title={Text("愿意把这页寄给 TA 吗？")},text={Text("保存后，这页的文字、照片和画布会加入下次寄出的文件。")},confirmButton={TextButton(onClick={change(draft.copy(visibility=shareTarget));shareDialog=false}) {Text("允许分享")}},dismissButton={TextButton(onClick={shareDialog=false}) {Text("保留私密")}})
    if(!privateBlocked) previewVideo?.let {path->VideoDialog(path) {previewVideo=null}}
    when(if(privateBlocked) null else tool) {
        "文字","编辑文字" -> TextInputDialog(if(tool=="文字") "添加一句话" else "修改文字",if(tool=="编辑文字") draft.elements.find {it.id==selected}?.content ?: "" else "",maxLength=300,onClose={tool=null},onSave={text->if(tool=="文字") addElement(Element(content=text,width=240f,height=130f)) else change(draft.copy(elements=draft.elements.map {if(it.id==selected) it.copy(content=text,updatedAt=System.currentTimeMillis()) else it}));tool=null})
        "贴纸" -> AlertDialog(onDismissRequest={tool=null},title={Text("贴一点小心情")},text={Column {listOf(listOf("♡","✿","✧","☁"),listOf("★","☀","☾","☺"),listOf("🌷","🐻","🎀","💌")).forEach {row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {row.forEach {sticker->TextButton(onClick={addElement(Element(type="STICKER",content=sticker,width=80f,height=80f,fontSize=48f,color=0xFFCA9987));tool=null}) {Text(sticker,fontSize=28.sp)}}}}}},confirmButton={TextButton(onClick={tool=null}) {Text("完成")}})
        "背景" -> AlertDialog(
            onDismissRequest={tool=null}, title={Text("选一张喜欢的纸")},
            text={ Column {
                paperColors.keys.toList().chunked(2).forEach { row ->
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        row.forEach { name -> FilterChip(selected=draft.background==name,onClick={change(draft.copy(background=name));tool=null},label={Text(name)}) }
                    }
                }
            } }, confirmButton={TextButton(onClick={tool=null}) {Text("完成")}}
        )
        "心情" -> AlertDialog(onDismissRequest={tool=null},title={Text("今天的心情")},text={Column {listOf("晴朗","开心","想你","平静","疲惫","难过").chunked(3).forEach {row->Row {row.forEach {mood->TextButton(onClick={change(draft.copy(mood=mood));tool=null}) {Text(mood)}}}}}},confirmButton={})
        "模板" -> AlertDialog(onDismissRequest={tool=null},title={Text("从一张模板开始")},text={Column {
            Text("选择模板会替换当前画布，可用撤销恢复。",fontSize=12.sp,color=Muted)
            listOf("拍立得","情书","旅行","纪念日").forEach {name->TextButton(onClick={
                val elements=when(name) {
                    "情书"->listOf(Element(content="To：亲爱的你",x=32f,y=55f,width=290f,height=50f,fontSize=23f),Element(content="今天也很喜欢你。\n还有明天，和很多很多天。",x=32f,y=150f,width=285f,height=180f),Element(content="From：我",x=190f,y=370f,width=160f,height=60f))
                    "纪念日"->listOf(Element(content="我们在一起",x=80f,y=65f,width=240f,height=60f),Element(content="${daysTogether(vm.state.value!!.profile.startDate)} 天",x=65f,y=145f,width=280f,height=120f,fontSize=40f),Element(type="STICKER",content="♡",x=140f,y=300f,width=100f,height=100f,fontSize=70f,color=0xFFCA9987))
                    else->listOf(Element(type="IMAGE",content=if(name=="旅行") "sample:lake" else "sample:coffee",x=55f,y=55f,width=250f,height=240f,rotation=-4f),Element(content=if(name=="旅行") "和你，去看更大的世界。" else "收藏一个很好的今天。",x=45f,y=355f,width=280f,height=90f),Element(type="STICKER",content="✧",x=280f,y=270f,width=70f,height=70f,fontSize=50f,color=0xFFCA9987))
                };change(draft.copy(elements=elements.mapIndexed {index,e->e.copy(zIndex=index)},strokes=emptyList()));selected=null;tool=null
            },modifier=Modifier.fillMaxWidth()) {Text(name)} }
        }},confirmButton={TextButton(onClick={tool=null}) {Text("取消")}})
    }
}
