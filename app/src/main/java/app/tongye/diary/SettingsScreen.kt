package app.tongye.diary

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable fun SettingsScreen(state: DiaryState,vm: DiaryViewModel,onProtected: (()->Unit)->Unit,onExchange: ()->Unit) {
    var dialog by remember {mutableStateOf<String?>(null)}
    var includePrivate by remember {mutableStateOf(false)}
    var exporting by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    val version=remember(context) {context.packageManager.getPackageInfo(context.packageName,0).versionName}
    val progress by vm.transferProgress.collectAsState()
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {uri->uri?.let {scope.launch {exporting=true;try {vm.export(it,includePrivate);vm.notify("回忆已导出，请妥善保存备份")} catch(e: IllegalArgumentException) {vm.notify(e.message ?: "备份未完成，请重试")} catch(_: Exception) {vm.notify("备份未完成，请检查手机和保存位置的剩余空间后重试")} finally {exporting=false}}}}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp)) {
        PageTitle("MY OWN LITTLE CORNER","我的小角落","生活的颜色，交给你来选择。")
        KeepsakeCard {
            Row(verticalAlignment=Alignment.CenterVertically) {Avatar(state.profile.name,size=58);Column(Modifier.weight(1f).padding(start=17.dp)) {Text(state.profile.name,fontSize=22.sp,fontWeight=FontWeight.Medium);Text("和 ${state.profile.partner}，慢慢记录生活。",fontSize=12.sp,lineHeight=20.sp,color=Muted,modifier=Modifier.padding(top=6.dp))}}
            Row(Modifier.padding(top=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {TinyLabel("同页 · 本地交换");TinyLabel(state.profile.theme)}
        }
        SectionHeading("我们的空间")
        PaperCard {
            SettingRow(Icons.Outlined.PersonOutline,"你的昵称",state.profile.name) {dialog="昵称"}
            SettingRow(Icons.Outlined.FavoriteBorder,"TA 的昵称",state.profile.partner) {dialog="TA昵称"}
            SettingRow(Icons.Outlined.CalendarToday,"在一起的日期",state.profile.startDate.ifBlank { "未设置" }) {dialog="日期"}
            SettingRow(Icons.Outlined.MailOutline,"交换最近的生活",if(state.exchange?.code.isNullOrBlank()) "和 TA 关联" else "寄出 / 收下") {onExchange()}
        }
        SectionHeading("让这里更像你")
        PaperCard {
            SettingRow(Icons.Outlined.Palette,"主题颜色",state.profile.theme) {dialog="主题"}
            Row(Modifier.fillMaxWidth().padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {IconTile(Icons.Outlined.Lock);Column(Modifier.weight(1f).padding(start=13.dp)) {Text("私密日记锁",fontSize=13.sp);Text("PIN + 可用的生物识别",fontSize=10.sp,color=Muted,modifier=Modifier.padding(top=5.dp))};Switch(checked=state.profile.lockEnabled,onCheckedChange={enabled->if(enabled) dialog="PIN" else onProtected {vm.profile(state.profile.copy(lockEnabled=false))}})}
            if(state.profile.lockEnabled) SettingRow(Icons.Outlined.Password,"修改 PIN","") {onProtected {dialog="PIN"}}
        }
        SectionHeading("好好保存我们的回忆")
        PaperCard {
            SettingRow(Icons.Outlined.FileDownload,"导出数据与影像",if(exporting) progress ?: "正在导出…" else "照片、动图、视频 · ZIP 备份") {if(!exporting) {includePrivate=false;dialog="备份"}}
            if(state.diaries.any {it.id.startsWith("sample-")}) SettingRow(Icons.Outlined.AutoAwesome,"移除示例日记","") {dialog="清理示例"}
            SettingRow(Icons.Outlined.Info,"数据与隐私说明","") {dialog="隐私"}
        }
        Text("同页 $version\n把生活写在同一页。",fontSize=10.sp,lineHeight=19.sp,color=Muted,modifier=Modifier.align(Alignment.CenterHorizontally).padding(vertical=30.dp))
    }
    when(dialog) {
        "昵称","TA昵称" -> TextInputDialog(if(dialog=="昵称") "你的昵称" else "TA 的昵称",if(dialog=="昵称") state.profile.name else state.profile.partner,maxLength=12,onClose={dialog=null},onSave={vm.profile(if(dialog=="昵称") state.profile.copy(name=it.trim()) else state.profile.copy(partner=it.trim()));dialog=null})
        "日期" -> {var date by remember {mutableStateOf(state.profile.startDate.ifBlank { today() })};val valid=runCatching {!LocalDate.parse(date).isAfter(LocalDate.now())}.getOrDefault(false);AlertDialog(onDismissRequest={dialog=null},title={Text("故事开始的那一天")},text={OutlinedTextField(date,{date=it.take(10)},label={Text("YYYY-MM-DD")},singleLine=true,isError=!valid)},confirmButton={TextButton(onClick={vm.profile(state.profile.copy(startDate=date));dialog=null},enabled=valid) {Text("保存")}},dismissButton={TextButton(onClick={dialog=null}) {Text("取消")}})}
        "主题" -> AlertDialog(onDismissRequest={dialog=null},title={Text("一点喜欢的颜色")},text={Column {listOf("鼠尾草" to Sage,"雾粉" to Color(0xFF8E6059),"雾蓝" to Color(0xFF4E6B85)).forEach {(name,color)->TextButton(onClick={vm.profile(state.profile.copy(theme=name));dialog=null},modifier=Modifier.fillMaxWidth()) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(color.copy(alpha=.65f)));Text(name,Modifier.weight(1f).padding(start=14.dp));if(state.profile.theme==name) Icon(Icons.Outlined.Check,"已选择",Modifier.size(19.dp),tint=color)
        }}}},confirmButton={})
        "隐私" -> AlertDialog(onDismissRequest={dialog=null},title={Text("你的回忆，保存在这里")},text={Text("日记与照片存储在本机。同页不连接日记服务器；只有你点击寄出，才会生成加密 .couple 文件交给所选的分享应用。私密页不会进入互传文件。\n\n双方使用同一个口令解密，口令只给 TA。卸载会删除本地数据，请先备份。\n\n私密锁保护应用内访问，数据库没有内容加密。私密页防截图，回到后台后重锁。\n\n「ZIP 备份」未加密，与寄给 TA 的文件用途不同。",fontSize=12.sp,lineHeight=22.sp)},confirmButton={TextButton(onClick={dialog=null}) {Text("知道了")}})
        "PIN" -> {var pin by remember {mutableStateOf("")};var confirm by remember {mutableStateOf("")};AlertDialog(onDismissRequest={dialog=null},title={Text("设置私密 PIN")},text={Column {
            Text("输入 4–8 位数字。请牢记 PIN，本地版没有账号找回服务。",fontSize=12.sp,color=Muted)
            OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(8)},label={Text("PIN")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.NumberPassword),singleLine=true)
            Spacer(Modifier.height(12.dp));OutlinedTextField(confirm,{confirm=it.filter(Char::isDigit).take(8)},label={Text("再次输入")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.NumberPassword),singleLine=true)
        }},confirmButton={TextButton(onClick={vm.setPin(pin);vm.profile(state.profile.copy(lockEnabled=true));dialog=null;vm.notify("私密日记锁已开启")},enabled=pin.length>=4 && pin==confirm) {Text("开启")}},dismissButton={TextButton(onClick={dialog=null}) {Text("取消")}})}
        "备份" -> AlertDialog(onDismissRequest={dialog=null},title={Text("把回忆带走")},text={Column {
            Text("导出日记数据及原始照片、动图、视频为 ZIP。默认导出可分享内容；备份文件未加密。寄给 TA 请使用「交换最近的生活」。",fontSize=13.sp,lineHeight=22.sp)
            Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(includePrivate,{includePrivate=it});Text("同时导出我的私密日记",fontSize=12.sp)}
        }},confirmButton={TextButton(onClick={dialog=null;val launch={export.launch("同页回忆-${today()}.zip")};if(includePrivate) onProtected(launch) else launch()}) {Text("选择保存位置")}},dismissButton={TextButton(onClick={dialog=null}) {Text("取消")}})
        "清理示例" -> AlertDialog(onDismissRequest={dialog=null},title={Text("移除体验示例？")},text={Text("移除首次进入时加入的示例日记与示例便签，也会移除你对这些示例页的修改。你新建的日记不会受影响。")},confirmButton={TextButton(onClick={vm.clearSamples();dialog=null}) {Text("移除示例")}},dismissButton={TextButton(onClick={dialog=null}) {Text("取消")}})
    }
}
@Composable private fun SettingRow(icon: ImageVector,title: String,value: String,onClick: ()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=15.dp),verticalAlignment=Alignment.CenterVertically) {
        IconTile(icon)
        Column(Modifier.weight(1f).padding(start=12.dp,end=8.dp)) {Text(title,fontSize=13.sp,color=Ink);if(value.isNotBlank()) Text(value,fontSize=11.sp,lineHeight=18.sp,color=Muted,modifier=Modifier.padding(top=4.dp))}
        Icon(Icons.Outlined.ChevronRight,null,Modifier.size(17.dp),tint=Muted)
    }
}
