package com.macroandroid.core.database.repository

import androidx.room.withTransaction
import com.macroandroid.automation.engine.ExecutionState
import com.macroandroid.automation.model.Macro
import com.macroandroid.automation.model.MacroDocument
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.port.MacroSource
import com.macroandroid.automation.serialization.MacroJson
import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.error.appRunCatching
import com.macroandroid.core.database.MacroDatabase
import com.macroandroid.core.database.dao.MacroDao
import com.macroandroid.core.database.entity.MacroDraftEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

interface MacroRepository : MacroSource {
    fun observeSummaries(): Flow<List<MacroSummary>>
    fun observeProfiles(): Flow<List<String>>
    fun observeCount(): Flow<Int>
    fun observe(id: MacroId): Flow<Macro?>
    suspend fun get(id: MacroId): Macro?
    suspend fun enabledMacros(): List<Macro>
    suspend fun namesInProfile(profile: String, excluding: MacroId): Set<String>

    /** Inserts or updates. Bumps `revision` on update. Steps must already be validated by the caller. */
    suspend fun save(macro: Macro): AppResult<Macro>
    suspend fun delete(id: MacroId): AppResult<Unit>
    suspend fun setEnabled(id: MacroId, enabled: Boolean): AppResult<Unit>
    suspend fun recordLastRun(id: MacroId, at: Instant, state: ExecutionState)

    suspend fun saveDraft(id: MacroId, document: MacroDocument)
    suspend fun draft(id: MacroId): MacroDocument?
    suspend fun deleteDraft(id: MacroId)
    fun observeDraftIds(): Flow<Set<MacroId>>
}

@Singleton
class RoomMacroRepository @Inject constructor(
    private val db: MacroDatabase,
    private val dao: MacroDao,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
) : MacroRepository {

    override fun observeSummaries(): Flow<List<MacroSummary>> =
        combine(dao.observeAll(), dao.observeAllMacroTags()) { macros, refs ->
            val tagsByMacro = refs.groupBy({ it.macroId }, { it.tag })
            macros.map { Mappers.toSummary(it, tagsByMacro[it.id].orEmpty().sorted()) }
        }.distinctUntilChanged()

    override fun observeProfiles(): Flow<List<String>> = dao.observeProfiles()

    override fun observeCount(): Flow<Int> = dao.observeCount()

    override fun observe(id: MacroId): Flow<Macro?> =
        combine(dao.observe(id.value), dao.observeAllMacroTags()) { entity, refs ->
            entity?.let { Mappers.toMacro(it, refs.filter { r -> r.macroId == it.id }.map { r -> r.tag }.sorted()) }
        }.distinctUntilChanged()

    override suspend fun get(id: MacroId): Macro? = withContext(dispatchers.io) {
        dao.get(id.value)?.let { Mappers.toMacro(it, dao.tagsOf(it.id)) }
    }

    override suspend fun getMacro(id: MacroId): Macro? = get(id)

    override suspend fun enabledMacros(): List<Macro> = withContext(dispatchers.io) {
        dao.enabled().map { Mappers.toMacro(it, dao.tagsOf(it.id)) }
    }

    override suspend fun namesInProfile(profile: String, excluding: MacroId): Set<String> =
        withContext(dispatchers.io) { dao.namesInProfile(profile, excluding.value).toSet() }

    override suspend fun save(macro: Macro): AppResult<Macro> = withContext(dispatchers.io) {
        appRunCatching(ErrorCode.DB_ERROR) {
            db.withTransaction {
                val existing = dao.get(macro.id.value)
                val now = clock.now()
                val next = if (existing == null) {
                    macro.copy(revision = 1, createdAt = now, updatedAt = now)
                } else {
                    macro.copy(revision = existing.revision + 1, updatedAt = now)
                }
                dao.upsertWithTags(Mappers.toEntity(next, now, existing), next.tags.distinct())
                next
            }
        }
    }

    override suspend fun delete(id: MacroId): AppResult<Unit> = withContext(dispatchers.io) {
        appRunCatching(ErrorCode.DB_ERROR) {
            db.withTransaction {
                dao.delete(id.value)
                dao.deleteDraft(id.value)
                dao.pruneUnusedTags()
            }
        }
    }

    override suspend fun setEnabled(id: MacroId, enabled: Boolean): AppResult<Unit> = withContext(dispatchers.io) {
        appRunCatching(ErrorCode.DB_ERROR) { dao.setEnabled(id.value, enabled, clock.now().toEpochMilliseconds()) }
    }

    override suspend fun recordLastRun(id: MacroId, at: Instant, state: ExecutionState) = withContext(dispatchers.io) {
        dao.recordLastRun(id.value, at.toEpochMilliseconds(), state.name)
    }

    override suspend fun saveDraft(id: MacroId, document: MacroDocument) = withContext(dispatchers.io) {
        dao.upsertDraft(MacroDraftEntity(id.value, MacroJson.encode(document), clock.now().toEpochMilliseconds()))
    }

    override suspend fun draft(id: MacroId): MacroDocument? = withContext(dispatchers.io) {
        dao.draft(id.value)?.let { row ->
            runCatching { MacroJson.instance.decodeFromString(MacroDocument.serializer(), row.documentJson) }.getOrNull()
        }
    }

    override suspend fun deleteDraft(id: MacroId) = withContext(dispatchers.io) { dao.deleteDraft(id.value) }

    override fun observeDraftIds(): Flow<Set<MacroId>> =
        dao.observeDrafts().map { drafts -> drafts.map { MacroId(it.macroId) }.toSet() }.distinctUntilChanged()
}
