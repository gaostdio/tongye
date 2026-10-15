package app.tongye.diary

import com.google.gson.Gson
import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Disk-backed transport. Every bounded chunk and the mandatory end record are authenticated. */
object ExchangeFiles {
    internal val MAGIC="TONGYE3\n".toByteArray(Charsets.US_ASCII)
    internal const val CHUNK_BYTES=1024*1024
    const val MAX_EXPANDED_BYTES=8L*1024*1024*1024
    const val MAX_FILE_BYTES=MAX_EXPANDED_BYTES+16L*1024*1024
    private const val MAX_JSON_BYTES=32L*1024*1024
    private val gson=Gson()
    private val photoName=Regex("photos/[0-9a-f]{64}\\.bin")

    fun prepare(state: DiaryState, photosRoot: File, destination: File, complete: Boolean=false, progress: (String)->Unit = {}): PreparedExchangeFile {
        val plan=ExchangeEngine.plan(state,photosRoot,complete,progress)
        require(plan.media.values.sumOf {it.length()}<=MAX_EXPANDED_BYTES-3*MAX_JSON_BYTES) {"这份回忆超过 8 GB，请分次分享；本机记录未改变"}
        try {
            destination.outputStream().buffered(64*1024).use {output->
                ChunkOutput(output,requireNotNull(ExchangeEngine.normalize(state).exchange).code).use {encrypted->
                    ZipOutputStream(encrypted).use {zip->
                        json(zip,"manifest.json",plan.bundle.copy(diaries=emptyList(),removals=emptyList()))
                        json(zip,"diaries.json",plan.bundle.diaries)
                        json(zip,"removed.json",plan.bundle.removals)
                        // JPEG/HEIC are already compressed. Deflating them costs time with little benefit.
                        zip.setLevel(0)
                        plan.media.entries.forEachIndexed {index,(hash,file)->
                            progress("正在装入图片和视频 ${index+1} / ${plan.media.size}")
                            zip.putNextEntry(ZipEntry("photos/$hash.bin"))
                            val digest=MessageDigest.getInstance("SHA-256")
                            file.inputStream().buffered().use {input->copy(input,zip,MAX_EXPANDED_BYTES,digest)}
                            require(hex(digest.digest())==hash) {"照片在导出时发生变化，请重新导出"}
                            zip.closeEntry()
                        }
                    }
                }
            }
            val b=plan.bundle
            return PreparedExchangeFile(b.diaries.size+b.commentUpdates.size,plan.media.size,b.removals.size)
        } catch(e: Exception) {destination.delete();throw e}
    }

    private fun json(zip: ZipOutputStream, name: String, value: Any) {
        zip.putNextEntry(ZipEntry(name))
        val limited=LimitedOutput(zip,MAX_JSON_BYTES)
        val writer=OutputStreamWriter(limited,Charsets.UTF_8)
        gson.toJson(value,writer);writer.flush();zip.closeEntry()
    }

    fun open(source: InputStream, code: String, cacheRoot: File, progress: (String)->Unit = {}): OpenedExchange {
        val input=DataInputStream(source.buffered(64*1024))
        val header=ByteArray(MAGIC.size)
        try {input.readFully(header)} catch(_: EOFException) {throw IllegalArgumentException("回忆文件不完整，请重新发送")}
        if(header.contentEquals("TONGYE2\n".toByteArray())) {
            val buffer=ByteArrayOutputStream();buffer.write(header)
            copy(input,buffer,ExchangeEngine.MAX_PACKAGE_BYTES.toLong()-header.size)
            return ExchangeEngine.open(buffer.toByteArray(),code)
        }
        require(header.contentEquals(MAGIC)) {"请选择 TA 发来的 .couple 文件；新版文件需要双方更新同页"}
        require(cacheRoot.isDirectory || cacheRoot.mkdirs()) {"无法准备收件缓存，请检查手机存储空间"}
        val stage=File(cacheRoot,"incoming-${newId()}")
        require(stage.mkdir()) {"无法准备收件缓存，请检查手机存储空间"}
        var success=false
        try {
            val archive=File(stage,"payload.zip")
            progress("正在校验并打开照片回忆…")
            archive.outputStream().buffered().use {decrypt(input,code,it)}
            val entries=mutableSetOf<String>();var expanded=0L
            val media=linkedMapOf<String,File>();val metadata=mutableMapOf<String,File>()
            ZipFile(archive).use {zip->
                val iterator=zip.entries()
                while(iterator.hasMoreElements()) {
                    val entry=iterator.nextElement();val name=entry.name
                    require(!entry.isDirectory && (name in setOf("manifest.json","diaries.json","removed.json") || name.matches(photoName)) && entries.add(name)) {"回忆文件的内容格式不正确"}
                    require(entries.size<=5003) {"回忆文件内容过多"}
                    val isPhoto=name.startsWith("photos/")
                    val file=File(stage,if(isPhoto) name.removePrefix("photos/") else name)
                    val limit=if(isPhoto) MAX_EXPANDED_BYTES-expanded else minOf(MAX_JSON_BYTES,MAX_EXPANDED_BYTES-expanded)
                    require(limit>=0 && entry.size<=limit) {"回忆文件超出可接收大小"}
                    val digest=MessageDigest.getInstance("SHA-256")
                    file.outputStream().buffered().use {output->zip.getInputStream(entry).use {expanded+=copy(it,output,limit,digest)}}
                    if(isPhoto) {
                        val hash=name.removePrefix("photos/").removeSuffix(".bin")
                        require(hex(digest.digest())==hash) {"照片完整性检查没有通过"}
                        media[hash]=file
                        progress("已校验 ${media.size} 个图片 / 视频文件")
                    } else metadata[name]=file
                }
            }
            fun <T> read(name: String, type: Class<T>): T = requireNotNull(metadata[name]) {"回忆文件缺少必要内容"}.reader(Charsets.UTF_8).use {gson.fromJson(it,type)}
            val bundle=try {
                val manifest=read("manifest.json",ExchangeBundle::class.java)
                manifest.copy(diaries=read("diaries.json",Array<Diary>::class.java).toList(),removals=read("removed.json",Array<Removal>::class.java).toList(),commentUpdates=manifest.commentUpdates ?: emptyList())
            } catch(_: Exception) {throw IllegalArgumentException("回忆文件的内容格式不正确")}
            ExchangeEngine.validate(bundle,code)
            require(media.keys.all {it in bundle.photoHashes}) {"照片完整性检查没有通过"}
            archive.delete();metadata.values.forEach {it.delete()}
            success=true
            return OpenedExchange(bundle,emptyMap(),media,stage)
        } finally {if(!success) {stage.listFiles()?.forEach {it.delete()};stage.delete()}}
    }

