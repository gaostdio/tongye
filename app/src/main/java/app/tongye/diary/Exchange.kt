package app.tongye.diary

import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class Removal(val id: String, val authorId: String, val visibility: String, val updatedAt: Long, val revision: Long)
data class CommentUpdate(val pageId: String, val ownerId: String, val comments: List<Comment>)
data class ExchangeState(
    val userId: String = newId(), val code: String = "", val peerId: String = "",
    val revision: Long = 1, val receivedRevision: Long = 0, val acknowledgedRevision: Long = 0,
    val revisions: Map<String,Long> = emptyMap(), val removals: Map<String,Removal> = emptyMap(),
    val receivedMedia: Set<String> = emptySet(), val peerMedia: Set<String> = emptySet(),
    val peerProfile: Profile? = null, val lastReceivedAt: Long = 0, val lastReceivedCount: Int = 0
)
data class ExchangeBundle(
    val version: Int = 2, val spaceId: String, val senderId: String, val recipientId: String,
    val fromRevision: Long, val toRevision: Long, val acknowledgedRevision: Long,
    val profile: Profile, val diaries: List<Diary>, val removals: List<Removal>,
    val photoHashes: Set<String>, val receivedMedia: Set<String>, val commentUpdates: List<CommentUpdate> = emptyList()
)
data class OpenedExchange(val bundle: ExchangeBundle, val media: Map<String,ByteArray>, val mediaFiles: Map<String,File> = emptyMap(), val staging: File? = null): AutoCloseable {
    override fun close() { staging?.let {dir->dir.listFiles()?.forEach {it.delete()};dir.delete()} }
    val mediaCount: Int get() = (media.keys+mediaFiles.keys).size
}
data class PreparedExchange(val bytes: ByteArray, val diaryCount: Int, val photoCount: Int, val removalCount: Int)
data class ExchangePlan(val bundle: ExchangeBundle, val media: Map<String,File>)
data class PreparedExchangeFile(val diaryCount: Int, val photoCount: Int, val removalCount: Int)
data class ExchangeImport(val state: DiaryState, val changedCount: Int, val duplicate: Boolean)

object ExchangeEngine {
    const val MAX_PACKAGE_BYTES = 64 * 1024 * 1024
    const val MAX_PACKAGE_PAGES = 20_000
    private const val MAX_JSON_BYTES = 32 * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 128 * 1024 * 1024
    private val magic = "TONGYE2\n".toByteArray(Charsets.US_ASCII)
    private val gson = Gson()
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val idPattern = Regex("[A-Za-z0-9_-]{1,100}")

