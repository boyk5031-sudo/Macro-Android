package com.macroandroid.feature.macros.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.validation.MacroValidator
import com.macroandroid.automation.validation.ValidationEnvironment
import com.macroandroid.core.common.contract.ConsentContract
import com.macroandroid.core.database.SecureValueStore
import com.macroandroid.core.database.repository.MacroRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Snapshot-based [ValidationEnvironment]: the suspendable facts (names, consent, secure ids) are captured once per
 * validation pass by [ValidatorFactory.snapshot], so the pure validator never blocks.
 */
class SnapshotValidationEnvironment(
    private val names: Set<String>,
    private val consent: Boolean,
    private val secureIds: Set<String>,
    private val installed: (String) -> Boolean?,
    private val now: Instant,
) : ValidationEnvironment {
    override fun otherMacroNames(profile: String, excludingId: String): Set<String> = names
    override fun isPackageInstalled(packageName: String): Boolean? = installed(packageName)
    override fun isAccessibilityConsentGranted(): Boolean = consent
    override fun isSecureValueAvailable(id: String): Boolean = id in secureIds
    override fun now(): Instant = now
}

@Singleton
class ValidatorFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val macros: MacroRepository,
    private val consent: ConsentContract,
    private val secureValues: SecureValueStore,
    private val clock: Clock,
) {
    suspend fun snapshot(profile: String, excluding: MacroId, secureIds: Collection<String>): MacroValidator {
        val names = macros.namesInProfile(profile, excluding)
        val granted = consent.consentGranted.first()
        val available = secureIds.filterTo(HashSet()) { secureValues.exists(it) }
        val pm = context.packageManager
        return MacroValidator(
            SnapshotValidationEnvironment(
                names = names,
                consent = granted,
                secureIds = available,
                installed = { pkg -> isInstalled(pm, pkg) },
                now = clock.now(),
            ),
        )
    }

    private fun isInstalled(pm: PackageManager, packageName: String): Boolean? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        // Not visible under <queries> is indistinguishable from not installed; the validator treats it as WARNING.
        null
    }
}
