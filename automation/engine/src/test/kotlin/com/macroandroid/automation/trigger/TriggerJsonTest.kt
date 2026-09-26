package com.macroandroid.automation.trigger

import com.google.common.truth.Truth.assertThat
import com.macroandroid.automation.testing.TriggerFixtures
import com.macroandroid.core.common.error.ErrorCode
import org.junit.Test

class TriggerJsonTest {

    @Test
    fun `configuration survives a compact round trip`() {
        val config = TriggerFixtures.config(mode = ExecutionMode.MULTI_TOUCH, packageName = "com.example.game")
        val json = TriggerJson.encodeConfiguration(config)
        assertThat(TriggerJson.decodeConfiguration(json).getOrNull()).isEqualTo(config)
    }

    @Test
    fun `document round trip keeps schema version and order`() {
        val a = TriggerFixtures.config(id = "a")
        val b = TriggerFixtures.config(id = "b")
        val json = TriggerJson.encodeDocument(listOf(a, b))
        assertThat(json).contains("\"schemaVersion\": 1")
        assertThat(json).contains(TriggerJson.KIND)
        val decoded = TriggerJson.decodeDocument(json).getOrNull()!!
        assertThat(decoded.triggers).containsExactly(a, b).inOrder()
    }

    @Test
    fun `corrupt column and foreign documents are rejected gracefully`() {
        assertThat(TriggerJson.decodeConfiguration("{not json").errorOrNull()?.code).isEqualTo(ErrorCode.DB_CORRUPT)
        assertThat(TriggerJson.decodeDocument("{}").errorOrNull()?.code).isEqualTo(ErrorCode.IMPORT_PARSE_FAILED)
        val macroDoc = """{"schemaVersion":1,"kind":"macroandroid.macros","triggers":[]}"""
        assertThat(TriggerJson.decodeDocument(macroDoc).errorOrNull()?.code).isEqualTo(ErrorCode.IMPORT_PARSE_FAILED)
        val future = """{"schemaVersion":99,"kind":"macroandroid.triggers","triggers":[]}"""
        assertThat(TriggerJson.decodeDocument(future).errorOrNull()?.code).isEqualTo(ErrorCode.SCHEMA_TOO_NEW)
    }

    @Test
    fun `imported triggers are validated`() {
        val json = TriggerJson.encodeDocument(listOf(TriggerFixtures.config(targets = emptyList())))
        assertThat(TriggerJson.decodeDocument(json).errorOrNull()?.code).isEqualTo(ErrorCode.TRIGGER_NO_TARGETS)
    }
}
