package com.macroandroid.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.macroandroid.feature.settings.ui.OnboardingTestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke test: the app starts, Hilt graph builds, onboarding is shown on first launch (NFR-REL-1). */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun inject() = hilt.inject()

    @Test
    fun firstLaunchShowsOnboarding() {
        compose.onNodeWithTag(OnboardingTestTags.NEXT).assertIsDisplayed()
    }
}
