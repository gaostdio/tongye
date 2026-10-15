package app.tongye.diary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke as PaintStroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val paperColors=mapOf("奶油白" to Color(0xFFFFFDF6),"淡粉" to Color(0xFFFFEFF0),"雾蓝" to Color(0xFFEDF3F6),"浅紫" to Color(0xFFF0ECF6),"牛皮纸" to Color(0xFFDCC6A8),"方格纸" to Color(0xFFFFFDF6),"横线纸" to Color(0xFFFFFDF6),"夜间纸" to Color(0xFF333E3A))

@Composable fun CanvasPage(
    diary: Diary, modifier: Modifier = Modifier, selected: String? = null, interactive: Boolean = false,
    drawing: Boolean = false, penColor: Long = 0xFF728477, penWidth: Float = 3f,
    onSelect: (String?)->Unit = {}, onTransform: (String,Float,Float,Float,Float)->Unit = {_,_,_,_,_->},
    onStroke: (Stroke)->Unit = {}, onGestureStart: ()->Unit = {}, onGestureEnd: ()->Unit = {}
) {
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(.75f).clip(RoundedCornerShape(4.dp)).background(paperColors[diary.background] ?: Paper)) {
        val factor=maxWidth.value / 360f
        val density=LocalDensity.current.density
        var livePoints by remember { mutableStateOf<List<Point>>(emptyList()) }
        val latestDiary by rememberUpdatedState(diary)
        val transform by rememberUpdatedState(onTransform)
        val choose by rememberUpdatedState(onSelect)
        val start by rememberUpdatedState(onGestureStart)
        val end by rememberUpdatedState(onGestureEnd)
        val addStroke by rememberUpdatedState(onStroke)
        Canvas(Modifier.fillMaxSize().then(if(interactive && !drawing) Modifier.pointerInput(Unit) { detectTapGestures { choose(null) } } else Modifier)) {
            val spacing=24f*factor*density
            if(diary.background=="方格纸" || diary.background=="横线纸") {
                var y=spacing;while(y<size.height) { drawLine(Color(0xFFDEE4DA),Offset(0f,y),Offset(size.width,y),1f);y+=spacing }
                if(diary.background=="方格纸") {var x=spacing;while(x<size.width) {drawLine(Color(0xFFDEE4DA),Offset(x,0f),Offset(x,size.height),1f);x+=spacing}}
            }
        }
        diary.elements.sortedBy { it.zIndex }.forEach { element ->
            key(element.id) {
                val gestureModifier=if(interactive && !drawing) Modifier
                    .pointerInput(element.id) {detectTapGestures(onTap={choose(element.id)})}
                    .pointerInput(element.id) {
                        detectTransformGestures { _,pan,zoom,rotation ->
                            choose(element.id);start()
                            transform(element.id,pan.x/(factor*density),pan.y/(factor*density),zoom,rotation)
                        }
                    } else Modifier
                Box(Modifier.offset((element.x*factor).dp,(element.y*factor).dp)
                    .size((element.width*factor).dp,(element.height*factor).dp)
                    .graphicsLayer { rotationZ=element.rotation;scaleX=element.scale;scaleY=element.scale }
                    .then(gestureModifier)
                    .then(if(selected==element.id && interactive) Modifier.border(1.dp,Sage,RoundedCornerShape(2.dp)) else Modifier)) {
                    when(element.type) {
                        "VIDEO" -> VideoPreview(element.content,Modifier.fillMaxSize(),playable=!interactive)
                        "IMAGE" -> Column(Modifier.fillMaxSize().background(Color.White).padding((7*factor).dp)) {
                            PhotoView(element.content,Modifier.fillMaxWidth().weight(1f));Spacer(Modifier.height((20*factor).dp))
                        }
                        "STICKER" -> Text(element.content,fontSize=(element.fontSize*factor).sp,color=Color(element.color),modifier=Modifier.padding((2*factor).dp))
                        else -> Text(element.content,fontSize=(element.fontSize*factor).sp,lineHeight=(element.fontSize*1.45f*factor).sp,color=Color(element.color),fontFamily=FontFamily.Serif,modifier=Modifier.padding((4*factor).dp))
                    }
                }
            }
        }
        Canvas(Modifier.fillMaxSize().then(if(interactive && drawing) Modifier.pointerInput(penColor,penWidth) {
            detectDragGestures(onDragStart={p -> livePoints=listOf(Point(p.x/(factor*density),p.y/(factor*density)))},onDragCancel={livePoints=emptyList()},onDragEnd={
                if(livePoints.size>1) addStroke(Stroke(livePoints,penColor,penWidth));livePoints=emptyList()
            }) {change,_ -> change.consume();livePoints=livePoints+Point(change.position.x/(factor*density),change.position.y/(factor*density))}
        } else Modifier)) {
            (latestDiary.strokes + if(livePoints.isNotEmpty()) listOf(Stroke(livePoints,penColor,penWidth)) else emptyList()).forEach { stroke ->
                val path=Path();stroke.points.forEachIndexed { index,p -> if(index==0) path.moveTo(p.x*factor*density,p.y*factor*density) else path.lineTo(p.x*factor*density,p.y*factor*density) }
                drawPath(path,Color(stroke.color),style=PaintStroke(stroke.width*factor*density,cap=androidx.compose.ui.graphics.StrokeCap.Round,join=androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
    }
}
