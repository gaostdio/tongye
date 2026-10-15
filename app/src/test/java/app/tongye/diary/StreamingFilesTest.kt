package app.tongye.diary

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.util.Random
import java.util.zip.ZipFile

class StreamingFilesTest {
    @get:Rule val folder=TemporaryFolder()
    private val code="TY1-0123-4567-89AB-CDEF-0123-4567-89AB-CDEF"
    private fun person(name: String)=ExchangeEngine.normalize(DiaryState(initialized=true,profile=Profile(name=name))).let {it.copy(exchange=it.exchange!!.copy(code=code))}
    private fun randomFile(root: File, name: String, bytes: Long): File {
        val file=File(root,name);val random=Random(name.hashCode().toLong());val buffer=ByteArray(64*1024)
        file.outputStream().buffered().use {output->var remaining=bytes;while(remaining>0) {random.nextBytes(buffer);val n=minOf(buffer.size.toLong(),remaining).toInt();output.write(buffer,0,n);remaining-=n}}
        return file
    }
    @Test fun manyOriginalPhotosOverOldLimitsExportImportAndBackUpWithoutByteArrays() {
        val photos=folder.newFolder();val cache=folder.newFolder()
        val files=List(50) {randomFile(photos,"photo-$it.bin",3L*1024*1024)}
        var a=ExchangeEngine.save(person("A"),Diary(id="many",title="150 MB original photos",visibility="PARTNER",photos=files.map {it.absolutePath}))
        a=ExchangeEngine.save(a,Diary(id="private",title="private-secret",visibility="PRIVATE",photos=listOf("/private-missing-not-read")))
        assertThrows(IllegalArgumentException::class.java) {ExchangeEngine.prepare(a,photos)}
        val packet=folder.newFile();val prepared=ExchangeFiles.prepare(a,photos,packet)
        assertTrue(packet.length()>128L*1024*1024);assertEquals(50,prepared.photoCount)
        packet.inputStream().use {input->ExchangeFiles.open(input,code,cache).use {opened->
            assertTrue(opened.media.isEmpty());assertEquals(50,opened.mediaFiles.size)
            files.forEach {file->val hash=ExchangeEngine.hash(file);assertEquals(hash,ExchangeEngine.hash(opened.mediaFiles.getValue(hash)))}
            val b=ExchangeEngine.receive(person("B"),opened,{opened.mediaFiles.getValue(it).absolutePath}).state
            assertEquals(1,b.diaries.size);assertEquals(50,b.diaries.single().photos.size)
            assertEquals(50,b.exchange!!.receivedMedia.size)
            a=ExchangeEngine.receive(a,ExchangeEngine.open(ExchangeEngine.prepare(b,photos).bytes,code),{it}).state
        }}
        assertTrue(cache.listFiles()!!.isEmpty())
        val delta=folder.newFile();assertEquals(0,ExchangeFiles.prepare(a,photos,delta).photoCount)
        val backup=folder.newFile();BackupFiles.write(a,photos,backup,false)
        assertTrue(backup.length()>128L*1024*1024)
        ZipFile(backup).use {zip->
            assertEquals(51,zip.size())
            val exported=zip.getInputStream(zip.getEntry("diary.json")).reader().use {Gson().fromJson(it,DiaryState::class.java)}
            assertNull(exported.exchange);assertEquals(1,exported.diaries.size)
            assertFalse(exported.diaries.any {it.title=="private-secret"})
            exported.diaries.single().photos.forEach {assertEquals(3L*1024*1024,zip.getEntry(it).size)}
        }
    }
    @Test fun singleOriginalOver64MbWorksAndPrivateFilesAreNotRead() {
        val photos=folder.newFolder();val file=randomFile(photos,"large.bin",65L*1024*1024)
        val a=ExchangeEngine.save(person("A"),Diary(id="large",title="Large original",photos=listOf(file.absolutePath)))
        val packet=folder.newFile();ExchangeFiles.prepare(a,photos,packet)
        packet.inputStream().use {ExchangeFiles.open(it,code,folder.newFolder()).use {opened->
            assertEquals(65L*1024*1024,opened.mediaFiles.values.single().length())
            assertEquals(ExchangeEngine.hash(file),ExchangeEngine.hash(opened.mediaFiles.values.single()))
        }}
    }
    @Test fun damagedMissingEndingWrongCodeAndTrailingBytesNeverYieldAnImport() {
        val photos=folder.newFolder();val file=randomFile(photos,"tamper.bin",3L*1024*1024)
        val a=ExchangeEngine.save(person("A"),Diary(title="Tamper",photos=listOf(file.absolutePath)))
        val packet=folder.newFile();ExchangeFiles.prepare(a,photos,packet)
        val bytes=packet.readBytes();val cache=folder.newFolder()
        val corrupt=bytes.copyOf().apply {this[100]=(this[100].toInt() xor 1).toByte()}
        val corruptEnd=bytes.copyOf().apply {this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()}
        listOf(corrupt,corruptEnd,bytes.copyOf(bytes.size-20),bytes+byteArrayOf(1)).forEach {bad->
            assertThrows(IllegalArgumentException::class.java) {ExchangeFiles.open(bad.inputStream(),code,cache)}
            assertTrue(cache.listFiles()!!.isEmpty())
        }
        assertThrows(IllegalArgumentException::class.java) {ExchangeFiles.open(bytes.inputStream(),ExchangeEngine.newCode(),cache)}
        assertTrue(cache.listFiles()!!.isEmpty())
    }
    @Test fun reorderedFramesAndChangedFrameLengthAreRejected() {
        val photos=folder.newFolder();val file=randomFile(photos,"reorder.bin",3L*1024*1024)
        val a=ExchangeEngine.save(person("A"),Diary(title="Reorder",photos=listOf(file.absolutePath)))
        val packet=folder.newFile();ExchangeFiles.prepare(a,photos,packet)
        val bytes=packet.readBytes();val start=ExchangeFiles.MAGIC.size+12;val frame=4+ExchangeFiles.CHUNK_BYTES+16
        val swapped=bytes.copyOf()
        bytes.copyInto(swapped,start,start+frame,start+2*frame);bytes.copyInto(swapped,start+frame,start,start+frame)
        val size=bytes.copyOf().apply {this[start]=0x7f}
        listOf(swapped,size).forEach {bad->assertThrows(IllegalArgumentException::class.java) {ExchangeFiles.open(bad.inputStream(),code,folder.newFolder())}}
    }
    @Test fun legacySmallLettersStillOpenAndNewFilesAreRandomized() {
        val root=folder.newFolder();val a=ExchangeEngine.save(person("A"),Diary(title="Legacy"))
        ExchangeFiles.open(ExchangeEngine.prepare(a,root).bytes.inputStream(),code,folder.newFolder()).use {assertEquals("Legacy",it.bundle.diaries.single().title)}
        val first=folder.newFile();val second=folder.newFile()
        ExchangeFiles.prepare(a,root,first);ExchangeFiles.prepare(a,root,second)
        assertFalse(first.readBytes().contentEquals(second.readBytes()))
    }
    @Test fun backupDeduplicatesContentAndCannotCollideOnOriginalFilename() {
        val photos=folder.newFolder();val first=File(photos,"first").apply {mkdir()};val second=File(photos,"second").apply {mkdir()}
        val a=randomFile(first,"same.jpg",1024);val b=randomFile(second,"same.jpg",2048)
        val duplicate=File(photos,"duplicate.jpg").apply {writeBytes(a.readBytes())}
        val state=ExchangeEngine.save(person("A"),Diary(title="Same names",photos=listOf(a.absolutePath,b.absolutePath,duplicate.absolutePath)))
        val backup=folder.newFile();BackupFiles.write(state,photos,backup,false)
        ZipFile(backup).use {zip->
            assertEquals(3,zip.size())
            val exported=zip.getInputStream(zip.getEntry("diary.json")).reader().use {Gson().fromJson(it,DiaryState::class.java)}
            val paths=exported.diaries.single().photos
            assertEquals(paths[0],paths[2]);assertNotEquals(paths[0],paths[1])
            assertEquals(1024L,zip.getEntry(paths[0]).size);assertEquals(2048L,zip.getEntry(paths[1]).size)
        }
    }
    @Test fun privateBackupIsExplicitAndMissingPublicPhotoDoesNotLeaveCompletedArchive() {
        val photos=folder.newFolder();val photo=randomFile(photos,"secret.jpg",100)
        val a=ExchangeEngine.save(person("A"),Diary(title="Secret",visibility="PRIVATE",photos=listOf(photo.absolutePath)))
        val public=folder.newFile();BackupFiles.write(a,photos,public,false)
        ZipFile(public).use {assertEquals(1,it.size())}
        val private=folder.newFile();BackupFiles.write(a,photos,private,true)
        ZipFile(private).use {assertEquals(2,it.size())}
        val broken=ExchangeEngine.save(person("A"),Diary(title="Broken",photos=listOf(File(photos,"missing.jpg").absolutePath)))
        val destination=File(folder.newFolder(),"failed.couple")
        assertThrows(IllegalArgumentException::class.java) {ExchangeFiles.prepare(broken,photos,destination)}
        assertFalse(destination.exists())
    }
    @Test fun interruptedOutputFailsWithoutClearingLocalRecords() {
        val photos=folder.newFolder();val a=ExchangeEngine.save(person("A"),Diary(id="stable",title="Keep me"))
        val packet=folder.newFile();ExchangeFiles.prepare(a,photos,packet)
        val short=packet.readBytes().copyOf(10)
        assertThrows(IllegalArgumentException::class.java) {ExchangeFiles.open(short.inputStream(),code,folder.newFolder())}
        assertEquals("Keep me",a.diaries.single().title)
    }
}