    private fun key(code: String)=SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(ExchangeEngine.normalizeCode(code).toByteArray()),"AES")
    private fun cipher(mode: Int, key: SecretKeySpec, prefix: ByteArray, index: Int, size: Int): Cipher {
        val nonce=prefix.copyOf()
        ByteBuffer.wrap(nonce).putInt(8,ByteBuffer.wrap(prefix).getInt(8) xor index)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode,key,GCMParameterSpec(128,nonce))
            updateAAD(MAGIC+prefix+ByteBuffer.allocate(8).putInt(index).putInt(size).array())
        }
    }
    private class ChunkOutput(output: OutputStream, code: String): OutputStream() {
        private val output=DataOutputStream(output)
        private val key=key(code)
        private val prefix=ByteArray(12).also {SecureRandom().nextBytes(it)}
        private val chunk=ByteArray(CHUNK_BYTES)
        private var used=0;private var index=0;private var total=0L;private var closed=false
        init {this.output.write(MAGIC);this.output.write(prefix)}
        override fun write(value: Int) {check(!closed);chunk[used++]=value.toByte();if(used==chunk.size) frame()}
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(!closed);var at=offset;var remaining=length
            while(remaining>0) {val n=minOf(remaining,chunk.size-used);bytes.copyInto(chunk,used,at,at+n);used+=n;at+=n;remaining-=n;if(used==chunk.size) frame()}
        }
        private fun frame() {
            total+=used;require(total<=MAX_EXPANDED_BYTES) {"这份回忆超过 8 GB，请分次分享；本机记录未改变"}
            output.writeInt(used);output.write(cipher(Cipher.ENCRYPT_MODE,key,prefix,index++,used).doFinal(chunk,0,used));used=0
        }
        override fun flush() {output.flush()}
        override fun close() {
            if(closed) return
            try {if(used>0) frame();frame();output.flush()} finally {closed=true;output.close()}
        }
    }
    private fun decrypt(input: DataInputStream, code: String, output: OutputStream) {
        try {
            val prefix=ByteArray(12);input.readFully(prefix);val key=key(code)
            var index=0;var total=0L;var transport=MAGIC.size+12L;var shortFrame=false
            while(true) {
                val size=input.readInt()
                require(size in 0..CHUNK_BYTES && (!shortFrame || size==0)) {"回忆文件的加密分段不正确"}
                transport+=4L+size+16;total+=size
                require(transport<=MAX_FILE_BYTES && total<=MAX_EXPANDED_BYTES && index<10000) {"回忆文件超出可接收大小"}
                val bytes=ByteArray(size+16);input.readFully(bytes)
                val decoded=cipher(Cipher.DECRYPT_MODE,key,prefix,index++,size).doFinal(bytes)
                if(size==0) {require(input.read()==-1) {"回忆文件末尾有多余内容"};break}
                output.write(decoded);if(size<CHUNK_BYTES) shortFrame=true
            }
        } catch(e: IllegalArgumentException) {throw e}
        catch(e: IOException) {if(e is EOFException) throw IllegalArgumentException("回忆文件不完整，请重新发送") else throw e}
        catch(_: Exception) {throw IllegalArgumentException("口令不一致或文件不完整，请重新发送后再收下")}
    }

    internal fun copy(input: InputStream, output: OutputStream, limit: Long, digest: MessageDigest? = null): Long {
        val buffer=ByteArray(64*1024);var total=0L;var n=input.read(buffer)
        while(n!=-1) {total+=n;require(total<=limit) {"文件超过可接收大小"};digest?.update(buffer,0,n);output.write(buffer,0,n);n=input.read(buffer)}
        return total
    }
    private fun hex(bytes: ByteArray)=bytes.joinToString("") {"%02x".format(it)}
    private class LimitedOutput(private val output: OutputStream, private val limit: Long): OutputStream() {
        private var count=0L
        override fun write(value: Int) {count++;require(count<=limit) {"本次文字或手账超过 32 MB，请分次分享"};output.write(value)}
        override fun write(bytes: ByteArray, offset: Int, length: Int) {count+=length;require(count<=limit) {"本次文字或手账超过 32 MB，请分次分享"};output.write(bytes,offset,length)}
    }
}