    fun newCode(): String = "TY1-" + ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it) }.chunked(4).joinToString("-")
    fun normalizeCode(value: String): String {
        val raw = value.trim().uppercase().removePrefix("TY1").filterNot { it == '-' || it.isWhitespace() }
        require(raw.matches(Regex("[0-9A-F]{32}"))) { "请粘贴 TA 发来的完整口令" }
        return "TY1-" + raw.chunked(4).joinToString("-")
    }
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun hash(file: File): String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use {input->val chunk=ByteArray(64*1024);var n=input.read(chunk);while(n!=-1) {digest.update(chunk,0,n);n=input.read(chunk)}}
        return digest.digest().joinToString("") {"%02x".format(it)}
    }
    fun spaceId(code: String): String = hash(normalizeCode(code).toByteArray())
    fun isPublic(d: Diary): Boolean = d.visibility == "PARTNER" || d.visibility == "SHARED"
    fun canEdit(d: Diary): Boolean = d.authorId == "me" || d.visibility == "SHARED"
    fun canComment(d: Diary): Boolean = isPublic(d)
    const val MAX_COMMENT_LENGTH = 200
    const val MAX_COMMENTS = 2000

    // Comments are append-only and must not advance the page-content conflict clock.
    fun addComment(state: DiaryState, id: String, text: String, now: Long = System.currentTimeMillis()): DiaryState {
        val s=normalize(state);val ex=requireNotNull(s.exchange)
        val page=requireNotNull(s.diaries.find {it.id==id}) { "这一页已不存在，无法评论" }
        require(canComment(page)) { "私密日记和手账不能评论" }
        val content=text.trim()
        require(content.isNotEmpty() && content.length<=MAX_COMMENT_LENGTH) { "请填写 1～200 字的评论" }
        require(page.comments.size<MAX_COMMENTS) { "这一页评论已达到 2000 条，请在新的非私密页继续交流" }
        require(now>0) { "评论时间无效" }
        val comment=Comment(text=content,createdAt=now)
        val revision=ex.revision+1
        return s.copy(diaries=s.diaries.map {if(it.id==id) it.copy(comments=mergeComments(it.comments,listOf(comment))) else it},
            exchange=ex.copy(revision=revision,revisions=ex.revisions+(id to revision)))
    }

    private fun mergeComments(a: List<Comment>, b: List<Comment>): List<Comment> =
        (a+b).distinctBy {it.id}.sortedWith(compareBy<Comment> {it.date}.thenBy {it.createdAt}.thenBy {it.id})

    // Gson bypasses Kotlin defaults for fields absent in an older snapshot.
    fun normalize(state: DiaryState): DiaryState {
        val diaries = state.diaries.map { d -> d.copy(
            videos=d.videos.orEmpty(),
            updatedBy=d.updatedBy ?: d.authorId,
            deletedElements=d.deletedElements ?: emptyMap(), deletedStrokes=d.deletedStrokes ?: emptyMap(),
            strokes=d.strokes.map { if(it.id.isNullOrBlank()) it.copy(id=newId(),updatedAt=d.updatedAt) else it }
        ) }
        val ex = state.exchange ?: ExchangeState(revisions=diaries.filter { isPublic(it) && it.authorId=="me" && !it.id.startsWith("sample-") }.associate { it.id to 1L })
        return state.copy(profile=state.profile.copy(homePageId=state.profile.homePageId ?: ""),diaries=diaries,exchange=ex)
    }

    fun save(state: DiaryState, draft: Diary, now: Long = System.currentTimeMillis()): DiaryState {
        val s=normalize(state); val ex=requireNotNull(s.exchange); val old=s.diaries.find {it.id==draft.id}
        require(old==null || canEdit(old)) { "TA 的个人日记只能由 TA 修改" }
        require(old==null || old.authorId=="me" || draft.visibility=="SHARED") { "共同页不能改为 TA 的私密内容" }
        val timestamp=maxOf(now,(old?.updatedAt ?: 0)+1)
        val elements=draft.elements.map { e -> val previous=old?.elements?.find {it.id==e.id}; if(previous==e) e else e.copy(createdBy=previous?.createdBy ?: "me",updatedBy="me",updatedAt=timestamp) }
        val deletedElements=(old?.deletedElements ?: emptyMap()) + (old?.elements ?: emptyList()).filter { e->elements.none {it.id==e.id} }.associate { it.id to timestamp }
        val deletedStrokes=(old?.deletedStrokes ?: emptyMap()) + (old?.strokes ?: emptyList()).filter { stroke->draft.strokes.none {it.id==stroke.id} }.associate { it.id to timestamp }
        val saved=draft.copy(authorId=old?.authorId ?: "me",updatedBy="me",updatedAt=timestamp,elements=elements,deletedElements=deletedElements,deletedStrokes=deletedStrokes,comments=mergeComments(old?.comments ?: emptyList(),draft.comments),videos=draft.videos.orEmpty())
        val revision=ex.revision+1
        var removals=ex.removals
        if(old!=null && isPublic(old) && !isPublic(saved) && !old.id.startsWith("sample-")) removals=removals + (old.id to Removal(old.id,old.authorId,old.visibility,timestamp,revision))
        if(isPublic(saved)) removals=removals-saved.id
        return s.copy(diaries=listOf(saved)+s.diaries.filterNot {it.id==saved.id},exchange=ex.copy(revision=revision,revisions=ex.revisions+(saved.id to revision),removals=removals))
    }

    fun delete(state: DiaryState, id: String, now: Long = System.currentTimeMillis()): DiaryState {
        val s=normalize(state); val ex=requireNotNull(s.exchange); val old=s.diaries.find {it.id==id} ?: return s
        require(canEdit(old)) { "TA 的个人日记只能由 TA 修改" }
        val revision=ex.revision+1
        val removals=if(isPublic(old) && !id.startsWith("sample-")) ex.removals+(id to Removal(id,old.authorId,old.visibility,maxOf(now,old.updatedAt+1),revision)) else ex.removals
        return s.copy(diaries=s.diaries.filterNot {it.id==id},profile=if(s.profile.homePageId==id) s.profile.copy(homePageId="") else s.profile,exchange=ex.copy(revision=revision,removals=removals,revisions=ex.revisions-id))
    }

    private fun wireAuthor(value: String, ex: ExchangeState): String = when(value) { "me"->ex.userId; "partner"->ex.peerId; else->value }
    private fun photoRefs(d: Diary): List<String> = d.mediaPaths()

    fun prepare(state: DiaryState, photosRoot: File, complete: Boolean = false): PreparedExchange {
        val plan=plan(state,photosRoot,complete)
        require(plan.media.values.sumOf {it.length()}<=MAX_PACKAGE_BYTES) { "旧格式照片超过 64 MB，请使用新版文件导出" }
        val media=plan.media.mapValues {it.value.readBytes()}
        val b=plan.bundle
        return PreparedExchange(encode(b,media,requireNotNull(normalize(state).exchange).code),b.diaries.size+b.commentUpdates.size,media.size,b.removals.size)
    }

    fun plan(state: DiaryState, photosRoot: File, complete: Boolean = false, progress: (String)->Unit = {}): ExchangePlan {
        val s=normalize(state); val ex=requireNotNull(s.exchange); require(ex.code.isNotBlank()) { "先和 TA 使用同一个口令" }
        val base=if(complete) 0 else ex.acknowledgedRevision
        val selection=s.diaries.filter { isPublic(it) && !it.id.startsWith("sample-") && (it.authorId=="me" || it.visibility=="SHARED") && (complete || (ex.revisions[it.id] ?: 0)>base) }
        // A reader returns only their comments, never the owner's body, canvas or photos.
        val commentUpdates=s.diaries.filter {it.visibility=="PARTNER" && it.authorId=="partner" && !it.id.startsWith("sample-") && (complete || (ex.revisions[it.id] ?: 0)>base)}.mapNotNull {d->
            d.comments.filter {it.author=="me"}.takeIf {it.isNotEmpty()}?.let {comments->CommentUpdate(d.id,ex.peerId,comments.map {it.copy(author=ex.userId)})}
        }
        val paths=selection.flatMap(::photoRefs).filterNot { it.startsWith("sample:") }.toSet()
        val media=linkedMapOf<String,File>(); val pathHashes=mutableMapOf<String,String>()
        paths.forEachIndexed {index,path ->
            val file=File(path).canonicalFile
            require(file.toPath().startsWith(photosRoot.canonicalFile.toPath()) && file.isFile) { "这页有照片找不到了，请重新添加后再寄出" }
            progress("正在检查图片和视频 ${index+1} / ${paths.size}")
            val digest=hash(file); pathHashes[path]=digest
            if(complete || digest !in ex.peerMedia) {
                if(digest !in media) media[digest]=file
            }
        }
        fun path(value: String)=if(value.startsWith("sample:")) value else "photos/${requireNotNull(pathHashes[value])}.bin"
        val diaries=selection.map { d->d.copy(
            authorId=wireAuthor(d.authorId,ex),updatedBy=wireAuthor(d.updatedBy,ex),favorite=false,
            photos=d.photos.map(::path), videos=d.videos.orEmpty().map(::path), elements=d.elements.map {e->e.copy(content=if(e.isMedia()) path(e.content) else e.content,createdBy=wireAuthor(e.createdBy,ex),updatedBy=wireAuthor(e.updatedBy,ex))},
            comments=d.comments.map {it.copy(author=wireAuthor(it.author,ex))}
        ) }
        val removals=ex.removals.values.filter {it.revision>0 && (complete || it.revision>base)}.map {it.copy(authorId=wireAuthor(it.authorId,ex))}
        val bundle=ExchangeBundle(version=if(diaries.any {it.hasVideos()}) 3 else 2,spaceId=spaceId(ex.code),senderId=ex.userId,recipientId=ex.peerId,fromRevision=base,toRevision=ex.revision,acknowledgedRevision=ex.receivedRevision,
            profile=s.profile.copy(lockEnabled=false,homePageId=s.profile.homePageId?.takeIf {id->s.diaries.any {it.id==id && isPublic(it) && it.authorId=="me"}} ?: ""),diaries=diaries,removals=removals,photoHashes=pathHashes.values.toSet(),receivedMedia=ex.receivedMedia.toList().takeLast(5000).toSet(),commentUpdates=commentUpdates)
        require(diaries.size+commentUpdates.size<=MAX_PACKAGE_PAGES && removals.size<=MAX_PACKAGE_PAGES && bundle.photoHashes.size<=5000) { "本次待寄出的内容太多，日记仍已保存在本机" }
        return ExchangePlan(bundle,media)
    }

    fun encode(bundle: ExchangeBundle, media: Map<String,ByteArray>, code: String): ByteArray {
        val buffer=ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            fun entry(name: String, bytes: ByteArray) {require(name.startsWith("photos/") || bytes.size<=MAX_JSON_BYTES) {"本次待寄出的文字或手账太多，日记仍已保存在本机"};zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()}
            entry("manifest.json",gson.toJson(bundle.copy(diaries=emptyList(),removals=emptyList())).toByteArray())
            entry("diaries.json",gson.toJson(bundle.diaries).toByteArray())
            entry("removed.json",gson.toJson(bundle.removals).toByteArray())
            media.forEach { (name,bytes)->require(name.matches(hashPattern) && hash(bytes)==name);entry("photos/$name.bin",bytes) }
        }
        val compressed=buffer.toByteArray();require(compressed.size<=MAX_PACKAGE_BYTES-64) { "这次回忆太大，请减少照片后再寄出" }
        val nonce=ByteArray(12).also {SecureRandom().nextBytes(it)}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(normalizeCode(code).toByteArray()),"AES"),GCMParameterSpec(128,nonce));cipher.updateAAD(magic)
        return magic+nonce+cipher.doFinal(compressed)
    }

    fun open(bytes: ByteArray, code: String): OpenedExchange {
        require(bytes.size in (magic.size+12+16)..MAX_PACKAGE_BYTES && bytes.take(magic.size).toByteArray().contentEquals(magic)) { "请选择 TA 发来的 .couple 回忆文件" }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(normalizeCode(code).toByteArray()),"AES"),GCMParameterSpec(128,bytes.copyOfRange(magic.size,magic.size+12)));cipher.updateAAD(magic)
        val compressed=try {cipher.doFinal(bytes.copyOfRange(magic.size+12,bytes.size))} catch(_: Exception) {throw IllegalArgumentException("口令不一致或文件不完整，请核对口令后重试")}
        val entries=linkedMapOf<String,ByteArray>();var expanded=0L
        ZipInputStream(ByteArrayInputStream(compressed)).use { zip ->
            var entry=zip.nextEntry
            while(entry!=null) {
                val name=entry.name
                require(!entry.isDirectory && (name in setOf("manifest.json","diaries.json","removed.json") || name.matches(Regex("photos/[0-9a-f]{64}\\.bin"))) && name !in entries) { "回忆文件的内容格式不正确" }
                require(entries.size<5004) { "回忆文件内容过多" }
                val output=ByteArrayOutputStream(); val chunk=ByteArray(8192);var count=zip.read(chunk)
                while(count!=-1) {expanded+=count;require(expanded<=MAX_EXPANDED_BYTES && (name.startsWith("photos/") || output.size()+count<=MAX_JSON_BYTES)) { "回忆文件超出可接收大小" };output.write(chunk,0,count);count=zip.read(chunk)}
                entries[name]=output.toByteArray();zip.closeEntry();entry=zip.nextEntry
            }
        }
        fun json(name: String)=requireNotNull(entries[name]) { "回忆文件缺少必要内容" }.toString(Charsets.UTF_8)
        val manifest=try {gson.fromJson(json("manifest.json"),ExchangeBundle::class.java)} catch(_: Exception) {throw IllegalArgumentException("回忆文件的资料格式不正确")}
        val bundle=try {manifest.copy(diaries=gson.fromJson(json("diaries.json"),Array<Diary>::class.java).toList(),removals=gson.fromJson(json("removed.json"),Array<Removal>::class.java).toList(),commentUpdates=manifest.commentUpdates ?: emptyList())} catch(_: Exception) {throw IllegalArgumentException("回忆文件的日记格式不正确")}
        validate(bundle,code)
        val media=entries.filterKeys {it.startsWith("photos/")}.mapKeys {it.key.removePrefix("photos/").removeSuffix(".bin")}
        require(media.keys.all {it in bundle.photoHashes} && media.all {(name,value)->hash(value)==name}) { "照片完整性检查没有通过" }
        return OpenedExchange(bundle,media)
    }

    internal fun validate(b: ExchangeBundle, code: String) {
        try {
            require(b.version in 1..3) { "请双方更新同页后再收下这份回忆" }
            require(b.version>=2 || b.commentUpdates.isEmpty())
            require(b.spaceId==spaceId(code) && b.senderId.matches(idPattern) && (b.recipientId.isEmpty() || b.recipientId.matches(idPattern)))
            require(b.fromRevision>=0 && b.toRevision>=b.fromRevision && b.acknowledgedRevision>=0)
            require(b.profile.name.isNotBlank() && b.profile.name.length<=12 && b.profile.theme in setOf("鼠尾草","雾粉","雾蓝"))
            require(b.profile.startDate.isEmpty() || runCatching {LocalDate.parse(b.profile.startDate)}.isSuccess)
            require(b.diaries.size+b.commentUpdates.size<=MAX_PACKAGE_PAGES && b.diaries.map {it.id}.toSet().size==b.diaries.size && b.removals.size<=MAX_PACKAGE_PAGES && b.photoHashes.size<=5000 && b.receivedMedia.size<=5000)
            require((b.photoHashes+b.receivedMedia).all {it.matches(hashPattern)})
            val actors=setOf(b.senderId,b.recipientId).filter {it.isNotBlank()}.toSet()
            require(b.commentUpdates.map {it.pageId}.distinct().size==b.commentUpdates.size)
            b.commentUpdates.forEach {u->
                require(u.pageId.matches(idPattern) && b.recipientId.isNotBlank() && u.ownerId==b.recipientId)
                require(b.diaries.none {it.id==u.pageId} && u.comments.size in 1..MAX_COMMENTS)
                require(u.comments.map {it.id}.distinct().size==u.comments.size)
                require(u.comments.all {it.id.matches(idPattern) && it.author==b.senderId && it.text.isNotBlank() && it.text.length<=MAX_COMMENT_LENGTH && it.createdAt>=0})
            }
            b.diaries.forEach { d ->
                require(d.id.matches(idPattern) && isPublic(d) && d.authorId in actors && d.updatedBy in actors && d.updatedAt>0 && d.createdAt>0)
                require(d.visibility!="PARTNER" || d.authorId==b.senderId)
                require(d.title.isNotBlank() && d.title.length<=80 && d.body.length<=20000 && d.mood.length<=20 && d.background in paperNames)
                LocalDate.parse(d.date)
                require(d.photos.size+d.videos.orEmpty().size<=100 && d.elements.size<=500 && d.strokes.size<=5000 && d.comments.size<=2000 && d.reactions.size<=10)
                require(b.version>=3 || !d.hasVideos())
                require(d.videos.orEmpty().none {it.startsWith("sample:")} && d.elements.none {it.type=="VIDEO" && it.content.startsWith("sample:")})
                require(d.elements.map {it.id}.toSet().size==d.elements.size && d.strokes.map {it.id}.toSet().size==d.strokes.size)
                require(d.deletedElements.size<=5000 && d.deletedStrokes.size<=10000)
                require((d.deletedElements+d.deletedStrokes).all {(id,time)->id.matches(idPattern) && time>0})
                d.elements.forEach { e->
                    require(e.id.matches(idPattern) && e.type in setOf("TEXT","IMAGE","STICKER","VIDEO") && e.updatedAt>0 && e.createdBy in actors && e.updatedBy in actors)
                    require(listOf(e.x,e.y,e.width,e.height,e.scale,e.rotation,e.fontSize).all {it.isFinite()} && e.width in 1f..5000f && e.height in 1f..5000f && e.scale in .01f..20f && e.fontSize in 1f..200f)
                    require(e.content.length<=300)
                }
                photoRefs(d).forEach { path->require(path in setOf("sample:lake","sample:coffee") || (path.matches(Regex("photos/[0-9a-f]{64}\\.bin")) && path.removePrefix("photos/").removeSuffix(".bin") in b.photoHashes)) }
                d.strokes.forEach { stroke->require(stroke.id.matches(idPattern) && stroke.points.size<=50000 && stroke.width.isFinite() && stroke.width in .1f..50f);require(stroke.points.all {it.x.isFinite() && it.y.isFinite()}) }
                require(d.comments.map {it.id}.distinct().size==d.comments.size)
                require(d.comments.all {it.id.matches(idPattern) && it.author in actors && it.text.length<=MAX_COMMENT_LENGTH && it.createdAt>=0})
                require(d.reactions.all {it in setOf("♡ 喜欢","☾ 晚安","☀ 加油")})
            }
            require(b.removals.all {it.id.matches(idPattern) && it.authorId in actors && it.visibility in setOf("SHARED","PARTNER") && it.updatedAt>0 && it.revision in 1..b.toRevision})
        } catch(e: IllegalArgumentException) {throw IllegalArgumentException(e.message?.takeIf {it.startsWith("请双方")} ?: "回忆文件的内容格式不正确")}
        catch(_: Exception) {throw IllegalArgumentException("回忆文件的内容格式不正确")}
    }
    private val paperNames=setOf("奶油白","方格纸","横线纸","牛皮纸","淡粉","雾蓝","浅紫","夜间纸")

    fun mergeDiary(local: Diary, incoming: Diary, actorOrder: (String)->String = {it}): Diary {
        if(local.visibility!="SHARED" || incoming.visibility!="SHARED") {
            val content=if(incoming.updatedAt>local.updatedAt) incoming.copy(favorite=local.favorite) else local
            return content.copy(comments=mergeComments(local.comments,incoming.comments))
        }
        val latest=if(compareValuesBy(incoming,local,{it.updatedAt},{actorOrder(it.updatedBy)})>0) incoming else local
        fun tombstones(a: Map<String,Long>, b: Map<String,Long>)=(a.keys+b.keys).associateWith {maxOf(a[it] ?: 0,b[it] ?: 0)}
        val deletedElements=tombstones(local.deletedElements,incoming.deletedElements);val deletedStrokes=tombstones(local.deletedStrokes,incoming.deletedStrokes)
        val elements=(local.elements+incoming.elements).groupBy {it.id}.map { (_,versions)->versions.maxWith(compareBy<Element> {it.updatedAt}.thenBy {actorOrder(it.updatedBy)}) }.filter {(deletedElements[it.id] ?: 0)<it.updatedAt}.sortedWith(compareBy<Element> {it.zIndex}.thenBy {it.id})
        val strokes=(local.strokes+incoming.strokes).groupBy {it.id}.map {(_,versions)->versions.maxBy {it.updatedAt}}.filter {(deletedStrokes[it.id] ?: 0)<it.updatedAt}
        return latest.copy(favorite=local.favorite,elements=elements,strokes=strokes,deletedElements=deletedElements,deletedStrokes=deletedStrokes,comments=mergeComments(local.comments,incoming.comments))
    }

    fun receive(state: DiaryState, opened: OpenedExchange, photoPath: (String)->String, now: Long = System.currentTimeMillis()): ExchangeImport {
        val s=normalize(state);val ex=requireNotNull(s.exchange);val b=opened.bundle
        validate(b,ex.code)
        require(b.senderId!=ex.userId) { "这是你寄出的回忆，请选择 TA 发来的文件" }
        require(b.recipientId.isBlank() || b.recipientId==ex.userId) { "这份回忆寄给了另一台手机" }
        require(ex.peerId.isBlank() || ex.peerId==b.senderId) { "这份回忆来自另一个人，请选择 TA 的文件" }
        require(b.acknowledgedRevision<=ex.revision) { "回忆文件的版本记录不正确" }
        require(b.fromRevision<=ex.receivedRevision) { "缺少较早的回忆，请让 TA 使用「重新寄完整回忆」" }
        val acknowledged=maxOf(ex.acknowledgedRevision,b.acknowledgedRevision)
        if(b.toRevision<=ex.receivedRevision) return ExchangeImport(s.copy(exchange=ex.copy(acknowledgedRevision=acknowledged,peerMedia=ex.peerMedia+b.receivedMedia+b.photoHashes)),0,true)
        fun actor(value: String)=when(value) {ex.userId->"me";b.senderId->"partner";else->throw IllegalArgumentException("回忆中的人物信息不正确")}
        fun localPath(path: String)=if(path.startsWith("sample:")) path else photoPath(path.removePrefix("photos/").removeSuffix(".bin"))
        var diaries=s.diaries.associateBy {it.id}.toMutableMap();var changed=0
        b.diaries.forEach { incoming ->
            val old=diaries[incoming.id]
            if(old?.visibility=="PRIVATE") return@forEach
            if(incoming.authorId==ex.userId && (old==null || old.visibility!="SHARED")) return@forEach
            if(old!=null && wireAuthor(old.authorId,ex.copy(peerId=b.senderId))!=incoming.authorId) throw IllegalArgumentException("回忆中的日记归属不一致")
            val removal=ex.removals[incoming.id]
            if(removal!=null && removal.updatedAt>=incoming.updatedAt) return@forEach
            val mapped=incoming.copy(authorId=actor(incoming.authorId),updatedBy=actor(incoming.updatedBy),photos=incoming.photos.map(::localPath),videos=incoming.videos.orEmpty().map(::localPath),elements=incoming.elements.map {it.copy(content=if(it.isMedia()) localPath(it.content) else it.content,createdBy=actor(it.createdBy),updatedBy=actor(it.updatedBy))},comments=incoming.comments.map {it.copy(author=actor(it.author))})
            val next=if(old==null) mapped else mergeDiary(old,mapped) {wireAuthor(it,ex.copy(peerId=b.senderId))}
            if(next!=old) {diaries[next.id]=next;changed++}
        }
        b.commentUpdates.forEach {u->
            val old=diaries[u.pageId] ?: return@forEach
            if(!isPublic(old)) return@forEach
            require(old.authorId=="me" && u.ownerId==ex.userId) { "评论的日记归属不一致" }
            val next=old.copy(comments=mergeComments(old.comments,u.comments.map {it.copy(author=actor(it.author))}))
            if(next!=old) {diaries[next.id]=next;changed++}
        }
        var removals=ex.removals
        b.removals.forEach { r ->
            val old=diaries[r.id]
            if(old!=null && old.visibility!="PRIVATE" && wireAuthor(old.authorId,ex.copy(peerId=b.senderId))==r.authorId && (old.visibility=="SHARED" || r.authorId==b.senderId) && r.updatedAt>=old.updatedAt) {diaries.remove(r.id);changed++}
            if(old?.visibility!="PRIVATE" && (removals[r.id]?.updatedAt ?: 0)<r.updatedAt) removals=removals+(r.id to r.copy(authorId=actor(r.authorId),revision=0))
        }
        val nextEx=ex.copy(peerId=b.senderId,receivedRevision=b.toRevision,acknowledgedRevision=acknowledged,receivedMedia=ex.receivedMedia+opened.media.keys+opened.mediaFiles.keys,peerMedia=ex.peerMedia+b.receivedMedia+b.photoHashes,peerProfile=b.profile,lastReceivedAt=now,lastReceivedCount=changed,removals=removals)
        return ExchangeImport(s.copy(diaries=diaries.values.sortedByDescending {it.date},profile=s.profile.copy(partner=b.profile.name,startDate=s.profile.startDate.ifBlank {b.profile.startDate}),exchange=nextEx),changed,false)
    }
}
