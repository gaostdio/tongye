package app.tongye.diary

import android.app.Application
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class DiaryViewModel(app: Application): AndroidViewModel(app) {
    private val db = Room.databaseBuilder(app, DiaryDatabase::class.java, "tongye.db").addMigrations(DiaryDatabase.MIGRATION_1_2).build()
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<DiaryState?>(null)
    val state = mutable.asStateFlow()
    private val noticeMutable = MutableStateFlow<String?>(null)
    val notice = noticeMutable.asStateFlow()
    private val progressMutable = MutableStateFlow<String?>(null)
    val transferProgress = progressMutable.asStateFlow()
    private val incomingMutable = MutableStateFlow<Uri?>(null)
    val incoming = incomingMutable.asStateFlow()
    fun incomingFile(uri: Uri) { incomingMutable.value=uri }
    fun clearIncoming() { incomingMutable.value=null }
    private val prefs = app.getSharedPreferences("privacy", 0)
    init { viewModelScope.launch { try {
        val normalized=withContext(Dispatchers.IO) {
            val loaded=db.workspace().load() ?: DiaryState()
            val next=ExchangeEngine.normalize(loaded)
            if(db.workspace().documentChunkCount()==0) db.workspace().save(WorkspaceDocuments.changes(null,next),migrateLegacy=true)
            else if(next!=loaded) db.workspace().save(WorkspaceDocuments.changes(loaded,next))
            next
        }
        mutable.value=normalized
    } catch(_: Exception) { noticeMutable.value="读取本地日记失败，请重启应用" } } }
    fun notify(message: String) { noticeMutable.value=message }
    fun clearNotice() { noticeMutable.value=null }
    private fun update(onSaved: ()->Unit = {}, onFailed: ()->Unit = {}, change: (DiaryState)->DiaryState) { viewModelScope.launch { mutex.withLock {
        val previous = mutable.value ?: return@withLock
        try { val next = withContext(Dispatchers.IO) {val updated=change(previous);db.workspace().save(WorkspaceDocuments.changes(previous,updated));updated}; mutable.value=next;onSaved() }
        catch(e: IllegalArgumentException) { notify(e.message ?: "无法保存这次修改");onFailed() }
        catch(_: Exception) { notify("保存失败，请检查手机存储空间后重试");onFailed() }
    } } }
    fun startLocal(name: String) = update { existing ->
        if(existing.initialized || name.isBlank()) existing
        else existing.copy(initialized=true,profile=existing.profile.copy(name=name.trim()))
    }
    fun saveDiary(diary: Diary,onSaved: ()->Unit = {}) = update(onSaved) { ExchangeEngine.save(it,diary) }
    fun deleteDiary(id: String,onSaved: ()->Unit = {},onFailed: ()->Unit = {}) = update(onSaved,onFailed) { ExchangeEngine.delete(it,id) }
    fun favorite(id: String) = update { it.copy(diaries=it.diaries.map { d->if(d.id==id) d.copy(favorite=!d.favorite) else d }) }
    fun react(id: String, reaction: String) = update { s->s.diaries.find {it.id==id && it.visibility=="SHARED"}?.let {d->ExchangeEngine.save(s,d.copy(reactions=if(reaction in d.reactions) d.reactions-reaction else d.reactions+reaction))} ?: s }
    fun comment(id: String, text: String,onSaved: ()->Unit = {},onFailed: ()->Unit = {}) = update(onSaved,onFailed) { ExchangeEngine.addComment(it,id,text) }
    fun note(text: String) = update { it.copy(notes=it.notes.filterNot { n->n.date==today() && n.author=="me" } + DailyNote(text=text.trim())) }
    fun profile(value: Profile) = update { s->s.copy(profile=value,exchange=s.exchange?.let {it.copy(revision=it.revision+1)}) }
    fun addAnniversary(value: Anniversary) = update { it.copy(anniversaries=it.anniversaries+value) }
    fun deleteAnniversary(id: String) = update { it.copy(anniversaries=it.anniversaries.filterNot { a->a.id==id }) }
    fun clearSamples() = update { it.copy(diaries=it.diaries.filterNot { d->d.id.startsWith("sample-") }, notes=it.notes.filterNot { n->n.author=="partner" }) }
    suspend fun connect(code: String) = mutex.withLock {
        val s=requireNotNull(mutable.value);val ex=requireNotNull(s.exchange);val normalized=ExchangeEngine.normalizeCode(code)
        require(ex.code.isBlank() || ex.code==normalized) { "已使用一个口令，请让 TA 加入当前口令" }
        val next=s.copy(exchange=ex.copy(code=normalized))
        withContext(Dispatchers.IO) {db.workspace().save(WorkspaceDocuments.changes(s,next))};mutable.value=next
    }
    suspend fun prepareExchange(complete: Boolean=false): Pair<File,PreparedExchangeFile> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dir=File(getApplication<Application>().cacheDir,"exchange").apply {mkdirs()}
            // Keep recent outgoing files so a share target can finish reading asynchronously.
            dir.listFiles()?.filter {System.currentTimeMillis()-it.lastModified()>7*24*60*60*1000L}?.forEach {it.delete()}
            val file=File(dir,"同页回忆-${today()}-${newId().take(8)}.couple")
            try {file to ExchangeFiles.prepare(requireNotNull(mutable.value),File(getApplication<Application>().filesDir,"photos"),file,complete) {progressMutable.value=it}}
            finally {progressMutable.value=null}
        }
    }
    suspend fun readExchange(uri: Uri): OpenedExchange = withContext(Dispatchers.IO) {
        val code=requireNotNull(mutable.value?.exchange).code;require(code.isNotBlank()) { "先粘贴 TA 的口令，再收下文件" }
        var opened: OpenedExchange?=null
        try {
            val read=requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)) {"无法打开文件，请在同页里重新选择"}.use {input->
                ExchangeFiles.open(input,code,File(getApplication<Application>().cacheDir,"incoming")) {progressMutable.value=it}
            }
            opened=read
            ExchangeEngine.receive(requireNotNull(mutable.value),read,{hash->File(getApplication<Application>().filesDir,"photos/$hash.bin").absolutePath})
            read
        } catch(e: Exception) {opened?.close();throw e}
        finally {progressMutable.value=null}
    }
    fun discardExchange(opened: OpenedExchange?) {viewModelScope.launch(Dispatchers.IO) {opened?.close()}}
    suspend fun receiveExchange(opened: OpenedExchange): ExchangeImport = mutex.withLock {
        try {withContext(Dispatchers.IO) {
            val s=requireNotNull(mutable.value)
            val dir=File(getApplication<Application>().filesDir,"photos").apply {mkdirs()}
            fun canonicalPhoto(hash: String)=File(dir,"$hash.bin")
            // Original authors keep UUID photo names; a returning page may refer to their existing bytes.
            val originals=mutableMapOf<String,File>()
            val missing=opened.bundle.photoHashes.filter {it !in opened.media && it !in opened.mediaFiles && !canonicalPhoto(it).isFile}.toSet()
            if(missing.isNotEmpty()) {
                s.diaries.filter {ExchangeEngine.isPublic(it)}.flatMap {it.mediaPaths()}.filterNot {it.startsWith("sample:")}.distinct().forEach {path->
                    val file=File(path).canonicalFile
                    if(file.toPath().startsWith(dir.canonicalFile.toPath()) && file.isFile) {
                        val digest=ExchangeEngine.hash(file);if(digest in missing) originals[digest]=file
                    }
                }
            }
            fun photo(hash: String)=originals[hash] ?: canonicalPhoto(hash)
            val merged=ExchangeEngine.receive(s,opened,{photo(it).absolutePath})
            // Verify the entire package before touching records; missing media never produces broken pages.
            opened.mediaFiles.forEach {(hash,file)->require(file.isFile && ExchangeEngine.hash(file)==hash) {"照片完整性检查没有通过"}}
            opened.bundle.photoHashes.forEach { hash -> require(hash in opened.media || hash in opened.mediaFiles || (photo(hash).isFile && ExchangeEngine.hash(photo(hash))==hash)) { "有照片未收到，请让 TA 使用「重新寄完整回忆」" } }
            val hashes=opened.media.keys+opened.mediaFiles.keys
            hashes.forEachIndexed {index,hash->
                val target=photo(hash)
                progressMutable.value="正在保存图片和视频 ${index+1} / ${hashes.size}"
                if(!target.isFile || ExchangeEngine.hash(target)!=hash) {
                    val temporary=File(dir,"$hash-${newId()}.tmp")
                    try {
                        val source=opened.mediaFiles[hash]
                        if(source!=null) source.inputStream().use {input->temporary.outputStream().use {input.copyTo(it,64*1024)}} else temporary.writeBytes(requireNotNull(opened.media[hash]))
                        require(temporary.renameTo(target)) {"照片保存失败，请检查手机存储空间"}
                    } finally {temporary.delete()}
                }
            }
            try {db.workspace().save(WorkspaceDocuments.changes(s,merged.state));mutable.value=merged.state;merged}
            finally {progressMutable.value=null}
        }} finally {progressMutable.value=null}
    }
    suspend fun importMedia(uri: Uri): ImportedMedia = withContext(Dispatchers.IO) {
        val mime=getApplication<Application>().contentResolver.getType(uri).orEmpty()
        require(mime.startsWith("image/") || mime.startsWith("video/")) {"请选择照片、动图或视频文件"}
        val isVideo=mime.startsWith("video/")
        val dir = File(getApplication<Application>().filesDir,"photos").apply { mkdirs() }
        val extension=MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf {it.matches(Regex("[a-zA-Z0-9]{1,10}"))} ?: if(isVideo) "mp4" else "img"
        val file = File(dir, "${newId()}.$extension")
        try {
            getApplication<Application>().contentResolver.openInputStream(uri).use {input->requireNotNull(input) {"无法读取这个文件"};file.outputStream().use {input.copyTo(it,64*1024)}}
            require(file.length()>0) {"这个文件没有内容，请重新选择"}
            if(isVideo) {
                val retriever=MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    require((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0)>0 && (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0)>0) {"无法读取视频，请换用常见的 MP4 视频"}
                } finally {retriever.release()}
            }
            ImportedMedia(file.absolutePath,isVideo)
        }
        catch(e: Exception) { file.delete(); throw e }
    }
    fun setPin(pin: String) {
        val salt = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("salt",salt).putString("hash", hash(salt+pin)).apply()
    }
    fun pinWaitSeconds(): Long = ((prefs.getLong("blockedUntil",0)-System.currentTimeMillis()+999)/1000).coerceAtLeast(0)
    fun checkPin(pin: String): Boolean {
        if(pinWaitSeconds()>0) return false
        val matches=MessageDigest.isEqual(hash(prefs.getString("salt","")+pin).toByteArray(),prefs.getString("hash","")!!.toByteArray())
        if(matches) prefs.edit().putInt("attempts",0).putLong("blockedUntil",0).apply()
        else { val count=prefs.getInt("attempts",0)+1; prefs.edit().putInt("attempts",if(count>=5) 0 else count).putLong("blockedUntil",if(count>=5) System.currentTimeMillis()+30_000 else 0).apply() }
        return matches
    }
    fun hasPin() = prefs.contains("hash")
    private fun hash(value: String) = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(value.toCharArray(),"tongye-private-pin-v1".toByteArray(),120_000,256)).encoded.joinToString("") { "%02x".format(it) }
    suspend fun export(uri: Uri, includePrivate: Boolean) = mutex.withLock {withContext(Dispatchers.IO) {
        val dir=File(getApplication<Application>().cacheDir,"backups").apply {mkdirs()}
        val temporary=File(dir,"backup-${newId()}.zip")
        try {
            BackupFiles.write(requireNotNull(mutable.value),File(getApplication<Application>().filesDir,"photos"),temporary,includePrivate) {progressMutable.value=it}
            progressMutable.value="备份已装好，正在写入保存位置…"
            requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri,"wt")) {"无法写入保存位置，请重新选择"}.use {output->temporary.inputStream().use {it.copyTo(output,64*1024)}}
        } finally {temporary.delete();progressMutable.value=null}
    }}
}

data class ImportedMedia(val path: String, val isVideo: Boolean)
