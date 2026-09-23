package com.macroandroid.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.macroandroid.core.database.entity.ExecutionEntity
import com.macroandroid.core.database.entity.ExecutionStepEntity
import com.macroandroid.core.database.entity.LogEntryEntity
import com.macroandroid.core.database.entity.MacroEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MacroDatabaseTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MacroDatabase::class.java)

    private lateinit var db: MacroDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MacroDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    @Throws(IOException::class)
    fun tearDown() = db.close()

    @Test
    fun schemaV1CanBeCreatedFromExportedJson() {
        // Fails if the exported schema JSON is missing or stale (exportSchema=true + schemas dir as test assets).
        migrationHelper.createDatabase("migration-test", MacroDatabase.VERSION).close()
    }

    @Test
    fun macroUpsertWithTagsAndCascade() = runTest {
        val dao = db.macroDao()
        dao.upsertWithTags(macro("m1"), listOf("a", "b"))
        assertThat(dao.tagsOf("m1")).containsExactly("a", "b")
        dao.upsertWithTags(macro("m1"), listOf("b"))
        assertThat(dao.tagsOf("m1")).containsExactly("b")
        assertThat(dao.namesInProfile("General", "other")).containsExactly("macro m1")
        dao.delete("m1")
        assertThat(dao.tagsOf("m1")).isEmpty()
        assertThat(dao.observeAll().first()).isEmpty()
    }

    @Test
    fun executionsCascadeAndOrphanInterruption() = runTest {
        val dao = db.executionDao()
        dao.insert(execution("e1", state = "RUNNING", owner = "dead"))
        dao.insert(execution("e2", state = "RUNNING", owner = "live"))
        dao.insert(execution("e3", state = "COMPLETED", owner = "dead"))
        dao.upsertStep(ExecutionStepEntity("e1", "s1", 0, 1, "wait", "RUNNING", 1L, null, null, null, null))
        dao.insertLogs(listOf(LogEntryEntity(executionId = "e1", at = 1L, level = "INFO", stepIndex = 0, attempt = 1, message = "x", errorCode = null)))

        assertThat(dao.interruptOrphans("live", 100L, "CANCELLED_PROCESS_DEATH")).isEqualTo(1)
        assertThat(dao.cancelOrphanSteps(100L)).isEqualTo(1)
        assertThat(dao.get("e1")!!.state).isEqualTo("INTERRUPTED")
        assertThat(dao.get("e2")!!.state).isEqualTo("RUNNING")
        assertThat(dao.get("e3")!!.state).isEqualTo("COMPLETED")
        assertThat(dao.active().map { it.id }).containsExactly("e2")

        dao.applyRetention(endedBefore = 50L, keepRuns = 10, maxLogs = 1_000)
        assertThat(dao.get("e3")).isNull() // ended_at 10 < 50
        assertThat(dao.get("e1")).isNotNull()
        dao.deleteAll()
        assertThat(dao.logs("e1")).isEmpty() // cascade
    }

    private fun macro(id: String) = MacroEntity(
        id = id, revision = 1, name = "Macro $id", normalisedName = "macro $id", description = "", profile = "General",
        enabled = true, stepsJson = "[]", policyJson = "{}", variablesJson = "{}", schemaVersion = 1, stepCount = 0,
        requiresAccessibility = false, createdAt = 1L, updatedAt = 1L,
    )

    private fun execution(id: String, state: String, owner: String) = ExecutionEntity(
        id = id, macroId = "m", macroRevision = 1, macroName = "m", originJson = "{\"type\":\"manual\"}", runRequestId = id,
        state = state, ownerToken = owner, queuedAt = 1L, startedAt = 2L, endedAt = if (state == "COMPLETED") 10L else null,
        pausedAt = null, blockedAt = null, blockedCode = null, blockedDetail = null, currentStepIndex = 0, currentStepId = null,
        currentAttempt = 0, completedSteps = 0, totalStaticSteps = 1, retryTotal = 0, errorCode = null, errorCategory = null,
        errorDetail = null,
    )
}
