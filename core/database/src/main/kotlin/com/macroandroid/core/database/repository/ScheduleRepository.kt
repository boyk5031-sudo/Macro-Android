package com.macroandroid.core.database.repository

import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.ScheduleId
import com.macroandroid.automation.model.ScheduleSpec
import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.error.appRunCatching
import com.macroandroid.core.database.dao.ScheduleDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

interface ScheduleRepository {
    fun observeAll(): Flow<List<StoredSchedule>>
    fun observeForMacro(macroId: MacroId): Flow<List<StoredSchedule>>
    fun observe(id: ScheduleId): Flow<StoredSchedule?>
    fun observeEnabledCount(): Flow<Int>
    suspend fun get(id: ScheduleId): StoredSchedule?
    suspend fun all(): List<StoredSchedule>
    suspend fun enabled(): List<StoredSchedule>
    suspend fun save(spec: ScheduleSpec): AppResult<StoredSchedule>
    suspend fun setEnabled(id: ScheduleId, enabled: Boolean): AppResult<Unit>
    suspend fun setNextRun(id: ScheduleId, nextRunAt: Instant?, plannedAt: Instant?)
    suspend fun recordFired(id: ScheduleId, firedAt: Instant, result: String)
    suspend fun delete(id: ScheduleId): AppResult<Unit>
}

@Singleton
class RoomScheduleRepository @Inject constructor(
    private val dao: ScheduleDao,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
) : ScheduleRepository {
    override fun observeAll(): Flow<List<StoredSchedule>> = dao.observeAll().map { it.map(Mappers::toSchedule) }.distinctUntilChanged()

    override fun observeForMacro(macroId: MacroId): Flow<List<StoredSchedule>> =
        dao.observeForMacro(macroId.value).map { it.map(Mappers::toSchedule) }.distinctUntilChanged()

    override fun observe(id: ScheduleId): Flow<StoredSchedule?> =
        dao.observe(id.value).map { it?.let(Mappers::toSchedule) }.distinctUntilChanged()

    override fun observeEnabledCount(): Flow<Int> = dao.observeEnabledCount().distinctUntilChanged()

    override suspend fun get(id: ScheduleId): StoredSchedule? = withContext(dispatchers.io) { dao.get(id.value)?.let(Mappers::toSchedule) }

    override suspend fun all(): List<StoredSchedule> = withContext(dispatchers.io) { dao.all().map(Mappers::toSchedule) }

    override suspend fun enabled(): List<StoredSchedule> = withContext(dispatchers.io) { dao.enabled().map(Mappers::toSchedule) }

    override suspend fun save(spec: ScheduleSpec): AppResult<StoredSchedule> = withContext(dispatchers.io) {
        appRunCatching(ErrorCode.DB_ERROR) {
            val entity = Mappers.toEntity(spec, clock.now(), dao.get(spec.id.value))
            dao.upsert(entity)
            Mappers.toSchedule(entity)
        }
    }

    override suspend fun setEnabled(id: ScheduleId, enabled: Boolean): AppResult<Unit> = withContext(dispatchers.io) {
        appRunCatching(ErrorCode.DB_ERROR) {
            val existing = dao.get(id.value) ?: error("schedule ${id.value} missing")
            // Keep spec_json's `enabled` in sync with the column so exports are consistent.
            val spec = Mappers.toSchedule(existing).spec.copy(enabled = enabled)
            dao.upsert(Mappers.toEntity(spec, clock.now(), existing))
        }
    }

    override suspend fun setNextRun(id: ScheduleId, nextRunAt: Instant?, plannedAt: Instant?) = withContext(dispatchers.io) {
        dao.setNextRun(id.value, nextRunAt?.toEpochMilliseconds(), plannedAt?.toEpochMilliseconds())
    }

    override suspend fun recordFired(id: ScheduleId, firedAt: Instant, result: String) = withContext(dispatchers.io) {
        dao.recordFired(id.value, firedAt.toEpochMilliseconds(), result)
    }

    override suspend fun delete(id: ScheduleId): AppResult<Unit> = withContext(dispatchers.io) {
        appRunCatching(ErrorCode.DB_ERROR) { dao.delete(id.value) }
    }
}
