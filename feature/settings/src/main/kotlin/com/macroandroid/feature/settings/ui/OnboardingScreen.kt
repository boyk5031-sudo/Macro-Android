package com.macroandroid.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.macroandroid.feature.settings.R
import com.macroandroid.feature.settings.presentation.OnboardingViewModel
import kotlinx.coroutines.launch

object OnboardingTestTags {
    const val NEXT = "onboarding.next"
    const val SKIP = "onboarding.skip"
}

private data class Page(val icon: ImageVector, val title: Int, val body: Int)

private val PAGES = listOf(
    Page(Icons.Outlined.Android, R.string.onb_1_title, R.string.onb_1_body),
    Page(Icons.Outlined.AutoAwesome, R.string.onb_2_title, R.string.onb_2_body),
    Page(Icons.Outlined.Settings, R.string.onb_3_title, R.string.onb_3_body),
)

/** Three pages: what the app does, what it cannot do, and where permissions live. Nothing is requested here. */
@Composable
fun OnboardingRoute(onDone: () -> Unit, modifier: Modifier = Modifier, viewModel: OnboardingViewModel = hiltViewModel()) {
    val pager = rememberPagerState { PAGES.size }
    val scope = rememberCoroutineScope()
    val finish = { viewModel.complete(onDone) }
    Scaffold(modifier = modifier) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            HorizontalPager(pager, Modifier.weight(1f)) { i ->
                val page = PAGES[i]
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(page.icon, contentDescription = null, Modifier.size(ICON_SIZE), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(page.title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                    Text(
                        stringResource(page.body),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { finish() }, Modifier.testTag(OnboardingTestTags.SKIP)) { Text(stringResource(R.string.onb_skip)) }
                Button(
                    onClick = {
                        if (pager.currentPage == PAGES.lastIndex) {
                            finish()
                        } else {
                            scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                        }
                    },
                    Modifier.testTag(OnboardingTestTags.NEXT),
                ) {
                    Text(stringResource(if (pager.currentPage == PAGES.lastIndex) R.string.onb_start else R.string.onb_next))
                }
            }
        }
    }
}

private val ICON_SIZE = 96.dp
