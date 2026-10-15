package app.tongye.diary

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

object LongevityFixtures {
    fun archive(count: Int=10_000, privatePages: Boolean=true): DiaryState {
        val first=LocalDate.of(2026,10,5).minusDays(count-1L)
        return ExchangeEngine.normalize(DiaryState(initialized=true,profile=Profile(name="长久记录",partner="TA"),diaries=(0 until count).map {i ->
            Diary(id="long-${i+1}",title="Long-day-${(i+1).toString().padStart(5,'0')}",
                body="第 ${i+1} 页的生活。💞\n"+"今天也在记录生活。".repeat(70),date=first.plusDays(i.toLong()).toString(),
                visibility=if(privatePages && i%10==0) "PRIVATE" else if(i%3==0) "SHARED" else "PARTNER",
                createdAt=1_700_000_000_000L+i,updatedAt=1_700_000_000_000L+i)
        }))
    }
}

class WorkspaceDocumentsTest {
    @Test fun tenThousandDaysSurviveDocumentRoundTripAndProduceLegacyFixture() {
        val archive=LongevityFixtures.archive()
        val json=Gson().toJson(archive)
        assertTrue(json.toByteArray().size>2*1024*1024)
        val changes=WorkspaceDocuments.changes(null,archive)
        assertEquals(10_001,changes.writes.size)
        val restored=WorkspaceDocuments.decode(changes.writes)!!
        assertEquals(archive.diaries.associateBy {it.id},restored.diaries.associateBy {it.id})
        assertEquals(archive.profile,restored.profile);assertEquals(archive.exchange,restored.exchange)
        assertEquals(1_000,restored.diaries.count {it.visibility=="PRIVATE"})
        System.getProperty("tongye.testArtifacts")?.let {directory ->
            File(directory,"long-term-legacy.json").writeText(json)
        }
    }
    @Test fun veryLargeHandwrittenPageUsesSmallRowsAndPreservesEmoji() {
        val page=Diary(id="large-diy",title="many memories",isDiy=true,
            elements=(0 until 500).map {Element(content="生活💞".repeat(50))},
            strokes=listOf(Stroke(points=(0 until 50_000).map {Point(it%340+0.123456f,it%460+0.654321f)}),Stroke(points=(0 until 50_000).map {Point(it%300+0.765432f,it%440+0.123456f)})))
        val state=ExchangeEngine.normalize(DiaryState(diaries=listOf(page)))
        val documents=WorkspaceDocuments.changes(null,state).writes
        val parts=documents.single {it.key=="diary:large-diy"}.parts
        assertTrue(parts.joinToString("").toByteArray().size>2*1024*1024)
        assertTrue(parts.size>60)
        assertTrue(parts.all {it.length<=WorkspaceDocuments.CHUNK_CHARS && !it.last().isHighSurrogate() && !it.first().isLowSurrogate()})
        assertEquals(page,WorkspaceDocuments.decode(documents)!!.diaries.single())
    }
    @Test fun editingOrDeletingOneOldPageDoesNotRewriteTheArchive() {
        val before=LongevityFixtures.archive()
        val all=WorkspaceDocuments.changes(null,before).writes.associateBy {it.key}.toMutableMap()
        val old=before.diaries[1]
        val edited=ExchangeEngine.save(before,old.copy(body="edited years later"))
        val delta=WorkspaceDocuments.changes(before,edited)
        assertEquals(setOf("workspace","diary:${old.id}"),delta.writes.map {it.key}.toSet())
        assertTrue(delta.deletes.isEmpty())
        delta.writes.forEach {all[it.key]=it}
        val deleted=ExchangeEngine.delete(edited,old.id)
        val removal=WorkspaceDocuments.changes(edited,deleted)
        assertEquals(listOf("diary:${old.id}"),removal.deletes)
        assertEquals(listOf("workspace"),removal.writes.map {it.key})
        removal.deletes.forEach {all.remove(it)};removal.writes.forEach {all[it.key]=it}
        val restored=WorkspaceDocuments.decode(all.values.toList())!!
        assertEquals(9_999,restored.diaries.size)
        assertFalse(restored.diaries.any {it.id==old.id})
        assertEquals(before.diaries[0],restored.diaries.find {it.id==before.diaries[0].id})
    }
}
