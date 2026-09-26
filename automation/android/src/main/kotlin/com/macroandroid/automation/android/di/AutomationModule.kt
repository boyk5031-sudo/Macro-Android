package com.macroandroid.automation.android.di

import com.macroandroid.automation.android.accessibility.AccessibilityServiceListener
import com.macroandroid.automation.android.accessibility.AndroidAccessibilityGateway
import com.macroandroid.automation.android.consent.AccessibilityConsent
import com.macroandroid.automation.android.gate.AndroidPreconditionGate
import com.macroandroid.automation.android.gate.ConsentProvider
import com.macroandroid.automation.android.launcher.AndroidAppLauncher
import com.macroandroid.automation.android.notification.ExecutionNotifications
import com.macroandroid.automation.android.scheduling.WorkManagerScheduler
import com.macroandroid.automation.android.service.MacroRunnerContractAdapter
import com.macroandroid.automation.engine.EnginePorts
import com.macroandroid.automation.port.AccessibilityGateway
import com.macroandroid.automation.port.AppLauncher
import com.macroandroid.automation.port.EngineConfig
import com.macroandroid.automation.port.ExecutionStore
import com.macroandroid.automation.port.MacroSource
import com.macroandroid.automation.port.NotificationPort
import com.macroandroid.automation.port.PreconditionGate
import com.macroandroid.automation.port.SecureValueResolver
import com.macroandroid.core.common.contract.AuditContract
import com.macroandroid.core.common.contract.ConsentContract
import com.macroandroid.core.common.contract.MacroRunnerContract
import com.macroandroid.core.common.contract.SchedulerContract
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.database.SecureValueStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AutomationBindingsModule {
    @Binds abstract fun accessibilityGateway(impl: AndroidAccessibilityGateway): AccessibilityGateway
    @Binds abstract fun preconditionGate(impl: AndroidPreconditionGate): PreconditionGate
    @Binds abstract fun appLauncher(impl: AndroidAppLauncher): AppLauncher
    @Binds abstract fun notificationPort(impl: ExecutionNotifications): NotificationPort
    @Binds abstract fun consentProvider(impl: AccessibilityConsent): ConsentProvider
    @Binds abstract fun consentContract(impl: AccessibilityConsent): ConsentContract
    @Binds abstract fun accessibilityServiceListener(impl: AccessibilityConsent): AccessibilityServiceListener
    @Binds abstract fun auditContract(impl: AccessibilityConsent): AuditContract
    @Binds abstract fun macroRunnerContract(impl: MacroRunnerContractAdapter): MacroRunnerContract
    @Binds abstract fun schedulerContract(impl: WorkManagerScheduler): SchedulerContract
}

@Module
@InstallIn(SingletonComponent::class)
object AutomationModule {
    @Provides
    @Singleton
    fun engineConfig(): EngineConfig = EngineConfig()

    @Provides
    @Singleton
    fun secureValueResolver(store: SecureValueStore): SecureValueResolver = object : SecureValueResolver {
        override suspend fun resolve(id: String): AppResult<String> = store.get(id)
    }

    @Provides
    @Singleton
    fun enginePorts(
        macroSource: MacroSource,
        store: ExecutionStore,
        secureValues: SecureValueResolver,
        gate: PreconditionGate,
        accessibility: AccessibilityGateway,
        launcher: AppLauncher,
        notifications: NotificationPort,
    ): EnginePorts = EnginePorts(
        macroSource = macroSource,
        store = store,
        secureValues = secureValues,
        gate = gate,
        accessibility = accessibility,
        launcher = launcher,
        notifications = notifications,
    )
}
