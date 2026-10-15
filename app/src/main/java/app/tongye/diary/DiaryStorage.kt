package app.tongye.diary

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson

// Retained only to read old installations. Never read the entire legacy row into a cursor.
@Entity(tableName="workspace") data class Snapshot(@PrimaryKey val id: Int = 1, val json: String)
@Entity(tableName="workspace_documents", primaryKeys=["documentId","chunkIndex"])
data class DocumentChunk(val documentId: String, val chunkIndex: Int, val json: String)

@Dao abstract class WorkspaceDao {
    @Query("SELECT documentId, chunkIndex, json FROM workspace_documents ORDER BY documentId, chunkIndex")
    abstract suspend fun readChunks(): List<DocumentChunk>
    @Query("SELECT length(json) FROM workspace WHERE id=1")
    abstract suspend fun legacyLength(): Int?
    @Query("SELECT substr(json,:offset,:count) FROM workspace WHERE id=1")
    abstract suspend fun legacyPart(offset: Int, count: Int): String?
    @Query("DELETE FROM workspace_documents WHERE documentId IN (:keys)")
    abstract suspend fun deleteDocuments(keys: List<String>)
    @Insert(onConflict=OnConflictStrategy.REPLACE)
    abstract suspend fun insertChunks(chunks: List<DocumentChunk>)
    @Query("DELETE FROM workspace")
    abstract suspend fun clearLegacy()

    @Transaction
    open suspend fun load(): DiaryState? {
        val rows=readChunks()
        if(rows.isNotEmpty()) {
            val documents=rows.groupBy {it.documentId}.map { (id,parts) ->
                require(parts.map {it.chunkIndex} == parts.indices.toList()) { "本地日记片段不完整" }
                StoredDocument(id,parts.map {it.json})
            }
            return WorkspaceDocuments.decode(documents)
        }
        val length=legacyLength() ?: return null
        val json=buildString {
            var offset=1
            while(offset<=length) {
                append(requireNotNull(legacyPart(offset,WorkspaceDocuments.CHUNK_CHARS)))
                offset+=WorkspaceDocuments.CHUNK_CHARS
            }
        }
        return Gson().fromJson(json,DiaryState::class.java)
    }

    @Transaction
    open suspend fun save(changes: DocumentChanges, migrateLegacy: Boolean=false) {
        val keys=changes.deletes+changes.writes.map {it.key}
        // SQLite also limits the number of placeholders in a single statement.
        keys.chunked(500).forEach { deleteDocuments(it) }
        changes.writes.forEach {document ->
            insertChunks(document.parts.mapIndexed {index,json -> DocumentChunk(document.key,index,json)})
        }
        if(migrateLegacy) clearLegacy()
    }

    @Query("SELECT count(*) FROM workspace_documents")
    abstract suspend fun documentChunkCount(): Int
}

@Database(entities=[Snapshot::class,DocumentChunk::class],version=2,exportSchema=true)
abstract class DiaryDatabase: RoomDatabase() {
    abstract fun workspace(): WorkspaceDao
    companion object {
        val MIGRATION_1_2 = object: Migration(1,2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS workspace_documents (documentId TEXT NOT NULL, chunkIndex INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(documentId,chunkIndex))")
            }
        }
    }
}
