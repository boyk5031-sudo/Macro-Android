package com.macroandroid.automation.serialization

import com.google.common.truth.Truth.assertThat
import com.macroandroid.automation.model.ActionParameters
import com.macroandroid.automation.model.Condition
import com.macroandroid.automation.model.FailureBehavior
import com.macroandroid.automation.model.MacroDocument
import com.macroandroid.automation.model.NodeSelector
import com.macroandroid.automation.model.ScheduleKind
import com.macroandroid.automation.model.TextMatch
import com.macroandroid.automation.model.TextValue
import com.macroandroid.automation.model.VariableValue
import com.macroandroid.automation.testing.MacroFixtures
import com.macroandroid.automation.testing.MacroFixtures.step
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MacroJsonTest {

    private val importer = MacroImporter()

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("schema/v1/$name")) { "missing $name" }
            .readBytes().decodeToString()

    @Test
    fun `reference document imports and matches expected structure`() {
        val doc = (importer.import(resource("open-wifi-settings.json")) as AppResult.Ok).value
        assertThat(doc.schemaVersion).isEqualTo(1)
        assertThat(doc.macros).hasSize(1)
        val macro = doc.macros.single()
        assertThat(macro.name).isEqualTo("Open Wi-Fi settings")
        assertThat(macro.executionPolicy.totalTimeout).isEqualTo(5.minutes)
        assertThat(macro.variables["attempts"]).isEqualTo(VariableValue.Int64(0))
        assertThat(macro.steps.map { it.action::class.simpleName }).containsExactly(
            "LaunchApp", "WaitForNode", "ClickNode", "If", "Parallel", "Stop", "Log",
        ).inOrder()
        val wait = macro.steps[1]
        assertThat(wait.timeout).isEqualTo(8.seconds)
        assertThat(wait.retry?.maxAttempts).isEqualTo(2)
        assertThat((wait.action as ActionParameters.WaitForNode).selector.textMatch).isEqualTo(TextMatch.CONTAINS)
        assertThat(macro.steps[2].onFailure).isEqualTo(FailureBehavior.JumpToLabel("fallback"))
        val cond = (macro.steps[3].action as ActionParameters.If).condition
        assertThat(cond).isInstanceOf(Condition.NodeExists::class.java)
        assertThat(macro.requiresAccessibility).isTrue()
        assertThat(macro.targetPackages).containsExactly("com.android.settings")

        val schedule = doc.schedules.single()
        assertThat(schedule.kind).isEqualTo(
            ScheduleKind.Daily(
                LocalTime(7, 30),
                setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            ),
        )
        assertThat(schedule.lateThreshold).isEqualTo(30.minutes)
    }

    @Test
    fun `export then import round-trips exactly`() {
        val doc = (importer.import(resource("open-wifi-settings.json")) as AppResult.Ok).value
        val text = MacroJson.encode(doc)
        val again = (importer.import(text) as AppResult.Ok).value
        assertThat(again).isEqualTo(doc)
        // Second export is byte-identical (deterministic serializer).
        assertThat(MacroJson.encode(again)).isEqualTo(text)
    }

    @Test
    fun `export omits defaults and uses ISO durations`() {
        val doc = MacroDocument(
            exportedAt = Instant.parse("2026-09-23T10:15:30Z"),
            appVersionCode = 1,
            macros = listOf(MacroFixtures.macro(step(1, ActionParameters.Wait(90.seconds)))),
        )
        val text = MacroJson.encode(doc)
        assertThat(text).contains("\"duration\": \"PT1M30S\"")
        assertThat(text).doesNotContain("\"enabled\"")
        assertThat(text).doesNotContain("\"revision\"")
        assertThat(text).contains("\"schemaVersion\": 1")
    }

    @Test
    fun `unknown keys are rejected`() {
        val text = resource("open-wifi-settings.json").replaceFirst("\"schemaVersion\"", "\"extra\": 1, \"schemaVersion\"")
        val r = importer.import(text)
        assertThat(r.errorOrNull()?.code).isEqualTo(ErrorCode.IMPORT_PARSE_FAILED)
    }

    @Test
    fun `reserved action types are reported as not supported`() {
        val text = resource("open-wifi-settings.json").replaceFirst(
            "{ \"type\": \"launchApp\", \"packageName\": \"com.android.settings\" }",
            "{ \"type\": \"screenshot\" }",
        )
        val r = importer.import(text)
        assertThat(r.errorOrNull()?.code).isEqualTo(ErrorCode.ACTION_NOT_SUPPORTED)
    }

    @Test
    fun `newer schema versions are rejected`() {
        val text = resource("open-wifi-settings.json").replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        assertThat(importer.import(text).errorOrNull()?.code).isEqualTo(ErrorCode.SCHEMA_TOO_NEW)
    }

    @Test
    fun `hostile input is bounded`() {
        assertThat(importer.import(ByteArray(2 * 1024 * 1024)).errorOrNull()?.code).isEqualTo(ErrorCode.FILE_TOO_LARGE)
        val deep = "[".repeat(40) + "]".repeat(40)
        assertThat(importer.import(deep).errorOrNull()?.code).isEqualTo(ErrorCode.IMPORT_DEPTH_EXCEEDED)
        assertThat(importer.import("{not json").errorOrNull()?.code).isEqualTo(ErrorCode.IMPORT_PARSE_FAILED)
        assertThat(importer.import(byteArrayOf(0xFF.toByte(), 0xFE.toByte())).errorOrNull()?.code)
            .isEqualTo(ErrorCode.IMPORT_PARSE_FAILED)
    }

    @Test
    fun `depth scanner ignores brackets inside strings`() {
        assertThat(JsonDepthScanner.maxDepth("""{"a":"[[[[","b":[1,[2]]}""")).isEqualTo(3)
        assertThat(JsonDepthScanner.maxDepth("""{"a":"\"[["}""")).isEqualTo(1)
    }

    @Test
    fun `selector describe never includes typed text of enterText`() {
        val s = NodeSelector(viewId = "a:id/b", text = "Login", textMatch = TextMatch.CONTAINS, index = 2)
        assertThat(s.describe()).isEqualTo("id=a:id/b text~\"Login\" #2")
        assertThat(TextValue.Template("Hi {{ name }} and {{other}}").referencedVariables()).containsExactly("name", "other")
    }
}
