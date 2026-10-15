package app.tongye.diary

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ModelsTest {
    @Test fun optionalTogetherDateDoesNotCrash() {assertEquals(0L,daysTogether(""));assertEquals(0L,daysTogether("未设置"))}
    @Test fun togetherIncludesTheFirstDay() {assertEquals(1L,daysTogether("2026-10-05",LocalDate.of(2026,10,5)));assertEquals(520L,daysTogether("2025-05-04",LocalDate.of(2026,10,5)))}
    @Test fun anniversaryRollsOverAndHandlesLeapDay() {
        assertEquals(364L,daysUntil(Anniversary(date="2020-10-04"),LocalDate.of(2026,10,5)))
        assertEquals(0L,daysUntil(Anniversary(date="2024-02-29"),LocalDate.of(2025,2,28)))
    }
    @Test fun partnerPrivateEntriesCannotLeakThroughFilters() {
        val own=Diary(id="own",visibility="PRIVATE",favorite=true)
        val partner=Diary(id="partner",visibility="PRIVATE",authorId="partner",favorite=true)
        val shared=Diary(id="shared",visibility="SHARED")
        val diaries=listOf(own,partner,shared)
        assertEquals(listOf("own"),visibleDiaries(diaries,"me","PRIVATE").map {it.id})
        assertEquals(listOf("own"),visibleDiaries(diaries,"me","FAVORITE").map {it.id})
        assertEquals(listOf("shared"),visibleDiaries(diaries,"me","SHARED").map {it.id})
    }
}
