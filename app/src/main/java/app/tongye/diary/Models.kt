package app.tongye.diary

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun today(): String = LocalDate.now().toString()
data class Element(
    val id: String = newId(), val type: String = "TEXT", val content: String = "",
    val x: Float = 40f, val y: Float = 60f, val width: Float = 180f, val height: Float = 100f,
    val rotation: Float = 0f, val scale: Float = 1f, val zIndex: Int = 0,
    val color: Long = 0xFF444B43, val fontSize: Float = 20f,
    val createdBy: String = "me", val updatedBy: String = "me", val updatedAt: Long = System.currentTimeMillis()
)
data class Stroke(val points: List<Point> = emptyList(), val color: Long = 0xFF728477, val width: Float = 3f, val id: String = newId(), val updatedAt: Long = System.currentTimeMillis())
data class Point(val x: Float = 0f, val y: Float = 0f)
data class Comment(val id: String = newId(), val author: String = "me", val text: String = "", val date: String = today(), val createdAt: Long = 0)
data class Diary(
    val id: String = newId(), val authorId: String = "me", val visibility: String = "SHARED",
    val title: String = "", val body: String = "", val date: String = today(), val mood: String = "晴朗",
    val photos: List<String> = emptyList(), val elements: List<Element> = emptyList(), val strokes: List<Stroke> = emptyList(),
    val background: String = "奶油白", val isDiy: Boolean = false, val favorite: Boolean = false,
    val reactions: Set<String> = emptySet(), val comments: List<Comment> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis(),
    val updatedBy: String = "me", val deletedElements: Map<String,Long> = emptyMap(), val deletedStrokes: Map<String,Long> = emptyMap(),
    val videos: List<String> = emptyList()
)

fun Element.isMedia(): Boolean = type == "IMAGE" || type == "VIDEO"
fun Diary.mediaPaths(): List<String> = photos + videos.orEmpty() + elements.filter {it.isMedia()}.map {it.content}
fun Diary.hasVideos(): Boolean = videos.orEmpty().isNotEmpty() || elements.any {it.type=="VIDEO"}
fun Diary.isVideoPath(path: String): Boolean = path in videos.orEmpty() || elements.any {it.type=="VIDEO" && it.content==path}
data class Anniversary(val id: String = newId(), val title: String = "", val date: String = today(), val yearly: Boolean = true)
data class DailyNote(val date: String = today(), val text: String = "", val author: String = "me")
data class Profile(val name: String = "我", val partner: String = "TA", val startDate: String = "", val theme: String = "鼠尾草", val lockEnabled: Boolean = false, val homePageId: String = "")
data class DiaryState(val profile: Profile = Profile(), val diaries: List<Diary> = emptyList(), val anniversaries: List<Anniversary> = emptyList(), val notes: List<DailyNote> = emptyList(), val initialized: Boolean = false, val exchange: ExchangeState? = null)
fun daysTogether(date: String, now: LocalDate = LocalDate.now()): Long = runCatching { (ChronoUnit.DAYS.between(LocalDate.parse(date), now) + 1).coerceAtLeast(0) }.getOrDefault(0)
fun daysUntil(a: Anniversary, now: LocalDate = LocalDate.now()): Long {
    val original = LocalDate.parse(a.date)
    var target = if(a.yearly) original.withYear(now.year) else original
    if(a.yearly && target.isBefore(now)) target = original.withYear(now.year + 1)
    return ChronoUnit.DAYS.between(now, target)
}
fun visibleDiaries(items: List<Diary>, userId: String, visibility: String): List<Diary> = items.filter {
    if(visibility == "PRIVATE") it.visibility == "PRIVATE" && it.authorId == userId
    else if(visibility == "FAVORITE") it.favorite && (it.visibility != "PRIVATE" || it.authorId == userId)
    else if(visibility == "MINE") it.authorId == userId && it.visibility != "PRIVATE"
    else if(visibility == "PARTNER") it.authorId != userId && it.visibility != "PRIVATE"
    else it.visibility == "SHARED"
}.sortedByDescending { it.date }
fun sampleState(): DiaryState {
    val now = LocalDate.now()
    return DiaryState(initialized = true, profile=Profile(name="小禾",partner="阿屿",startDate="2025-05-04"), diaries = listOf(
        Diary(id="sample-walk", title="把普通的日子，过成喜欢的样子", body="沿着湖边慢慢走，风很轻，你也在。\n原来幸福就是一些很小、很小的事情。", date=now.minusDays(1).toString(), mood="开心", photos=listOf("sample:lake")),
        Diary(id="sample-page", title="周末，和你一起浪费时间", body="两杯咖啡，一个很长的下午。", date=now.minusDays(3).toString(), isDiy=true, background="方格纸", elements=listOf(
            Element(id="sample-photo", type="IMAGE",content="sample:coffee",x=40f,y=66f,width=200f,height=180f,rotation=-6f),
            Element(id="sample-text",content="和你一起\n慢慢生活",x=110f,y=300f,width=220f,height=100f,fontSize=26f,createdBy="partner",updatedBy="partner"),
            Element(id="sample-sticker",type="STICKER",content="✿",x=256f,y=200f,width=70f,height=70f,fontSize=48f,color=0xFFD49C85)
        )),
        Diary(id="sample-night",authorId="partner",title="今晚的月亮很好看",body="想把这一刻也放进我们的日记。",date=now.minusDays(6).toString(),mood="想你")
    ), anniversaries=listOf(Anniversary(title="在一起的日子",date="2025-05-04")), notes=listOf(DailyNote(text="今天也想和你，一起好好生活。", author="partner")))
}
