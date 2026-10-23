package io.github.tendo505.tandem.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.tendo505.tandem.core.Field
import io.github.tendo505.tandem.core.Op
import io.github.tendo505.tandem.core.SeqOp
import kotlinx.coroutines.flow.Flow

/** A list this phone follows, and how far into the server's log for it we have read. */
@Entity(tableName = "lists")
data class ListEntity(@PrimaryKey val id: String, val cursor: Long = 0, val joinedAt: Long)

/**
 * The winning value of every (entity, field), i.e. the replica's state.
 * Only winners are kept: a losing op can never matter again, so there is no
 * need to store the full history on the phone.
 */
@Entity(tableName = "fields", primaryKeys = ["listId", "entity", "field"])
data class FieldEntity(val listId: String, val entity: String, val field: String, val value: String, val hlc: String) {
    fun toOp() = Op(listId, entity, Field.valueOf(field), value, hlc)
}

/** Local ops the server hasn't confirmed yet. Re-sending is safe, so they are only removed after a successful push. */
@Entity(tableName = "outbox", primaryKeys = ["listId", "hlc"])
data class OutboxEntity(val listId: String, val hlc: String, val entity: String, val field: String, val value: String) {
    fun toOp() = Op(listId, entity, Field.valueOf(field), value, hlc)
}

data class PendingCount(val listId: String, val n: Int)

private fun Op.toField() = FieldEntity(listId, entity, field.name, value, hlc)
private fun Op.toOutbox() = OutboxEntity(listId, hlc, entity, field.name, value)

@Dao
abstract class TandemDao {
    @Query("SELECT * FROM lists ORDER BY joinedAt DESC")
    abstract fun observeLists(): Flow<List<ListEntity>>

    @Query("SELECT * FROM lists")
    abstract suspend fun lists(): List<ListEntity>

    @Query("SELECT * FROM lists WHERE id = :id")
    abstract suspend fun list(id: String): ListEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertList(list: ListEntity)

    @Query("UPDATE lists SET cursor = :cursor WHERE id = :id")
    abstract suspend fun setCursor(id: String, cursor: Long)

    @Query("SELECT * FROM fields")
    abstract fun observeAllFields(): Flow<List<FieldEntity>>

    @Query("SELECT * FROM fields WHERE listId = :listId")
    abstract fun observeFields(listId: String): Flow<List<FieldEntity>>

    @Query("SELECT * FROM fields WHERE listId = :listId")
    abstract suspend fun fields(listId: String): List<FieldEntity>

    @Query("SELECT hlc FROM fields WHERE listId = :listId AND entity = :entity AND field = :field")
    abstract suspend fun winningHlc(listId: String, entity: String, field: String): String?

    @Upsert
    abstract suspend fun upsertField(field: FieldEntity)

    /** Encoded HLCs sort like the timestamps, so MAX works on the strings. */
    @Query("SELECT MAX(hlc) FROM fields")
    abstract suspend fun maxHlc(): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertOutbox(op: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE listId = :listId ORDER BY hlc LIMIT :limit")
    abstract suspend fun outbox(listId: String, limit: Int): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE listId = :listId AND hlc IN (:hlcs)")
    abstract suspend fun deleteSent(listId: String, hlcs: List<String>)

    @Query("SELECT listId, COUNT(*) AS n FROM outbox GROUP BY listId")
    abstract fun observePending(): Flow<List<PendingCount>>

    @Query("DELETE FROM lists WHERE id = :id")
    protected abstract suspend fun deleteList(id: String)

    @Query("DELETE FROM fields WHERE listId = :id")
    protected abstract suspend fun deleteFields(id: String)

    @Query("DELETE FROM outbox WHERE listId = :id")
    protected abstract suspend fun deleteOutbox(id: String)

    /** Last writer wins per field. Returns how many ops changed something. */
    @Transaction
    open suspend fun apply(ops: List<Op>): Int {
        var changed = 0
        for (op in ops) {
            val current = winningHlc(op.listId, op.entity, op.field.name)
            if (current == null || current < op.hlc) {
                upsertField(op.toField())
                changed++
            }
        }
        return changed
    }

    /** A local edit: apply it and queue it for upload, atomically. */
    @Transaction
    open suspend fun recordLocal(op: Op) {
        apply(listOf(op))
        insertOutbox(op.toOutbox())
    }

    /** Apply one page from the server and move the cursor past it, atomically, so a crash can't skip ops. */
    @Transaction
    open suspend fun receivePage(listId: String, page: List<SeqOp>) {
        if (list(listId) == null) return // left the list while the request was in flight
        apply(page.map { it.op }.filter { it.listId == listId })
        page.lastOrNull()?.let { setCursor(listId, it.seq) }
    }

    @Transaction
    open suspend fun forget(listId: String) {
        deleteOutbox(listId)
        deleteFields(listId)
        deleteList(listId)
    }
}

@Database(entities = [ListEntity::class, FieldEntity::class, OutboxEntity::class], version = 1, exportSchema = true)
abstract class TandemDb : RoomDatabase() {
    abstract fun dao(): TandemDao

    companion object {
        fun create(context: Context): TandemDb =
            Room.databaseBuilder(context, TandemDb::class.java, "tandem.db").build()
    }
}
