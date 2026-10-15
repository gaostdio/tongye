package app.tongye.diary

import com.google.gson.Gson
import java.io.File
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Unencrypted backup: stream originals and JSON to a completed local ZIP before publishing. */
object BackupFiles {
    fun write(snapshot: DiaryState, photosRoot: File, destination: File, includePrivate: Boolean, progress: (String)->Unit = {}) {
        val safe=snapshot.copy(profile=snapshot.profile.copy(lockEnabled=false),exchange=null,
            diaries=snapshot.diaries.filter {ExchangeEngine.isPublic(it) || (includePrivate && it.authorId=="me" && it.visibility=="PRIVATE")})
        val paths=safe.diaries.flatMap {it.mediaPaths()}.filterNot {it.startsWith("sample:")}.distinct()
        val byPath=mutableMapOf<String,String>();val media=linkedMapOf<String,File>()
        paths.forEachIndexed {index,path->
            val file=File(path).canonicalFile
            require(file.toPath().startsWith(photosRoot.canonicalFile.toPath()) && file.isFile) {"有照片文件缺失，备份未完成；请检查照片后重试"}
            progress("正在检查备份照片 ${index+1} / ${paths.size}")
            val hash=ExchangeEngine.hash(file);byPath[path]="photos/$hash.bin";media.putIfAbsent(hash,file)
        }
        fun portable(path: String)=if(path.startsWith("sample:")) path else requireNotNull(byPath[path])
        val portable=safe.copy(diaries=safe.diaries.map {d->d.copy(photos=d.photos.map(::portable),videos=d.videos.orEmpty().map(::portable),elements=d.elements.map {if(it.isMedia()) it.copy(content=portable(it.content)) else it})})
        try {
            destination.outputStream().buffered(64*1024).use {output->ZipOutputStream(output).use {zip->
                zip.putNextEntry(ZipEntry("diary.json"))
                val writer=OutputStreamWriter(zip,Charsets.UTF_8);Gson().toJson(portable,writer);writer.flush();zip.closeEntry()
                zip.setLevel(0)
                media.entries.forEachIndexed {index,(hash,file)->
                    progress("正在备份照片 ${index+1} / ${media.size}")
                    zip.putNextEntry(ZipEntry("photos/$hash.bin"))
                    val digest=java.security.MessageDigest.getInstance("SHA-256")
                    file.inputStream().buffered().use {ExchangeFiles.copy(it,zip,Long.MAX_VALUE,digest)}
                    require(digest.digest().joinToString("") {"%02x".format(it)}==hash) {"照片在备份时发生变化，请重新导出"}
                    zip.closeEntry()
                }
            }}
        } catch(e: Exception) {destination.delete();throw e}
    }
}
