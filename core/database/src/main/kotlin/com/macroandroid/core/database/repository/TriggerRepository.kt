package com.macroandroid.core.database.repository

import com.macroandroid.automation.trigger.TargetPointId
import com.macroandroid.automation.trigger.TriggerConfiguration
import com.macroandroid.automation.trigger.TriggerId
import com.macroandroid.automation.trigger.TriggerJson
import com.macroandroid.automation.trigger.TriggerValidator
import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.error.appRunCatching
import com.macroandroid.core.common.logging.Logger
import com.macroandroid.core.database.dao.TriggerDao
import com.macroandroid.core.database.entity.TriggerConfigEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

data class StoredTrigger(val config: TriggerConfiguration, val createdAt: Instant, val updatedAt: Instant) {
    val id: TriggerId get() = config.id
}

/** Persistence for Trigger Area configurations; survives restarts, validates on write, tolerates corrupt rows. */
interface TriggerRepository {
    fun observeAll(): Flow<List<StoredTrigger>>
    fun observe(id: TriggerId): Flow<StoredTrigger?>
    suspend fun get(id: TriggerId): StoredTrigger?
    suspend fun all(): List<StoredTrigger>

    /** Insert or update; the configuration must pass [TriggerValidator]. */
    suspend fun save(config: TriggerConfiguration): AppResult<StoredTrigger>
    suspend fun setEnabled(id: TriggerId, enabled: Boolean): AppResult<Unit>
    suspend fun rename(id: TriggerId, name: String): AppResult<Unit>

    /** Copies a configuration (new ids for it and its points) as "<name> (copy)", disabled. */
    suspend fun duplicate(id: TriggerId): AppResult<StoredTrigger>
    suspend fun delete(id: TriggerId): AppResult<Unit>

    /** Imports already-validated configurations with fresh ids; returns how many were stored. */
    suspend fun importAll(configs: List<TriggerConfiguration>): AppResult<Int>
}

@Singleton
class RoomTriggerRepository @Inject constructor(
    private val dao: TriggerDao,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
    private val logger: Logger,
) : TriggerRepository {

    override fun observeAll(): Flow<List<StoredTrigger>> =
        dao.observeAll().map { rows -> rows.mapNotNull(::decode) }.distinctUntilChanged()

    override fun observe(id: TriggerId): Flow<StoredTrigger?> =
        dao.observe(id.value).map { it?.let(::decode) }.distinctUntilChanged()

    override suspend fun get(id: TriggerId): StoredTrigger? = withContext(dispatchers.io) { dao.get(id.value)?.let(::decode) }

    override suspend fun all(): List<StoredTrigger> = withContext(dispatchers.io) { dao.all().mapNotNull(::decode) }

    override suspend fun save(config: TriggerConfiguration): AppResult<StoredTrigger> = withContext(dispatchers.io) {
        TriggerValidator.validate(config).firstOrNull()?.let { return@withContext AppResult.err(it) }
        appRunCatching(ErrorCode.DB_ERROR) {
            val now = clock.now().toEpochMilliseconds()
            val existing = dao.get(config.id.value)
            val entity = TriggerConfigEntity(
                id = config.id.value,
                name = config.name.trim(),
                enabled = config.enabled,
                packageName = config.packageName,
                configJson = TriggerJson.encodeConfiguration(config.copy(name = config.name.trim())),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            dao.upsert(entity)
            StoredTrigger(config, Instant.fromEpochMilliseconds(entity.createdAt), Instant.fromEpochMilliseconds(now))
        }
    }

    override suspend fun setEnabled(id: TriggerId, enabled: Boolean): AppResult<Unit> =
        update(id) { it.copy(enabled = enabled) }

    override suspend fun rename(id: TriggerId, name: String): AppResult<Unit> = update(id) { it.copy(name = name) }

    override suspend fun duplicate(id: TriggerId): AppResult<StoredTrigger> = withContext(dispatchers.io) {
        val source = get(id) ?: return@withContext AppResult.err(ErrorCode.TRIGGER_NOT_FOUND)
        val copy = source.config.copy(
            id = TriggerId.random(),
            name = "${source.config.name} (copy)".take(MAX_NAME),
            enabled = false,
            targetPoints = source.config.targetPoints.map { it.copy(id = TargetPointId.random()) },
        )
        save(copy)
    }

    override suspend fun delete(id: TriggerId): AppResult<Unit> = withContext(dispatchers.io) {
        appRunCatching(ErrorCode.DB_ERROR) { dao.delete(id.value) }
    }

    override suspend fun importAll(configs: List<TriggerConfiguration>): AppResult<Int> = withContext(dispatchers.io) {
        configs.firstNotNullOfOrNull { c -> TriggerValidator.validate(c).firstOrNull() }
            ?.let { return@withContext AppResult.err(it) }
        appRunCatching(ErrorCode.DB_ERROR) {
            val now = clock.now().toEpochMilliseconds()
            val entities = configs.map { c ->
                val fresh = c.copy(
                    id = TriggerId.random(),
                    enabled = false,
                    targetPoints = c.targetPoints.map { it.copy(id = TargetPointId.random()) },
                )
                TriggerConfigEntity(
                    id = fresh.id.value,
                    name = fresh.name,
                    enabled = false,
                    packageName = fresh.packageName,
                    configJson = TriggerJson.encodeConfiguration(fresh),
                    createdAt = now,
                    updatedAt = now,
                )
            }
            dao.upsertAll(entities)
            entities.size
        }
    }

    private suspend fun update(id: TriggerId, transform: (TriggerConfiguration) -> TriggerConfiguration): AppResult<Unit> =
        withContext(dispatchers.io) {
            val current = get(id) ?: return@withContext AppResult.err(ErrorCode.TRIGGER_NOT_FOUND)
            when (val saved = save(transform(current.config))) {
                is AppResult.Ok -> AppResult.ok(Unit)
                is AppResult.Err -> saved
            }
        }

    private fun decode(row: TriggerConfigEntity): StoredTrigger? =
        when (val decoded = TriggerJson.decodeConfiguration(row.configJson)) {
            is AppResult.Ok -> StoredTrigger(
                // Columns are authoritative for the fields the list toggles without re-encoding JSON.
                config = decoded.value.copy(name = row.name, enabled = row.enabled, packageName = row.packageName),
                createdAt = Instant.fromEpochMilliseconds(row.createdAt),
                updatedAt = Instant.fromEpochMilliseconds(row.updatedAt),
            )
            is AppResult.Err -> {
                logger.w(TAG, "skipping corrupt trigger row ${row.id}: ${decoded.error.code}")
                null
            }
        }

    private companion object {
        const val TAG = "TriggerRepo"
        const val MAX_NAME = 60
    }
}
