package app.tongye.diary

import android.app.Application
import android.os.Build
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.decode.Decoder
import coil.fetch.SourceResult
import coil.request.Options
import coil.request.ImageRequest
import java.io.File

// Received attachments use a hash and .bin, so do not select the decoder by extension/MIME.
private val videoDecoder=object: Decoder.Factory {
    override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader)=VideoFrameDecoder(result.source,options)
}

class TongyeApplication: Application(), ImageLoaderFactory {
    override fun newImageLoader()=ImageLoader.Builder(this).components {
        if(Build.VERSION.SDK_INT>=28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
    }.build()
}

@Composable fun MediaPreview(path: String, video: Boolean, modifier: Modifier=Modifier, playable: Boolean=true) {
    if(video) VideoPreview(path,modifier,playable) else PhotoView(path,modifier)
}

@Composable fun VideoPreview(path: String, modifier: Modifier=Modifier, playable: Boolean=true) {
    val context=LocalContext.current
    var playing by remember(path) {mutableStateOf(false)}
    val request=remember(path,context) {ImageRequest.Builder(context).data(File(path)).decoderFactory(videoDecoder).build()}
    Box(modifier.background(Color(0xFF303832)).then(if(playable) Modifier.clickable {playing=true} else Modifier),contentAlignment=Alignment.Center) {
        AsyncImage(request,contentDescription="视频封面",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.PlayCircleOutline,if(playable) "播放视频" else "视频",Modifier.size(44.dp),tint=Color.White)
            Text(if(playable) "点击播放" else "视频",fontSize=11.sp,color=Color.White)
        }
    }
    if(playing) VideoDialog(path) {playing=false}
}

@Composable fun VideoDialog(path: String, onClose: ()->Unit) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val close by rememberUpdatedState(onClose)
    var failed by remember(path) {mutableStateOf(false)}
    val view=remember(path,context) {VideoView(context)}
    DisposableEffect(view,owner) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) {view.stopPlayback();close()}}
        owner.lifecycle.addObserver(observer)
        onDispose {owner.lifecycle.removeObserver(observer);view.stopPlayback()}
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().padding(20.dp),color=Paper) {
            Column {
                Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("这一刻的视频",modifier=Modifier.weight(1f),color=Ink)
                    TextButton(onClick=onClose) {Text("关闭")}
                }
                if(failed) Text("这个视频无法在当前手机播放，请换用常见的 MP4 视频。",modifier=Modifier.padding(20.dp),color=Muted)
                else AndroidView(factory={ctx->FrameLayout(ctx).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    addView(view,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
                    view.setMediaController(MediaController(ctx).also {it.setAnchorView(view)})
                    view.setOnErrorListener {_,_,_->failed=true;view.stopPlayback();true}
                    view.setOnPreparedListener {view.start()}
                    view.setVideoPath(path)
                }},modifier=Modifier.fillMaxWidth().height(260.dp))
                Text("点画面可暂停、继续或拖动进度。",fontSize=12.sp,color=Muted,modifier=Modifier.padding(16.dp))
            }
        }
    }
}
