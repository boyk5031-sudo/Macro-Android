package com.macroandroid.core.testing

import com.macroandroid.core.common.coroutines.AppDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Installs a [TestDispatcher] as `Dispatchers.Main` and exposes matching [AppDispatchers] for injection. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    val dispatchers: AppDispatchers = AppDispatchers(
        io = testDispatcher,
        default = testDispatcher,
        main = testDispatcher,
        mainImmediate = testDispatcher,
    )

    override fun starting(description: Description) = Dispatchers.setMain(testDispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}
