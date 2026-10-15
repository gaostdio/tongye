package app.tongye.diary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun DiaryDetail(diary: Diary,profile: Profile,vm: DiaryViewModel,onBack: ()->Unit,onEdit: ()->Unit,onDelete: ()->Unit) {
    var deleteDialog by remember {mutableStateOf(false)}
    var deleting by remember {mutableStateOf(false)}
    var commentText by rememberSaveable(diary.id) {mutableStateOf("")}
    var sendingComment by remember(diary.id) {mutableStateOf(false)}
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack) {Icon(Icons.Outlined.ArrowBack,"返回")}
            Text(when(diary.visibility) {"PRIVATE"->"我的私密页";"PARTNER"->if(diary.authorId=="me") "我的日记" else "TA 寄来的日记";else->"我们的共同页"},fontSize=14.sp,modifier=Modifier.weight(1f),color=Muted)
            IconButton(onClick={vm.favorite(diary.id)}) {Icon(if(diary.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,"收藏",tint=Blush)}
            if(ExchangeEngine.canEdit(diary)) {
                IconButton(onClick=onEdit) {Icon(Icons.Outlined.EditNote,"编辑",tint=Sage)}
                TextButton(onClick={deleteDialog=true},enabled=!deleting) {Text("删除",color=Blush)}
            }
        }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=24.dp)) {
          item {
            Column {
            PageTitle(diary.date.replace("-"," / "),diary.title)
            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.padding(bottom=20.dp)) {
                Avatar(if(diary.authorId=="me") profile.name else profile.partner,diary.authorId!="me",28)
                Text("  ${if(diary.authorId=="me") profile.name else profile.partner}${if(diary.id.startsWith("sample-")) " · 示例" else ""}",fontSize=11.sp,color=Muted,modifier=Modifier.weight(1f))
                Text(diary.mood,fontSize=11.sp,color=Blush)
            }
            if(diary.isDiy) CanvasPage(diary)
            if(diary.isDiy && diary.authorId=="me" && diary.visibility!="PRIVATE") TextButton(onClick={vm.profile(profile.copy(homePageId=diary.id));vm.notify("已设为我的空间封面，下次寄出会带给 TA")}) {Text("用作我的空间封面",fontSize=12.sp)}
            if(diary.body.isNotBlank()) PaperCard(Modifier.padding(top=12.dp,bottom=12.dp),color=Color(0xFFFFFDF9)) {Text(diary.body,fontSize=15.sp,lineHeight=29.sp,color=Ink)}
            diary.photos.forEach {PhotoView(it,Modifier.fillMaxWidth().padding(vertical=8.dp).height(260.dp).clip(RoundedCornerShape(18.dp)))}
            diary.videos.orEmpty().forEach {VideoPreview(it,Modifier.fillMaxWidth().padding(vertical=8.dp).height(240.dp).clip(RoundedCornerShape(18.dp)))}
            if(diary.visibility=="SHARED") {
                HorizontalDivider(Modifier.padding(top=26.dp,bottom=16.dp),color=Color(0xFFE7E8DE))
                Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {listOf("♡ 喜欢","☾ 晚安","☀ 加油").forEach {reaction->FilterChip(selected=reaction in diary.reactions,onClick={vm.react(diary.id,reaction)},label={Text(reaction,fontSize=10.sp)})}}
            }
            if(diary.visibility=="PARTNER") Text(if(diary.authorId=="me") "寄出时带给 TA，TA 可以阅读和评论" else "TA 的个人记录，正文只读，可以在下方评论",fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=24.dp))
            if(ExchangeEngine.canComment(diary)) {
                if(diary.visibility=="PARTNER") HorizontalDivider(Modifier.padding(top=20.dp,bottom=8.dp),color=Color(0xFFE7E8DE))
                Text("评论 · ${diary.comments.size}",fontSize=18.sp,color=Ink,modifier=Modifier.padding(top=16.dp,bottom=8.dp))
                Text("评论追加在这一页下方，不改正文。下次寄出、TA 收下后可见。",fontSize=12.sp,color=Muted,lineHeight=20.sp)
                OutlinedTextField(value=commentText,onValueChange={if(it.length<=ExchangeEngine.MAX_COMMENT_LENGTH) commentText=it},
                    label={Text("写评论")},placeholder={Text("对这一页说点什么…")},minLines=2,maxLines=5,
                    enabled=!sendingComment && !deleting && diary.comments.size<ExchangeEngine.MAX_COMMENTS,
                    modifier=Modifier.fillMaxWidth().padding(top=12.dp))
                Row(Modifier.fillMaxWidth().padding(top=6.dp,bottom=16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("${commentText.length} / ${ExchangeEngine.MAX_COMMENT_LENGTH}",fontSize=11.sp,color=Muted,modifier=Modifier.weight(1f))
                    Button(onClick={
                        sendingComment=true
                        vm.comment(diary.id,commentText,onSaved={
                            commentText="";sendingComment=false
                            vm.notify(if(diary.id.startsWith("sample-")) "评论已保存；示例页不会互传" else "评论已保存，下次寄出后 TA 可以看到")
                        },onFailed={sendingComment=false})
                    },enabled=commentText.isNotBlank() && !sendingComment && !deleting && diary.comments.size<ExchangeEngine.MAX_COMMENTS) {
                        Text(if(sendingComment) "正在保存…" else "发表评论")
                    }
                }
                if(diary.comments.size>=ExchangeEngine.MAX_COMMENTS) Text("这一页已满 2000 条评论，请在新的非私密页继续交流。",fontSize=12.sp,color=Muted)
                if(diary.comments.isEmpty()) Text("还没有评论，留下第一句回应吧。",fontSize=12.sp,color=Muted)
            } else Text("🔒 仅自己可见，不会寄出",fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=24.dp))
            }
          }
          if(ExchangeEngine.canComment(diary)) items(diary.comments,key={it.id}) {c->
            PaperCard(Modifier.padding(bottom=8.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(if(c.author=="me") "${profile.name} · 我" else profile.partner,fontSize=12.sp,color=Sage,modifier=Modifier.weight(1f))
                    Text(if(c.createdAt>0) Instant.ofEpochMilli(c.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) else c.date,fontSize=10.sp,color=Muted)
                }
                Text(c.text,fontSize=13.sp,lineHeight=23.sp,modifier=Modifier.padding(top=7.dp))
            }
          }
          item {Spacer(Modifier.height(40.dp))}
        }
        if(ExchangeEngine.canEdit(diary)) Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick=onEdit,enabled=!deleting,modifier=Modifier.weight(1f)) {Icon(Icons.Outlined.EditNote,null,Modifier.size(18.dp));Text("  编辑这一页")}
            OutlinedButton(onClick={deleteDialog=true},enabled=!deleting,modifier=Modifier.weight(1f)) {Icon(Icons.Outlined.DeleteOutline,null,Modifier.size(18.dp),tint=Blush);Text("  删除这一页",color=Blush)}
        }
    }
    if(deleteDialog) AlertDialog(onDismissRequest={if(!deleting) deleteDialog=false},title={Text(if(diary.isDiy) "删除这页手账？" else "删除这篇日记？")},text={Text("《${diary.title}》将从本机删除。" + if(ExchangeEngine.isPublic(diary) && !diary.id.startsWith("sample-")) "\n\n下次寄出会带上删除记录，TA 收下后也会删除这一页。已经发出的文件仍由 TA 持有。\n\n此操作无法撤销。" else "\n\n此操作无法撤销。")},confirmButton={TextButton(onClick={deleting=true;vm.deleteDiary(diary.id,onSaved={deleteDialog=false;onDelete()},onFailed={deleting=false})},enabled=!deleting) {Text(if(deleting) "正在删除…" else "确认删除",color=Blush)}},dismissButton={TextButton(onClick={deleteDialog=false},enabled=!deleting) {Text("取消")}})
}
