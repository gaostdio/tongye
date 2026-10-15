package app.tongye.diary

import android.os.Bundle
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.core.content.IntentCompat

class MainActivity : FragmentActivity() {
    private lateinit var model: DiaryViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model=ViewModelProvider(this)[DiaryViewModel::class.java]
        if(savedInstanceState==null) receiveIntent(intent)
        enableEdgeToEdge()
        setContent { TongyeApp(model) }
    }
    override fun onNewIntent(intent: Intent) {super.onNewIntent(intent);setIntent(intent);receiveIntent(intent)}
    private fun receiveIntent(intent: Intent) {
        val uri=when(intent.action) {Intent.ACTION_VIEW->intent.data;Intent.ACTION_SEND->IntentCompat.getParcelableExtra(intent,Intent.EXTRA_STREAM,android.net.Uri::class.java);else->null}
        if(uri?.scheme=="content") model.incomingFile(uri)
    }
}
