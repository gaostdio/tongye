package app.tongye.diary

import com.google.gson.Gson

data class StoredDocument(val key: String, val parts: List<String>)
data class DocumentChanges(val writes: List<StoredDocument>, val deletes: List<String>)

// Small SQLite rows avoid CursorWindow limits even for a very large drawing or archive.
object WorkspaceDocuments {
    const val CHUNK_CHARS = 32 * 1024
    private const val metadataKey = "workspace"
    private const val diaryPrefix = "diary:"
    private val gson = Gson()

    fun changes(previous: DiaryState?, next: DiaryState): DocumentChanges {
        require(next.diaries.map { it.id }.distinct().size == next.diaries.size) { "日记编号重复，未保存这次修改" }
        val old = previous?.diaries?.associateBy { it.id }.orEmpty()
        val writes = mutableListOf<StoredDocument>()
        val metadata = next.copy(diaries = emptyList())
        if (previous == null || previous.copy(diaries = emptyList()) != metadata) {
            writes += document(metadataKey, gson.toJson(metadata))
        }
        next.diaries.forEach { page ->
            if (old[page.id] != page) writes += document(diaryPrefix + page.id, gson.toJson(page))
        }
        val ids = next.diaries.map { it.id }.toSet()
        return DocumentChanges(writes, old.keys.filterNot { it in ids }.map { diaryPrefix + it })
    }

    fun decode(documents: List<StoredDocument>): DiaryState? {
        if (documents.isEmpty()) return null
        require(documents.map { it.key }.distinct().size == documents.size) { "本地日记记录重复" }
        val metadata = requireNotNull(documents.find { it.key == metadataKey }) { "本地资料不完整" }
        val state = gson.fromJson(metadata.parts.joinToString(""), DiaryState::class.java)
        val pages = documents.filter { it.key.startsWith(diaryPrefix) }.map { document ->
            val page = gson.fromJson(document.parts.joinToString(""), Diary::class.java)
            require(document.key == diaryPrefix + page.id) { "本地日记编号不一致" }
            page
        }
        return state.copy(diaries = pages.sortedWith(compareByDescending<Diary> { it.date }.thenByDescending { it.updatedAt }))
    }

    private fun document(key: String, json: String): StoredDocument {
        val parts=mutableListOf<String>();var start=0
        while(start<json.length) {
            var end=minOf(start+CHUNK_CHARS,json.length)
            if(end<json.length && json[end-1].isHighSurrogate() && json[end].isLowSurrogate()) end--
            parts+=json.substring(start,end);start=end
        }
        return StoredDocument(key,parts)
    }
}
