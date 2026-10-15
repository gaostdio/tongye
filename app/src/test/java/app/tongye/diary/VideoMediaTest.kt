package app.tongye.diary

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class VideoMediaTest {
    @get:Rule val temp=TemporaryFolder()
    private val code="TY1-0123-4567-89AB-CDEF-0123-4567-89AB-CDEF"
    private fun person(name: String)=ExchangeEngine.normalize(DiaryState(initialized=true,profile=Profile(name=name))).let {it.copy(exchange=it.exchange!!.copy(code=code))}
    private fun file(root: File,name: String)=File(root,name).apply {writeBytes(ByteArray(65536) {(it+name.hashCode()).toByte()})}

    @Test fun gifAndVideoStreamAcrossDiaryAndCanvasWithoutPrivateMedia() {
        val root=temp.newFolder();val gif=file(root,"animated.gif");val video=file(root,"clip.mp4")
        var a=ExchangeEngine.save(person("A"),Diary(id="video-diary",title="A clip",visibility="PARTNER",photos=listOf(gif.path),videos=listOf(video.path)))
        a=ExchangeEngine.save(a,Diary(id="video-canvas",title="A canvas",isDiy=true,elements=listOf(Element(type="VIDEO",content=video.path))))
        a=ExchangeEngine.save(a,Diary(id="secret",title="Secret",visibility="PRIVATE",videos=listOf("/not-readable/private.mp4")))
        val packet=temp.newFile();assertEquals(2,ExchangeFiles.prepare(a,root,packet).photoCount)
        packet.inputStream().use {ExchangeFiles.open(it,code,temp.newFolder()).use {opened->
            assertEquals(3,opened.bundle.version);assertEquals(2,opened.mediaCount)
            assertEquals(ExchangeEngine.hash(video),ExchangeEngine.hash(opened.mediaFiles.getValue(ExchangeEngine.hash(video))))
            val b=ExchangeEngine.receive(person("B"),opened,{hash->"/received/$hash.bin"}).state
            val page=b.diaries.single {it.id=="video-diary"}
            assertEquals(listOf("/received/${ExchangeEngine.hash(video)}.bin"),page.videos)
            assertEquals("/received/${ExchangeEngine.hash(gif)}.bin",page.photos.single())
            assertFalse(ExchangeEngine.canEdit(page));assertTrue(ExchangeEngine.canComment(page))
            assertEquals(page.videos.single(),b.diaries.single {it.id=="video-canvas"}.elements.single().content)
            assertFalse(b.diaries.any {it.id=="secret"})
        }}
    }

    @Test fun backupIncludesVideoOriginalsAndOnlyExplicitPrivateMedia() {
        val root=temp.newFolder();val video=file(root,"clip.mp4");val secret=file(root,"secret.mp4")
        var state=ExchangeEngine.save(person("A"),Diary(title="Video",videos=listOf(video.path),isDiy=true,elements=listOf(Element(type="VIDEO",content=video.path))))
        state=ExchangeEngine.save(state,Diary(title="Secret",visibility="PRIVATE",videos=listOf(secret.path)))
        listOf(false,true).forEach {include->
            val archive=temp.newFile();BackupFiles.write(state,root,archive,include)
            ZipFile(archive).use {zip->
                assertEquals(if(include) 3 else 2,zip.size())
                val portable=zip.getInputStream(zip.getEntry("diary.json")).reader().use {Gson().fromJson(it,DiaryState::class.java)}
                assertNull(portable.exchange)
                val page=portable.diaries.single {it.title=="Video"}
                assertEquals(page.videos.single(),page.elements.single().content)
                assertArrayEquals(video.readBytes(),zip.getInputStream(zip.getEntry(page.videos.single())).use {it.readBytes()})
                assertEquals(include,portable.diaries.any {it.title=="Secret"})
            }
        }
    }

    @Test fun videoCannotBeHiddenInOldBundleOrEscapeHashPaths() {
        val root=temp.newFolder();val video=file(root,"clip.mp4")
        val state=ExchangeEngine.save(person("A"),Diary(title="Video",videos=listOf(video.path)))
        val prepared=ExchangeEngine.prepare(state,root);val opened=ExchangeEngine.open(prepared.bytes,code)
        fun rejected(bundle: ExchangeBundle) {assertThrows(IllegalArgumentException::class.java) {ExchangeEngine.open(ExchangeEngine.encode(bundle,opened.media,code),code)}}
        rejected(opened.bundle.copy(version=2))
        rejected(opened.bundle.copy(diaries=opened.bundle.diaries.map {it.copy(videos=listOf("../private.mp4"))}))
        rejected(opened.bundle.copy(diaries=opened.bundle.diaries.map {it.copy(videos=emptyList(),elements=listOf(Element(type="VIDEO",content="file:///private",createdBy=opened.bundle.senderId,updatedBy=opened.bundle.senderId)))}))
    }

    @Test fun oldRoomAndExchangeJsonWithoutVideosRemainsUsable() {
        val old=ExchangeEngine.save(person("A"),Diary(title="Old page"))
        val json=JsonParser.parseString(Gson().toJson(old)).asJsonObject
        json.getAsJsonArray("diaries").forEach {it.asJsonObject.remove("videos")}
        val loaded=ExchangeEngine.normalize(Gson().fromJson(json,DiaryState::class.java))
        assertTrue(loaded.diaries.single().videos.isEmpty());assertEquals(old.exchange!!.userId,loaded.exchange!!.userId)
        val root=temp.newFolder();val prepared=ExchangeEngine.prepare(old,root);val opened=ExchangeEngine.open(prepared.bytes,code)
        val pageJson=JsonParser.parseString(Gson().toJson(opened.bundle.diaries.single())).asJsonObject.apply {remove("videos")}
        val legacy=opened.bundle.copy(diaries=listOf(Gson().fromJson(pageJson,Diary::class.java)))
        val b=ExchangeEngine.receive(person("B"),OpenedExchange(legacy,emptyMap()),{it}).state
        assertEquals("Old page",b.diaries.single().title);assertTrue(b.diaries.single().videos.isEmpty())
    }

    @Test fun concurrentVideoCanvasElementsConvergeAndDeletionIsPreserved() {
        val a=Diary(id="shared",title="Shared",updatedAt=100,updatedBy="a",elements=listOf(Element(id="a",type="VIDEO",content="a.bin",createdBy="a",updatedBy="a",updatedAt=100)))
        val b=a.copy(updatedBy="b",elements=listOf(Element(id="b",type="VIDEO",content="b.bin",createdBy="b",updatedBy="b",updatedAt=100)))
        val ab=ExchangeEngine.mergeDiary(a,b);val ba=ExchangeEngine.mergeDiary(b,a)
        assertEquals(ab,ba);assertEquals(setOf("a","b"),ab.elements.map {it.id}.toSet())
        val removed=ab.copy(updatedAt=200,updatedBy="a",elements=ab.elements.filterNot {it.id=="a"},deletedElements=mapOf("a" to 200L))
        assertEquals(listOf("b"),ExchangeEngine.mergeDiary(b,removed).elements.map {it.id})
        assertEquals(ExchangeEngine.mergeDiary(b,removed),ExchangeEngine.mergeDiary(removed,b))
    }
}
