package app.tongye.diary

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ExchangeTest {
    @get:Rule val folder=TemporaryFolder()
    private val code="TY1-0123-4567-89AB-CDEF-0123-4567-89AB-CDEF"
    private fun person(name: String)=ExchangeEngine.normalize(DiaryState(initialized=true,profile=Profile(name=name))).let {it.copy(exchange=it.exchange!!.copy(code=code))}
    private fun transfer(from: DiaryState, to: DiaryState, root: File): ExchangeImport {
        val file=ExchangeEngine.prepare(from,root)
        val opened=ExchangeEngine.open(file.bytes,code)
        return ExchangeEngine.receive(to,opened,{"/received/$it.bin"})
    }
    @Test fun concurrentCommentsMergeInBothDirectionsWithoutDuplicating() {
        val root=folder.newFolder()
        var a=ExchangeEngine.save(person("A"),Diary(id="comments",title="Comments",body="original"),1000)
        var b=transfer(a,person("B"),root).state
        a=transfer(b,a,root).state
        a=ExchangeEngine.addComment(a,"comments","  from A  ",3000)
        b=ExchangeEngine.addComment(b,"comments","from B",3000)
        val packetA=ExchangeEngine.prepare(a,root);val packetB=ExchangeEngine.prepare(b,root)
        assertEquals(1,packetA.diaryCount);assertEquals(1,packetB.diaryCount)
        val openedA=ExchangeEngine.open(packetA.bytes,code);val openedB=ExchangeEngine.open(packetB.bytes,code)
        a=ExchangeEngine.receive(a,openedB,{it}).state
        b=ExchangeEngine.receive(b,openedA,{it}).state
        assertEquals(setOf("from A","from B"),a.diaries.single().comments.map {it.text}.toSet())
        assertEquals(a.diaries.single().comments.map {it.id},b.diaries.single().comments.map {it.id})
        assertEquals("me",a.diaries.single().comments.first {it.text=="from A"}.author)
        assertEquals("partner",b.diaries.single().comments.first {it.text=="from A"}.author)
        assertEquals(1000L,a.diaries.single().updatedAt)
        assertEquals("original",b.diaries.single().body)
        val repeated=ExchangeEngine.receive(a,openedB,{it})
        assertTrue(repeated.duplicate);assertEquals(2,repeated.state.diaries.single().comments.size)
    }
    @Test fun commentOnOlderCopyDoesNotOverwritePeersNewBody() {
        val root=folder.newFolder()
        var a=ExchangeEngine.save(person("A"),Diary(id="body-comment",title="old title",body="old body"),1000)
        var b=transfer(a,person("B"),root).state
        a=ExchangeEngine.save(a,a.diaries.single().copy(title="new title",body="new body"),2000)
        b=ExchangeEngine.addComment(b,"body-comment","nice page",9000)
        val packetA=ExchangeEngine.prepare(a,root);val packetB=ExchangeEngine.prepare(b,root)
        a=ExchangeEngine.receive(a,ExchangeEngine.open(packetB.bytes,code),{it}).state
        b=ExchangeEngine.receive(b,ExchangeEngine.open(packetA.bytes,code),{it}).state
        listOf(a,b).forEach {state->
            assertEquals("new body",state.diaries.single().body)
            assertEquals("new title",state.diaries.single().title)
            assertEquals(2000L,state.diaries.single().updatedAt)
            assertEquals("nice page",state.diaries.single().comments.single().text)
        }
    }
    @Test fun savingOlderEditorDraftPreservesReceivedComments() {
        val original=ExchangeEngine.save(person("A"),Diary(id="draft"),1000)
        val draft=original.diaries.single().copy(body="edited")
        val commented=ExchangeEngine.addComment(original,"draft","keep this",3000)
        val saved=ExchangeEngine.save(commented,draft,4000)
        assertEquals("edited",saved.diaries.single().body)
        assertEquals("keep this",saved.diaries.single().comments.single().text)
    }
    @Test fun commentsValidateInputAndCannotBypassPrivateOrReadOnlyBody() {
        var s=ExchangeEngine.save(person("A"),Diary(id="shared-comment"),1000)
        s=ExchangeEngine.save(s,Diary(id="private-comment",visibility="PRIVATE"),1000)
        s=ExchangeEngine.save(s,Diary(id="personal-comment",visibility="PARTNER",authorId="partner"),1000)
        s=s.copy(diaries=s.diaries+Diary(id="readonly-comment",visibility="PARTNER",authorId="partner"))
        listOf("private-comment","missing").forEach {id->
            assertThrows(IllegalArgumentException::class.java) {ExchangeEngine.addComment(s,id,"no")}
        }
        listOf("personal-comment","readonly-comment").forEach {id->
            assertEquals("allowed",ExchangeEngine.addComment(s,id,"allowed").diaries.first {it.id==id}.comments.single().text)
        }
        assertThrows(IllegalArgumentException::class.java) {ExchangeEngine.save(s,s.diaries.first {it.id=="readonly-comment"}.copy(body="forbidden"))}
        listOf("  ","a".repeat(201)).forEach {text->
            assertThrows(IllegalArgumentException::class.java) {ExchangeEngine.addComment(s,"shared-comment",text)}
        }
        val full=s.copy(diaries=s.diaries.map {if(it.id=="shared-comment") it.copy(comments=List(2000) {Comment(id="c-$it",text="full")}) else it})
        assertThrows(IllegalArgumentException::class.java) {ExchangeEngine.addComment(full,"shared-comment","over")}
    }
    @Test fun legacyCommentsWithoutTimestampSurviveNewCommentRoundTrip() {
        val legacy=Gson().fromJson("""{"id":"legacy","author":"me","text":"old comment","date":"2026-10-04"}""",Comment::class.java)
        assertEquals(0L,legacy.createdAt)
        val root=folder.newFolder()
        var a=ExchangeEngine.save(person("A"),Diary(id="old-comment-page",title="Legacy comments",comments=listOf(legacy)),1000)
        a=ExchangeEngine.addComment(a,"old-comment-page","new comment",2000)
        val b=transfer(a,person("B"),root).state
        assertEquals(listOf("old comment","new comment"),b.diaries.single().comments.map {it.text})
    }
    @Test fun partnerPersonalDiaryAndCanvasCommentsReturnWithoutBodyOrPhotos() {
        listOf(false,true).forEach {diy->
            val root=folder.newFolder();val photo=File(root,"personal.png").apply {writeBytes(byteArrayOf(5,6,7))}
            var a=ExchangeEngine.save(person("A"),Diary(id="personal",title="Personal",body="owner body",visibility="PARTNER",isDiy=diy,photos=listOf(photo.absolutePath)),1000)
            var b=transfer(a,person("B"),root).state
            val before=b.diaries.single()
            assertFalse(ExchangeEngine.canEdit(before));assertTrue(ExchangeEngine.canComment(before))
            b=ExchangeEngine.addComment(b,"personal","reader comment",2000)
            assertEquals(before.copy(comments=b.diaries.single().comments),b.diaries.single())
            val prepared=ExchangeEngine.prepare(b,root)
            val opened=ExchangeEngine.open(prepared.bytes,code)
            assertEquals(1,prepared.diaryCount);assertEquals(0,prepared.photoCount)
            assertTrue(opened.bundle.diaries.isEmpty());assertTrue(opened.media.isEmpty())
            assertEquals(a.exchange!!.userId,opened.bundle.commentUpdates.single().ownerId)
            a=ExchangeEngine.receive(a,opened,{it}).state
            assertEquals("owner body",a.diaries.single().body);assertEquals(1000L,a.diaries.single().updatedAt)
            assertEquals("partner",a.diaries.single().comments.single().author)
            assertTrue(ExchangeEngine.receive(a,opened,{it}).duplicate)
            assertEquals(1,ExchangeEngine.prepare(b,root,complete=true).diaryCount)
            b=transfer(a,b,root).state
            assertEquals(0,ExchangeEngine.prepare(b,root).diaryCount)
            assertThrows(IllegalArgumentException::class.java) {ExchangeEngine.delete(b,"personal")}
        }
    }
    @Test fun concurrentOwnerEditReplyAndReaderCommentConvergeOnPersonalPage() {
        val root=folder.newFolder()
        var a=ExchangeEngine.save(person("A"),Diary(id="personal-concurrent",title="Before",body="old",visibility="PARTNER"),1000)
        var b=transfer(a,person("B"),root).state
        a=ExchangeEngine.save(a,a.diaries.single().copy(title="After",body="new"),2000)
        a=ExchangeEngine.addComment(a,"personal-concurrent","owner reply",3000)
        b=ExchangeEngine.addComment(b,"personal-concurrent","reader reply",9000)
        val fromA=ExchangeEngine.prepare(a,root);val fromB=ExchangeEngine.prepare(b,root)
        a=ExchangeEngine.receive(a,ExchangeEngine.open(fromB.bytes,code),{it}).state
        b=ExchangeEngine.receive(b,ExchangeEngine.open(fromA.bytes,code),{it}).state
        listOf(a,b).forEach {s->
            assertEquals("new",s.diaries.single().body);assertEquals("After",s.diaries.single().title)
            assertEquals(setOf("owner reply","reader reply"),s.diaries.single().comments.map {it.text}.toSet())
            assertEquals(2000L,s.diaries.single().updatedAt)
        }
        assertEquals(a.diaries.single().comments.map {it.id},b.diaries.single().comments.map {it.id})
    }
    @Test fun personalCommentsMergeWhenBodyTimestampIsUnchanged() {
        val root=folder.newFolder()
        var a=ExchangeEngine.save(person("A"),Diary(id="same-clock",title="Same clock",visibility="PARTNER"),1000)
        var b=transfer(a,person("B"),root).state
        b=ExchangeEngine.addComment(b,"same-clock","reader",2000)
        a=transfer(b,a,root).state
        a=ExchangeEngine.addComment(a,"same-clock","owner",3000)
        b=transfer(a,b,root).state
        assertEquals(setOf("reader","owner"),b.diaries.single().comments.map {it.text}.toSet())
        assertEquals(1000L,b.diaries.single().updatedAt)
    }
    @Test fun personalCommentCannotResurrectDeletedOrPrivatePage() {
        val root=folder.newFolder()
        val a=ExchangeEngine.save(person("A"),Diary(id="closed",title="Public",visibility="PARTNER"),1000)
        val b=ExchangeEngine.addComment(transfer(a,person("B"),root).state,"closed","late comment",9000)
        val deleted=transfer(b,ExchangeEngine.delete(a,"closed",2000),root).state
        assertTrue(deleted.diaries.isEmpty())
        val private=ExchangeEngine.save(a,a.diaries.single().copy(visibility="PRIVATE",body="private-secret"),2000)
        val received=transfer(b,private,root).state
        assertEquals(private.diaries,received.diaries)
        val outgoing=ExchangeEngine.open(ExchangeEngine.prepare(received,root).bytes,code).bundle
        assertTrue(outgoing.diaries.isEmpty());assertTrue(outgoing.commentUpdates.isEmpty())
    }
    @Test fun forgedPersonalCommentOwnersAuthorsAndBodiesAreRejected() {
        val root=folder.newFolder()
        val a=ExchangeEngine.save(person("A"),Diary(id="safe",title="Safe",visibility="PARTNER"),1000)
        val b=ExchangeEngine.addComment(transfer(a,person("B"),root).state,"safe","reader",2000)
        val packet=ExchangeEngine.open(ExchangeEngine.prepare(b,root).bytes,code).bundle
        val update=packet.commentUpdates.single()
        listOf(
            packet.copy(commentUpdates=listOf(update.copy(ownerId=packet.senderId))),
            packet.copy(commentUpdates=listOf(update.copy(comments=update.comments.map {it.copy(author=packet.recipientId)}))),
            packet.copy(diaries=listOf(a.diaries.single().copy(authorId=a.exchange!!.userId,updatedBy=a.exchange!!.userId)),commentUpdates=emptyList()),
            packet.copy(commentUpdates=listOf(update,update))
        ).forEach {forged->
            assertThrows(IllegalArgumentException::class.java) {ExchangeEngine.open(ExchangeEngine.encode(forged,emptyMap(),code),code)}
        }
    }
    @Test fun versionOneFileWithoutCommentUpdatesStillImports() {
        val root=folder.newFolder()
        val a=ExchangeEngine.save(person("A"),Diary(id="legacy-v1",title="Legacy",visibility="PARTNER"),1000)
        val current=ExchangeEngine.open(ExchangeEngine.prepare(a,root).bytes,code).bundle
        val gson=Gson();val json=gson.toJsonTree(current.copy(version=1)).asJsonObject
        json.remove("commentUpdates")
        json.add("diaries",gson.toJsonTree(emptyList<Diary>()))
        val buffer=ByteArrayOutputStream()
        ZipOutputStream(buffer).use {zip->
            mapOf("manifest.json" to gson.toJson(json),"diaries.json" to gson.toJson(current.diaries),"removed.json" to "[]").forEach {(name,value)->
                zip.putNextEntry(ZipEntry(name));zip.write(value.toByteArray());zip.closeEntry()
            }
        }
        val magic="TONGYE2\n".toByteArray();val nonce=ByteArray(12)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(code.toByteArray()),"AES"),GCMParameterSpec(128,nonce));cipher.updateAAD(magic)
        val opened=ExchangeEngine.open(magic+nonce+cipher.doFinal(buffer.toByteArray()),code)
        assertEquals(1,opened.bundle.version);assertTrue(opened.bundle.commentUpdates.isEmpty())
        val b=ExchangeEngine.receive(person("B"),opened,{it}).state
        assertEquals("Legacy",b.diaries.single().title)
    }
    @Test fun roundTripFiltersPrivateAndNeverRepeatsEntries() {
        val root=folder.newFolder();var a=person("A");val b=person("B")
        a=ExchangeEngine.save(a,Diary(id="public",title="for you",visibility="PARTNER"))
        a=ExchangeEngine.save(a,Diary(id="secret",title="private-secret",visibility="PRIVATE",body="only for myself"))
        val prepared=ExchangeEngine.prepare(a,root);val opened=ExchangeEngine.open(prepared.bytes,code)
        assertEquals(listOf("public"),opened.bundle.diaries.map {it.id})
        assertFalse(prepared.bytes.toString(Charsets.ISO_8859_1).contains("private-secret"))
        val received=ExchangeEngine.receive(b,opened,{it})
        assertEquals("partner",received.state.diaries.single().authorId)
        assertEquals("A",received.state.profile.partner)
        val repeated=ExchangeEngine.receive(received.state,opened,{it})
        assertTrue(repeated.duplicate);assertEquals(0,repeated.changedCount);assertEquals(1,repeated.state.diaries.size)
    }
    @Test fun wrongKeyTamperingAndForeignPartnerAreRejected() {
        val root=folder.newFolder();val a=person("A");val b=person("B")
        val bytes=ExchangeEngine.prepare(a,root).bytes
        assertThrows(IllegalArgumentException::class.java) {ExchangeEngine.open(bytes,ExchangeEngine.newCode())}
        val damaged=bytes.copyOf();damaged[damaged.lastIndex]=(damaged.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {ExchangeEngine.open(damaged,code)}
        val received=transfer(a,b,root).state
        val stranger=person("C")
        assertThrows(IllegalArgumentException::class.java) {transfer(stranger,received,root)}
        assertThrows(IllegalArgumentException::class.java) {transfer(a,a,root)}
    }
    @Test fun acknowledgmentAllowsIncrementalPagesAndPhotos() {
        val root=folder.newFolder();val photo=File(root,"first.png").apply {writeBytes(byteArrayOf(1,2,3,4))}
        var a=ExchangeEngine.save(person("A"),Diary(id="page",title="first",photos=listOf(photo.absolutePath)))
        a=ExchangeEngine.save(a,Diary(id="second",title="unchanged"))
        val first=ExchangeEngine.prepare(a,root);assertEquals(1,first.photoCount);assertEquals(2,first.diaryCount)
        val b=ExchangeEngine.receive(person("B"),ExchangeEngine.open(first.bytes,code),{photo.absolutePath}).state
        a=transfer(b,a,root).state
        assertEquals(a.exchange!!.revision,a.exchange!!.acknowledgedRevision)
        val before=ExchangeEngine.prepare(a,root);assertEquals(0,before.diaryCount);assertEquals(0,before.photoCount)
        a=ExchangeEngine.save(a,a.diaries.first {it.id=="page"}.copy(body="new words"))
        val delta=ExchangeEngine.prepare(a,root);assertEquals(1,delta.diaryCount);assertEquals(0,delta.photoCount)
        val updated=ExchangeEngine.receive(b,ExchangeEngine.open(delta.bytes,code),{photo.absolutePath}).state
        assertEquals("new words",updated.diaries.first {it.id=="page"}.body)
        assertEquals(1,ExchangeEngine.prepare(a,root,complete=true).photoCount)
    }
    @Test fun concurrentDifferentElementsMergeAndDeletedElementsStayDeleted() {
        val root=folder.newFolder()
        var a=ExchangeEngine.save(person("A"),Diary(id="canvas",title="ours",isDiy=true,elements=listOf(Element(id="first",content="hello"))),1000)
        var b=transfer(a,person("B"),root).state
        a=ExchangeEngine.save(a,a.diaries.single().copy(elements=a.diaries.single().elements+Element(id="A-added",content="A")),2000)
        b=ExchangeEngine.save(b,b.diaries.single().copy(elements=b.diaries.single().elements+Element(id="B-added",content="B")),3000)
        val fromA=ExchangeEngine.prepare(a,root);val fromB=ExchangeEngine.prepare(b,root)
        a=ExchangeEngine.receive(a,ExchangeEngine.open(fromB.bytes,code),{it}).state
        b=ExchangeEngine.receive(b,ExchangeEngine.open(fromA.bytes,code),{it}).state
        assertEquals(setOf("first","A-added","B-added"),a.diaries.single().elements.map {it.id}.toSet())
        assertEquals(a.diaries.single().elements.map {it.id}.toSet(),b.diaries.single().elements.map {it.id}.toSet())
        a=ExchangeEngine.save(a,a.diaries.single().copy(elements=a.diaries.single().elements.filterNot {it.id=="first"}),4000)
        b=transfer(a,b,root).state
        assertFalse(b.diaries.single().elements.any {it.id=="first"})
    }
    @Test fun privateTransitionWithdrawsSharedPageWithoutSendingPrivateWords() {
        val root=folder.newFolder();var a=ExchangeEngine.save(person("A"),Diary(id="shared",title="public"))
        val b=transfer(a,person("B"),root).state
        a=ExchangeEngine.save(a,a.diaries.single().copy(visibility="PRIVATE",body="new private words"))
        val opened=ExchangeEngine.open(ExchangeEngine.prepare(a,root).bytes,code)
        assertTrue(opened.bundle.diaries.isEmpty());assertEquals("shared",opened.bundle.removals.single().id)
        val received=ExchangeEngine.receive(b,opened,{it})
        assertTrue(received.state.diaries.isEmpty());assertEquals("new private words",a.diaries.single().body)
    }
    @Test fun deletionPropagatesAndReadOnlyPartnerCannotBeEdited() {
        val root=folder.newFolder();var a=ExchangeEngine.save(person("A"),Diary(id="page",title="ours"))
        val b=transfer(a,person("B"),root).state
        a=ExchangeEngine.delete(a,"page")
        assertTrue(transfer(a,b,root).state.diaries.isEmpty())
        val partner=ExchangeEngine.save(person("A"),Diary(id="own",title="mine",visibility="PARTNER"))
        val readOnly=transfer(partner,person("B"),root).state
        assertThrows(IllegalArgumentException::class.java) {ExchangeEngine.save(readOnly,readOnly.diaries.single().copy(title="overwrite"))}
    }
    @Test fun oldSnapshotsGainStableIdentityAndStrokeIds() {
        val old="""{"initialized":true,"profile":{"name":"old","partner":"TA","startDate":"","theme":"鼠尾草","lockEnabled":false},"diaries":[{"id":"old-page","authorId":"me","visibility":"SHARED","title":"old","body":"","date":"2026-10-05","mood":"晴朗","photos":[],"elements":[],"strokes":[{"points":[],"color":0,"width":3}],"background":"奶油白","isDiy":true,"favorite":false,"reactions":[],"comments":[],"createdAt":1,"updatedAt":1}],"anniversaries":[],"notes":[]}"""
        val s=ExchangeEngine.normalize(Gson().fromJson(old,DiaryState::class.java))
        assertTrue(s.exchange!!.userId.isNotBlank());assertTrue(s.diaries.single().strokes.single().id.isNotBlank())
        assertEquals(s,ExchangeEngine.normalize(s));assertEquals(1L,s.exchange!!.revisions["old-page"])
    }
    @Test fun deletingPrivatePageStaysLocalAndClearsItsReferences() {
        val root=folder.newFolder()
        var a=ExchangeEngine.save(person("A"),Diary(id="private-delete",title="only me",visibility="PRIVATE"))
        a=ExchangeEngine.delete(a,"private-delete")
        val prepared=ExchangeEngine.prepare(a,root)
        val opened=ExchangeEngine.open(prepared.bytes,code)
        assertTrue(a.diaries.isEmpty());assertTrue(opened.bundle.removals.isEmpty())
        assertEquals(0,prepared.removalCount);assertFalse(a.exchange!!.revisions.containsKey("private-delete"))
        a=ExchangeEngine.save(a,Diary(id="cover-delete",title="my cover",isDiy=true,visibility="PARTNER"))
        a=a.copy(profile=a.profile.copy(homePageId="cover-delete"))
        a=ExchangeEngine.delete(a,"cover-delete")
        assertEquals("",a.profile.homePageId)
        assertEquals(1,ExchangeEngine.prepare(a,root).removalCount)
    }
    @Test fun eitherPartnerCanDeleteSharedPageAndRepeatedRemovalIsHarmless() {
        val root=folder.newFolder()
        val a=ExchangeEngine.save(person("A"),Diary(id="shared-delete",title="ours",isDiy=true),1000)
        var b=transfer(a,person("B"),root).state
        b=ExchangeEngine.delete(b,"shared-delete",3000)
        val prepared=ExchangeEngine.prepare(b,root)
        assertEquals(0,prepared.diaryCount);assertEquals(1,prepared.removalCount)
        val opened=ExchangeEngine.open(prepared.bytes,code)
        val received=ExchangeEngine.receive(a,opened,{it})
        assertTrue(received.state.diaries.isEmpty());assertEquals(1,received.changedCount)
        val repeated=ExchangeEngine.receive(received.state,opened,{it})
        assertTrue(repeated.duplicate);assertTrue(repeated.state.diaries.isEmpty())
    }
    @Test fun personalPageDeletePropagatesAndOldPageDoesNotReturn() {
        val root=folder.newFolder()
        val original=ExchangeEngine.save(person("A"),Diary(id="personal-delete",title="mine",visibility="PARTNER"),1000)
        var b=transfer(original,person("B"),root).state
        assertThrows(IllegalArgumentException::class.java) {ExchangeEngine.delete(b,"personal-delete")}
        val deleted=ExchangeEngine.delete(original,"personal-delete",3000)
        b=transfer(deleted,b,root).state
        assertTrue(b.diaries.isEmpty())
        // A new transport version containing an older page must respect the deletion timestamp.
        val stale=original.copy(exchange=original.exchange!!.copy(revision=deleted.exchange!!.revision+10))
        b=transfer(stale,b,root).state
        assertTrue(b.diaries.isEmpty())
    }
    @Test fun nicknameAndThreeSpacesRemainIndependent() {
        val mine=Diary(id="mine",visibility="PARTNER")
        val theirs=Diary(id="theirs",visibility="PARTNER",authorId="partner")
        val common=Diary(id="common",visibility="SHARED")
        val secret=Diary(id="private",visibility="PRIVATE")
        assertEquals(setOf("mine","common"),visibleDiaries(listOf(mine,theirs,common,secret),"me","MINE").map {it.id}.toSet())
        assertEquals(listOf("theirs"),visibleDiaries(listOf(mine,theirs,common,secret),"me","PARTNER").map {it.id})
        assertEquals(listOf("common"),visibleDiaries(listOf(mine,theirs,common,secret),"me","SHARED").map {it.id})
    }
    @Test fun rejectsTraversalZipAndPhotosOutsidePrivatePhotoDirectory() {
        val zipBytes=ByteArrayOutputStream()
        ZipOutputStream(zipBytes).use {it.putNextEntry(ZipEntry("../outside.json"));it.write("{}".toByteArray());it.closeEntry()}
        val magic="TONGYE2\n".toByteArray();val nonce=ByteArray(12)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(code.toByteArray()),"AES"),GCMParameterSpec(128,nonce));cipher.updateAAD(magic)
        assertThrows(IllegalArgumentException::class.java) {ExchangeEngine.open(magic+nonce+cipher.doFinal(zipBytes.toByteArray()),code)}
        val root=folder.newFolder();val other=folder.newFile().apply {writeBytes(byteArrayOf(1))}
        val state=ExchangeEngine.save(person("A"),Diary(title="bad path",photos=listOf(other.absolutePath)))
        assertThrows(IllegalArgumentException::class.java) {ExchangeEngine.prepare(state,root)}
        val privateOnly=ExchangeEngine.save(person("A"),Diary(title="secret",visibility="PRIVATE",photos=listOf(other.absolutePath)))
        assertEquals(0,ExchangeEngine.prepare(privateOnly,root).photoCount)
    }
    @Test fun conflictingElementTieUsesPersistentIdentityOnBothSides() {
        val root=folder.newFolder()
        var a=ExchangeEngine.save(person("A"),Diary(id="same",title="same",isDiy=true,elements=listOf(Element(id="text",content="initial"))),1000)
        var b=transfer(a,person("B"),root).state
        a=ExchangeEngine.save(a,a.diaries.single().copy(elements=listOf(a.diaries.single().elements.single().copy(content="A"))),2000)
        b=ExchangeEngine.save(b,b.diaries.single().copy(elements=listOf(b.diaries.single().elements.single().copy(content="B"))),2000)
        val packetA=ExchangeEngine.prepare(a,root);val packetB=ExchangeEngine.prepare(b,root)
        a=ExchangeEngine.receive(a,ExchangeEngine.open(packetB.bytes,code),{it}).state
        b=ExchangeEngine.receive(b,ExchangeEngine.open(packetA.bytes,code),{it}).state
        assertEquals(a.diaries.single().elements.single().content,b.diaries.single().elements.single().content)
    }
    @Test fun returningSharedCanvasDoesNotResendOriginalAuthorsPhoto() {
        val root=folder.newFolder();val photo=File(root,"original.png").apply {writeBytes(byteArrayOf(3,4,5))}
        var a=ExchangeEngine.save(person("A"),Diary(id="canvas-photo",title="ours",isDiy=true,elements=listOf(Element(id="image",type="IMAGE",content=photo.absolutePath))))
        var b=transfer(a,person("B"),root).state
        // Simulate the receiver's different local filename, as on Android.
        val imported=File(root,"received.bin").apply {writeBytes(photo.readBytes())}
        b=b.copy(diaries=b.diaries.map {d->d.copy(elements=d.elements.map {it.copy(content=imported.absolutePath)})})
        b=ExchangeEngine.save(b,b.diaries.single().copy(elements=b.diaries.single().elements+Element(id="reply",content="from B")))
        val reply=ExchangeEngine.prepare(b,root)
        assertEquals(1,reply.diaryCount);assertEquals(0,reply.photoCount)
        a=ExchangeEngine.receive(a,ExchangeEngine.open(reply.bytes,code),{photo.absolutePath}).state
        assertEquals(setOf("image","reply"),a.diaries.single().elements.map {it.id}.toSet())
    }
    @Test fun writeFictionalPacketForDeviceSmokeTest() {
        val root=folder.newFolder()
        val photo=File(root,"photo.png").apply {writeBytes(Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQ0AAAAASUVORK5CYII="))}
        var sender=ExchangeEngine.save(person("小舟"),Diary(id="device-partner-note",visibility="PARTNER",title="来自 TA 的问候",body="这是一份本地互传测试记录。",photos=listOf(photo.absolutePath)))
        sender=ExchangeEngine.save(sender,Diary(id="device-common-page",title="一起完成的手账",isDiy=true,elements=listOf(Element(id="device-text",content="把生活寄给你",rotation=-5f))))
        sender=sender.copy(profile=sender.profile.copy(theme="雾粉",homePageId="device-common-page"))
        sender=ExchangeEngine.save(sender,Diary(id="device-private",visibility="PRIVATE",title="不能寄出的私密页",body="private-never-send"))
        val prepared=ExchangeEngine.prepare(sender,root)
        val opened=ExchangeEngine.open(prepared.bytes,code)
        assertEquals(2,opened.bundle.diaries.size);assertEquals(1,opened.media.size)
        System.getProperty("tongye.testArtifacts")?.let { directory ->
            val dir=File(directory).apply {mkdirs()};File(dir,"TA的生活.couple").writeBytes(prepared.bytes);File(dir,"test-code.txt").writeText(code)
        }
    }
    @Test fun tenThousandEntriesCanBeExchangedThenContinueWithOneNewDay() {
        val root=folder.newFolder()
        val archive=LongevityFixtures.archive(privatePages=false)
        var a=archive.copy(exchange=archive.exchange!!.copy(code=code))
        val prepared=ExchangeEngine.prepare(a,root)
        assertEquals(10_000,prepared.diaryCount)
        var b=ExchangeEngine.receive(person("B"),ExchangeEngine.open(prepared.bytes,code),{it}).state
        assertEquals(10_000,b.diaries.size)
        assertEquals(a.diaries.associate {it.id to it.body},b.diaries.associate {it.id to it.body})
        a=transfer(b,a,root).state
        a=ExchangeEngine.save(a,Diary(id="next-day",title="the next day",body="still writing",date="2026-10-06",visibility="PARTNER"))
        assertEquals(1,ExchangeEngine.prepare(a,root).diaryCount)
        b=transfer(a,b,root).state
        assertEquals(10_001,b.diaries.size)
        assertEquals("still writing",b.diaries.find {it.id=="next-day"}!!.body)
    }
    @Test fun oldPhotoAcknowledgmentsDoNotBlockFutureLetters() {
        val root=folder.newFolder()
        val a=person("A").let {it.copy(exchange=it.exchange!!.copy(receivedMedia=(0 until 6_000).map {i->"%064x".format(i)}.toSet()))}
        val opened=ExchangeEngine.open(ExchangeEngine.prepare(a,root).bytes,code)
        assertEquals(5_000,opened.bundle.receivedMedia.size)
        assertTrue(opened.bundle.receivedMedia.contains("%064x".format(5_999)))
        assertFalse(opened.bundle.receivedMedia.contains("%064x".format(0)))
        assertFalse(ExchangeEngine.receive(person("B"),opened,{it}).duplicate)
    }
}
