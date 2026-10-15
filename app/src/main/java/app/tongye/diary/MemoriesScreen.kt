package app.tongye.diary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun MemoriesScreen(state: DiaryState,vm: DiaryViewModel,onOpen: (Diary)->Unit) {
    var tab by remember {mutableIntStateOf(0)}
    var addAnniversary by remember {mutableStateOf(false)}
    var deleting by remember {mutableStateOf<Anniversary?>(null)}
    var month by remember {mutableStateOf(YearMonth.now())}
    val shared=remember(state.diaries) {state.diaries.filter {it.visibility=="SHARED"}.sortedByDescending {it.date}}
    val photos=remember(shared) {shared.flatMap {d->d.mediaPaths().map {it to d}}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=24.dp)) {
        item("heading") {
        PageTitle("LITTLE THINGS, FOREVER KEPT","我们的回忆","把走过的日子，再温柔地翻一遍。")
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("相册","时间轴","手账","月度","纪念日").forEachIndexed {i,label->FilterChip(selected=tab==i,onClick={tab=i},label={Text(label,fontSize=12.sp)})}}
        MemoryCounts(listOf(shared.size.toString() to "共同记录",photos.size.toString() to "收藏影像",shared.count {it.isDiy}.toString() to "手账页"),Modifier.padding(top=12.dp,bottom=20.dp))
        }
        when(tab) {
            0 -> {
                item("photo-count") {Text("${photos.size} 个图片 / 视频 · 仅共享日记",fontSize=10.sp,color=Muted,modifier=Modifier.padding(bottom=16.dp))}
                if(photos.isEmpty()) item("photos-empty") {EmptyState("把喜欢的瞬间留下来","在共同日记或手账里添加照片、动图、视频，会出现在这里。")}
                items(photos.chunked(2)) {row->Row(Modifier.fillMaxWidth().padding(bottom=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {row.forEach {(path,diary)->Surface(onClick={onOpen(diary)},modifier=Modifier.weight(1f),shape=RoundedCornerShape(18.dp),color=Color.White,border=BorderStroke(1.dp,PaperEdge)) {
                    Column(Modifier.padding(8.dp)) {
                        MediaPreview(path,diary.isVideoPath(path),Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),playable=false)
                        Text(diary.title,fontSize=12.sp,color=Ink,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=10.dp,start=3.dp,end=3.dp))
                        Text(diary.date,fontSize=10.sp,color=Muted,modifier=Modifier.padding(top=4.dp,start=3.dp,bottom=6.dp))
                    }
                }};if(row.size==1) Spacer(Modifier.weight(1f))}}
            }
            1 -> {
                if(shared.isEmpty()) item("timeline-empty") {EmptyState("我们的故事，未完待续","每一篇共享日记，都是时间轴上的一个瞬间。")}
                shared.groupBy {it.date.take(7)}.forEach {(period,diaries)->item("month-$period") {Text(period.replace("-"," 年 ")+" 月",fontFamily=FontFamily.Serif,fontSize=23.sp,color=Sage,modifier=Modifier.padding(vertical=15.dp))};items(diaries,key={"timeline-${it.id}"}) {DiaryCard(it,state.profile) {onOpen(it)}}}
            }
            2 -> {
                val pages=shared.filter {it.isDiy}
                if(pages.isEmpty()) item("diy-empty") {EmptyState("一起写下第一张手账","日记页点 +，选择自由手账。")}
                items(pages,key={"diy-${it.id}"}) {d->Column(Modifier.padding(bottom=22.dp).clickable {onOpen(d)}) {CanvasPage(d);Text(d.title,fontSize=14.sp,modifier=Modifier.padding(top=11.dp));Text(d.date,fontSize=10.sp,color=Muted,modifier=Modifier.padding(top=5.dp))}}
            }
            3 -> item("monthly") {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {IconButton(onClick={month=month.minusMonths(1)}) {Icon(Icons.Outlined.ChevronLeft,"上个月")};Text("${month.year} / ${month.monthValue}",modifier=Modifier.weight(1f),fontSize=15.sp);IconButton(onClick={month=month.plusMonths(1)},enabled=month<YearMonth.now()) {Icon(Icons.Outlined.ChevronRight,"下个月")}}
                val records=shared.filter {it.date.startsWith(month.toString())}
                val monthPhotos=photos.filter {it.second.date.startsWith(month.toString())}
                val notes=state.notes.filter {it.date.startsWith(month.toString())}
                PaperCard(color=Color(0xFFEAEEE5)) {
                    Text(month.month.getDisplayName(java.time.format.TextStyle.FULL,Locale.ENGLISH).uppercase(),fontSize=11.sp,letterSpacing=3.sp,color=Sage)
                    Text("我们的 ${month.monthValue} 月",fontSize=30.sp,fontFamily=FontFamily.Serif,modifier=Modifier.padding(vertical=18.dp))
                    listOf("一起记录" to "${records.map {it.date}.distinct().size} 天","保存影像" to "${monthPhotos.size} 个","制作手账" to "${records.count {it.isDiy}} 页","留下便签" to "${notes.size} 句").forEach {(label,value)->Row(Modifier.fillMaxWidth().padding(vertical=8.dp)) {Text(label,fontSize=12.sp,color=Muted,modifier=Modifier.weight(1f));Text(value,fontSize=14.sp,color=Ink)}}
                }
                if(monthPhotos.isNotEmpty()) {SectionHeading("这个月的几个瞬间");monthPhotos.take(6).forEach {(path,d)->MediaPreview(path,d.isVideoPath(path),Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(16.dp)).clickable {onOpen(d)}.padding(bottom=12.dp),playable=false)}}
                if(records.isEmpty()) Text("这个月还没有共享记录，慢慢写就好。",fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=18.dp))
            }
            4 -> {
                item("anniversary-heading") {SectionHeading("给平凡的日子一点仪式感","＋ 添加",onClick={addAnniversary=true})}
                items(state.anniversaries.sortedBy {daysUntil(it)},key={"anniversary-${it.id}"}) {a->PaperCard(Modifier.padding(bottom=12.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {Icon(Icons.Outlined.Event,null,tint=Blush);Column(Modifier.weight(1f).padding(start=14.dp)) {Text(a.title,fontSize=15.sp);Text(a.date + if(a.yearly) " · 每年" else " · 一次",fontSize=10.sp,color=Muted,modifier=Modifier.padding(top=6.dp))};val days=daysUntil(a);Text(when {days==0L->"今天";days<0->"已过 ${-days} 天";else->"还有 $days 天"},fontSize=13.sp,color=Sage);IconButton(onClick={deleting=a},modifier=Modifier.size(30.dp)) {Icon(Icons.Outlined.Close,"删除纪念日",Modifier.size(15.dp),tint=Muted)} }
                } }
                if(state.anniversaries.isEmpty()) item("anniversary-empty") {EmptyState("有些日子，很特别","记下见面、生日，或者属于你们的纪念日。")}
            }
        }
        item("footer") {Spacer(Modifier.height(28.dp))}
    }
    if(addAnniversary) AnniversaryDialog(onClose={addAnniversary=false},onSave={vm.addAnniversary(it);addAnniversary=false})
    deleting?.let {a->AlertDialog(onDismissRequest={deleting=null},title={Text("移除这个纪念日？")},text={Text(a.title)},confirmButton={TextButton(onClick={vm.deleteAnniversary(a.id);deleting=null}) {Text("移除")}},dismissButton={TextButton(onClick={deleting=null}) {Text("取消")}})}
}
@Composable private fun AnniversaryDialog(onClose: ()->Unit,onSave: (Anniversary)->Unit) {
    var title by remember {mutableStateOf("")};var date by remember {mutableStateOf(today())};var yearly by remember {mutableStateOf(true)}
    val valid=title.isNotBlank() && runCatching {LocalDate.parse(date)}.isSuccess
    AlertDialog(onDismissRequest=onClose,title={Text("一个特别的日子")},text={Column {
        OutlinedTextField(title,{title=it.take(30)},label={Text("纪念日名称")},singleLine=true)
        Spacer(Modifier.height(12.dp));OutlinedTextField(date,{date=it.take(10)},label={Text("日期 · YYYY-MM-DD")},singleLine=true,isError=runCatching {LocalDate.parse(date)}.isFailure)
        Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(yearly,{yearly=it});Text("每年纪念",fontSize=13.sp)}
    }},confirmButton={TextButton(onClick={onSave(Anniversary(title=title.trim(),date=date,yearly=yearly))},enabled=valid) {Text("保存")}},dismissButton={TextButton(onClick=onClose) {Text("取消")}})
}
