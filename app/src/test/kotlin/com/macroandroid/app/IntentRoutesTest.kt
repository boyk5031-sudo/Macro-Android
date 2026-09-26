package com.macroandroid.app

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.macroandroid.app.ui.IntentRoutes
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntentRoutesTest {
    @Test
    fun `execution deep link parses`() {
        assertThat(IntentRoutes.parse(Uri.parse("macroandroid://execution/abc"))).isEqualTo(IntentRoutes.Route.Execution("abc"))
    }

    @Test
    fun `run macro round trips`() {
        val uri = IntentRoutes.runMacroUri("m-1")
        assertThat(IntentRoutes.parse(uri)).isEqualTo(IntentRoutes.Route.RunMacro("m-1"))
    }

    @Test
    fun `static shortcuts parse`() {
        assertThat(IntentRoutes.parse(Uri.parse("macroandroid://macro/new"))).isEqualTo(IntentRoutes.Route.NewMacro)
        assertThat(IntentRoutes.parse(Uri.parse("macroandroid://runs"))).isEqualTo(IntentRoutes.Route.Runs)
    }

    @Test
    fun `foreign or malformed uris are ignored`() {
        assertThat(IntentRoutes.parse(Uri.parse("https://example.com/macro/x/run"))).isNull()
        assertThat(IntentRoutes.parse(Uri.parse("macroandroid://macro/x/delete"))).isNull()
        assertThat(IntentRoutes.parse(Uri.parse("macroandroid://execution/"))).isNull()
        assertThat(IntentRoutes.parse(Uri.parse("macroandroid://unknown"))).isNull()
    }
}
