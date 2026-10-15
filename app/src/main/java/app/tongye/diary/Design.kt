package app.tongye.diary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke as OutlineStroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

val Paper = Color(0xFFFAF8F4)
val Ink = Color(0xFF353E37)
val Muted = Color(0xFF6B736A)
val Sage = Color(0xFF526D5C)
val Blush = Color(0xFF9A6A58)
val PaperEdge = Color(0xFFE9E8DF)
@Composable fun accentPaper(): Color = MaterialTheme.colorScheme.primary.copy(alpha=.10f).compositeOver(Paper)

// Quiet, vector-only book illustration; it carries no user data or touch targets.
@Composable fun BookMotif(modifier: Modifier = Modifier) {
    val accent=MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val w=size.width;val h=size.height
        drawCircle(accent.copy(alpha=.045f),w*.43f,Offset(w*.56f,h*.51f))
        val book=Path().apply {
            moveTo(w*.13f,h*.34f);quadraticTo(w*.31f,h*.26f,w*.50f,h*.36f)
            quadraticTo(w*.69f,h*.26f,w*.87f,h*.34f);lineTo(w*.87f,h*.77f)
            quadraticTo(w*.68f,h*.69f,w*.50f,h*.80f)
            quadraticTo(w*.32f,h*.69f,w*.13f,h*.77f);close()
        }
        drawPath(book,Color.White.copy(alpha=.65f))
        drawPath(book,accent.copy(alpha=.35f),style=OutlineStroke(1.5.dp.toPx()))
        drawLine(accent.copy(alpha=.30f),Offset(w*.50f,h*.36f),Offset(w*.50f,h*.78f),1.5.dp.toPx())
        for(i in 0..2) {
            val y=h*(.46f+i*.08f)
            drawLine(accent.copy(alpha=.18f),Offset(w*.22f,y),Offset(w*.40f,y+.02f*h),1.dp.toPx())
            drawLine(accent.copy(alpha=.18f),Offset(w*.60f,y+.02f*h),Offset(w*.78f,y),1.dp.toPx())
        }
        drawLine(accent.copy(alpha=.35f),Offset(w*.78f,h*.17f),Offset(w*.78f,h*.25f),1.dp.toPx())
        drawLine(accent.copy(alpha=.35f),Offset(w*.74f,h*.21f),Offset(w*.82f,h*.21f),1.dp.toPx())
        drawCircle(Blush.copy(alpha=.40f),2.dp.toPx(),Offset(w*.25f,h*.18f))
    }
}
@Composable fun KeepsakeCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.()->Unit) {
    val tint=accentPaper()
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(26.dp),color=tint,border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.12f))) {
        Box(Modifier.background(Brush.linearGradient(listOf(tint,Color(0xFFF5EEE5))))) {
            BookMotif(Modifier.align(Alignment.BottomEnd).size(124.dp).padding(8.dp))
            Column(Modifier.padding(22.dp),content=content)
        }
    }
}
@Composable fun IconTile(icon: ImageVector,modifier: Modifier = Modifier) {
    Box(modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(accentPaper()),contentAlignment=Alignment.Center) {
        Icon(icon,null,Modifier.size(19.dp),tint=MaterialTheme.colorScheme.primary)
    }
}
@Composable fun TinyLabel(text: String,modifier: Modifier = Modifier,color: Color = MaterialTheme.colorScheme.primary) {
    Surface(modifier,shape=RoundedCornerShape(8.dp),color=color.copy(alpha=.08f)) {
        Text(text,Modifier.padding(horizontal=9.dp,vertical=5.dp),fontSize=10.sp,lineHeight=15.sp,color=color)
    }
}
@Composable fun MemoryCounts(values: List<Pair<String,String>>,modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=Color.White,border=BorderStroke(1.dp,PaperEdge)) {
        Row(Modifier.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
            values.forEachIndexed {i,(value,label)->
                if(i>0) VerticalDivider(Modifier.height(30.dp),color=PaperEdge)
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(value,fontSize=24.sp,fontFamily=FontFamily.Serif,color=MaterialTheme.colorScheme.primary,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(label,fontSize=10.sp,color=Muted,modifier=Modifier.padding(top=4.dp))
                }
            }
        }
    }
}
@Composable fun PageTitle(eyebrow: String, title: String, subtitle: String = "", action: @Composable (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(top=18.dp,bottom=18.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(Blush))
            Text(eyebrow,Modifier.padding(start=8.dp),fontSize=9.sp,letterSpacing=1.5.sp,color=MaterialTheme.colorScheme.primary)
        }
        Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(title,fontSize=28.sp,lineHeight=38.sp,fontWeight=FontWeight.SemiBold,color=Ink,modifier=Modifier.weight(1f))
            action?.invoke()
        }
        if(subtitle.isNotEmpty()) Text(subtitle,modifier=Modifier.padding(top=8.dp),fontSize=12.sp,color=Muted,lineHeight=20.sp)
    }
}
@Composable fun Avatar(name: String, partner: Boolean = false, size: Int = 46) {
    Surface(Modifier.size(size.dp),shape=CircleShape,color=if(partner) accentPaper() else Color(0xFFF0DFD5),border=BorderStroke(2.dp,Color.White.copy(alpha=.8f))) {
      Box(contentAlignment=Alignment.Center) {
        Text(name.take(1),color=if(partner) MaterialTheme.colorScheme.primary else Blush,fontSize=(size/2.8f).sp,fontWeight=FontWeight.Medium)
      }
    }
}
@Composable fun SectionHeading(title: String, trailing: String = "", onClick: ()->Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top=22.dp,bottom=14.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(3.dp,17.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha=.55f)))
        Text(title,fontSize=17.sp,fontWeight=FontWeight.SemiBold,color=Ink,modifier=Modifier.weight(1f).padding(start=9.dp))
        if(trailing.isNotEmpty()) TextButton(onClick=onClick,contentPadding=PaddingValues(0.dp)) { Text(trailing,fontSize=11.sp,color=Muted) }
    }
}
@Composable fun PaperCard(modifier: Modifier = Modifier, color: Color = Color.White, content: @Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),color=color,tonalElevation=0.dp,border=BorderStroke(1.dp,PaperEdge)) { Column(Modifier.padding(20.dp),content=content) }
}
@Composable fun EmptyState(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical=50.dp,horizontal=20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        BookMotif(Modifier.size(102.dp))
        Text(title,fontSize=17.sp,color=Ink,modifier=Modifier.padding(top=16.dp))
        Text(text,fontSize=12.sp,color=Muted,lineHeight=21.sp,textAlign=TextAlign.Center,modifier=Modifier.padding(top=8.dp))
    }
}
@Composable fun PhotoView(path: String, modifier: Modifier = Modifier) {
    if(path.startsWith("sample:")) ScenicPhoto(path,modifier)
    else AsyncImage(model=path,contentDescription="日记照片",contentScale=ContentScale.Crop,modifier=modifier)
}
@Composable private fun ScenicPhoto(path: String, modifier: Modifier) {
    Canvas(modifier) {
        val w=size.width; val h=size.height
        if(path=="sample:coffee") {
            drawRect(Color(0xFFE9DCCD)); drawRect(Color(0xFFC9AE94),topLeft=Offset(0f,h*.68f))
            drawCircle(Color(0xFFB69C83),w*.22f,Offset(w*.38f,h*.58f)); drawCircle(Color(0xFFF6EDE2),w*.19f,Offset(w*.37f,h*.53f))
            drawCircle(Color(0xFF7D5943),w*.135f,Offset(w*.37f,h*.53f)); drawCircle(Color(0xFFE8D2B8),w*.075f,Offset(w*.37f,h*.53f))
            drawCircle(Color(0xFFAF967C),w*.17f,Offset(w*.78f,h*.46f)); drawCircle(Color(0xFFF6EDE2),w*.15f,Offset(w*.77f,h*.42f)); drawCircle(Color(0xFF7D5943),w*.10f,Offset(w*.77f,h*.42f))
            drawLine(Color(0xFF71816A),Offset(w*.06f,0f),Offset(w*.12f,h*.42f),w*.02f)
            drawOval(Color(0xFF899375),Offset(w*.05f,h*.08f),androidx.compose.ui.geometry.Size(w*.17f,h*.13f))
        } else {
            drawRect(Color(0xFFDCE6DE)); drawCircle(Color(0xFFF5EEE0),w*.09f,Offset(w*.74f,h*.24f))
            val mountain=Path().apply { moveTo(0f,h*.64f);lineTo(w*.27f,h*.22f);lineTo(w*.55f,h*.61f);lineTo(w*.78f,h*.36f);lineTo(w,h*.6f);lineTo(w,h);lineTo(0f,h);close() }
            drawPath(mountain,Color(0xFF94A79B))
            val near=Path().apply { moveTo(0f,h*.74f);lineTo(w*.18f,h*.44f);lineTo(w*.41f,h*.75f);lineTo(w*.72f,h*.51f);lineTo(w,h*.76f);lineTo(w,h);lineTo(0f,h);close() }; drawPath(near,Color(0xFF6D8B79))
            drawRect(Color(0xFFB2CAC0),Offset(0f,h*.77f)); drawLine(Color(0xFFD7E2D9),Offset(w*.54f,h*.85f),Offset(w*.83f,h*.85f),2f)
        }
    }
}
@Composable fun DiaryCard(diary: Diary, profile: Profile, onClick: ()->Unit) {
    Surface(onClick=onClick,shape=RoundedCornerShape(22.dp),color=Color.White,border=BorderStroke(1.dp,PaperEdge),modifier=Modifier.fillMaxWidth().padding(bottom=16.dp)) {
        Column {
            val photo=diary.photos.firstOrNull() ?: diary.elements.firstOrNull { it.type=="IMAGE" }?.content
            val video=diary.videos.orEmpty().firstOrNull() ?: diary.elements.firstOrNull {it.type=="VIDEO"}?.content
            val count=diary.photos.size+diary.videos.orEmpty().size+diary.elements.count {it.isMedia()}
            if(photo!=null || video!=null) Box {
                if(photo!=null) PhotoView(photo,Modifier.fillMaxWidth().aspectRatio(1.85f))
                else VideoPreview(video!!,Modifier.fillMaxWidth().aspectRatio(1.85f),playable=false)
                if(diary.isDiy) Surface(Modifier.align(Alignment.TopStart).padding(12.dp),shape=RoundedCornerShape(8.dp),color=Paper.copy(alpha=.94f)) {
                    Text(if(diary.visibility=="SHARED") "共同手账" else "自由手账",Modifier.padding(horizontal=10.dp,vertical=6.dp),fontSize=10.sp,color=Ink)
                }
                if(count>1) Surface(Modifier.align(Alignment.BottomEnd).padding(12.dp),shape=RoundedCornerShape(8.dp),color=Ink.copy(alpha=.65f)) {
                    Text("$count 个影像",Modifier.padding(horizontal=9.dp,vertical=5.dp),fontSize=10.sp,color=Color.White)
                }
            }
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(if(diary.isDiy) Icons.Outlined.DashboardCustomize else Icons.Outlined.MenuBook,null,Modifier.size(14.dp),tint=MaterialTheme.colorScheme.primary)
                    Text(diary.date.replace("-"," / "),fontSize=10.sp,color=Muted,modifier=Modifier.weight(1f).padding(start=7.dp))
                    Text(if(diary.visibility=="PRIVATE") "仅自己" else diary.mood,fontSize=10.sp,color=Blush)
                }
                Text(diary.title,fontSize=18.sp,lineHeight=27.sp,fontWeight=FontWeight.Medium,color=Ink,modifier=Modifier.padding(top=10.dp),maxLines=2,overflow=TextOverflow.Ellipsis)
                if(diary.body.isNotBlank()) Text(diary.body.replace("\n"," "),fontSize=12.sp,color=Muted,maxLines=2,lineHeight=21.sp,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=7.dp))
                HorizontalDivider(Modifier.padding(top=16.dp),color=PaperEdge)
                Row(Modifier.padding(top=14.dp),verticalAlignment=Alignment.CenterVertically) {
                    Avatar(if(diary.authorId=="me") profile.name else profile.partner,diary.authorId!="me",22)
                    Text((if(diary.authorId=="me") "  ${profile.name}的记录" else "  ${profile.partner}的记录") + if(diary.id.startsWith("sample-")) " · 示例" else "",fontSize=10.sp,color=Muted,modifier=Modifier.weight(1f))
                    if(diary.comments.isNotEmpty() && diary.visibility!="PRIVATE") {Icon(Icons.Outlined.ChatBubbleOutline,null,Modifier.size(13.dp),tint=Muted);Text(" ${diary.comments.size}  ",fontSize=10.sp,color=Muted)}
                    if(diary.favorite) Icon(Icons.Outlined.Star,null,Modifier.size(15.dp),tint=Blush)
                    if(diary.visibility=="SHARED") Text("♡ ${diary.reactions.size}",fontSize=12.sp,color=Blush)
                }
            }
        }
    }
}
