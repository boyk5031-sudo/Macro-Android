package com.macroandroid.feature.macros.data

import android.content.Context
import android.net.Uri
import com.macroandroid.automation.model.ActionParameters
import com.macroandroid.automation.model.Macro
import com.macroandroid.automation.model.MacroDocument
import com.macroandroid.automation.model.MacroId
import com.macroandroid.automation.model.MacroLimits
import com.macroandroid.automation.model.MacroStep
import com.macroandroid.automation.model.SecureValueRef
import com.macroandroid.automation.model.StepId
import com.macroandroid.automation.model.TextValue
import com.macroandroid.automation.model.VariableValue
import com.macroandroid.automation.serialization.MacroImporter
import com.macroandroid.automation.serialization.MacroJson
import com.macroandroid.automation.validation.MacroValidator
import com.macroandroid.core.common.coroutines.AppDispatchers
import com.macroandroid.core.common.error.AppResult
import com.macroandroid.core.common.error.ErrorCode
import com.macroandroid.core.common.error.appRunCatching
import com.macroandroid.core.database.SecureValueStore
import com.macroandroid.core.database.repository.AuditKind
import com.macroandroid.core.database.repository.ExecutionRepository
import com.macroandroid.core.database.repository.MacroRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/** How a name collision on import should be resolved (FR-MAC-8). */
enum class ImportConflictChoice { RENAME, REPLACE, SKIP }

data class ImportCandidate(
    val macro: Macro,
    val conflictsWith: MacroId?,
    val issues: List<String>,
    val hasRedactedSecrets: Boolean,
)

data class ImportPreview(val schemaVersion: Int, val candidates: List<ImportCandidate>)

/** Export/import of macro JSON through SAF-provided URIs (FR-MAC-7/8). */
@Singleton
class MacroTransfer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val macros: MacroRepository,
    private val secureValues: SecureValueStore,
    private val executions: ExecutionRepository,
    private val validators: ValidatorFactory,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
) {
    private val importer = MacroImporter()

    // ---- export ----------------------------------------------------------------------------

    /** Sensitive values are exported redacted unless [includeSecrets] is explicitly set (with the UI warning). */
    suspend fun exportDocument(ids: List<MacroId>, includeSecrets: Boolean, appVersionCode: Int): AppResult<String> =
        withContext(dispatchers.io) {
            appRunCatching(ErrorCode.UNEXPECTED) {
                val list = ids.mapNotNull { macros.get(it) }
                val prepared = if (includeSecrets) list.map { inlineSecrets(it) } else list.map { redactSecrets(it) }
                val doc = MacroDocument(exportedAt = clock.now(), appVersionCode = appVersionCode, macros = prepared)
                MacroJson.encode(doc)
            }
        }

    suspend fun writeTo(uri: Uri, content: String, exportedIds: List<MacroId>): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) }
                ?: return@withContext AppResult.err(ErrorCode.FILE_UNREADABLE, "openOutputStream=null")
            exportedIds.forEach { executions.audit(AuditKind.MACRO_EXPORTED, it.value) }
            AppResult.ok(Unit)
        } catch (e: IOException) {
            AppResult.err(ErrorCode.FILE_UNREADABLE, e.message, e)
        } catch (e: SecurityException) {
            AppResult.err(ErrorCode.URI_PERMISSION_REVOKED, e.message, e)
        }
    }

    // ---- import ----------------------------------------------------------------------------

    suspend fun preview(uri: Uri): AppResult<ImportPreview> = withContext(dispatchers.io) {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                readLimited(stream, MacroLimits.IMPORT_BYTES_MAX)
                    ?: return@withContext AppResult.err(ErrorCode.FILE_TOO_LARGE)
            } ?: return@withContext AppResult.err(ErrorCode.FILE_UNREADABLE, "openInputStream=null")
        } catch (e: IOException) {
            return@withContext AppResult.err(ErrorCode.FILE_UNREADABLE, e.message, e)
        } catch (e: SecurityException) {
            return@withContext AppResult.err(ErrorCode.URI_PERMISSION_REVOKED, e.message, e)
        }
        val doc = when (val r = importer.import(bytes)) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> return@withContext AppResult.err(r.error)
        }
        val candidates = doc.macros.map { imported ->
            // Imported macros are created disabled and get fresh ids unless they replace an existing one (FR-MAC-8).
            val existing = macros.namesInProfile(imported.profile, MacroId("")).contains(imported.name.trim().lowercase())
            val conflictId = if (existing) findByName(imported.profile, imported.name) else null
            val validator: MacroValidator = validators.snapshot(imported.profile, conflictId ?: imported.id, emptyList())
            val issues = validator.validate(imported).issues.map { "${it.code}${it.field?.let { f -> " ($f)" } ?: ""}" }
            ImportCandidate(
                macro = imported.copy(enabled = false),
                conflictsWith = conflictId,
                issues = issues,
                hasRedactedSecrets = hasRedactedSecrets(imported),
            )
        }
        AppResult.ok(ImportPreview(doc.schemaVersion, candidates))
    }

    suspend fun commit(preview: ImportPreview, choices: Map<MacroId, ImportConflictChoice>): AppResult<Int> =
        withContext(dispatchers.io) {
            var imported = 0
            for (c in preview.candidates) {
                val choice = choices[c.macro.id] ?: if (c.conflictsWith != null) ImportConflictChoice.SKIP else ImportConflictChoice.RENAME
                val target: Macro = when {
                    c.conflictsWith == null -> c.macro.copy(id = MacroId.random(), steps = freshStepIds(c.macro.steps))
                    choice == ImportConflictChoice.SKIP -> continue
                    choice == ImportConflictChoice.REPLACE -> c.macro.copy(id = c.conflictsWith, steps = freshStepIds(c.macro.steps))
                    else -> c.macro.copy(
                        id = MacroId.random(),
                        name = uniqueName(c.macro.profile, c.macro.name),
                        steps = freshStepIds(c.macro.steps),
                    )
                }
                // Secure refs from another device are unusable: strip them so the editor flags the step (SECURE_VALUE_UNAVAILABLE).
                val stripped = stripForeignSecrets(target)
                when (val r = macros.save(stripped)) {
                    is AppResult.Ok -> {
                        imported++
                        executions.audit(AuditKind.MACRO_IMPORTED, r.value.id.value, "schema=${preview.schemaVersion}")
                    }
                    is AppResult.Err -> return@withContext AppResult.err(r.error)
                }
            }
            AppResult.ok(imported)
        }

    // ---- helpers ---------------------------------------------------------------------------

    /** Reads at most [limit] bytes; returns null when the stream is longer (API 26-safe, no `readNBytes`). */
    private fun readLimited(stream: java.io.InputStream, limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            if (out.size() + n > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private suspend fun findByName(profile: String, name: String): MacroId? {
        val wanted = name.trim().lowercase()
        return macros.observeAllMacros().first().firstOrNull { it.profile == profile && it.name.trim().lowercase() == wanted }?.id
    }

    private suspend fun uniqueName(profile: String, base: String): String {
        val taken = macros.namesInProfile(profile, MacroId(""))
        var i = 2
        var candidate = "$base (imported)"
        while (candidate.lowercase() in taken) candidate = "$base (imported $i)".also { i++ }
        return candidate.take(MacroLimits.NAME_MAX)
    }

    private fun freshStepIds(steps: List<MacroStep>): List<MacroStep> = steps.map { s ->
        val a = when (val act = s.action) {
            is ActionParameters.If -> act.copy(then = freshStepIds(act.then), `else` = freshStepIds(act.`else`))
            is ActionParameters.Repeat -> act.copy(body = freshStepIds(act.body))
            is ActionParameters.Parallel -> act.copy(children = freshStepIds(act.children))
            else -> act
        }
        s.copy(id = StepId.random(), action = a)
    }

    private fun hasRedactedSecrets(m: Macro): Boolean =
        m.variables.values.any { it is VariableValue.Secure && it.ref.redacted } ||
            m.allSteps().any { s -> textValues(s.action).any { it is TextValue.Secure && it.ref.redacted } }

    private fun stripForeignSecrets(m: Macro): Macro = redactRefs(m) { it.copy(redacted = true) }

    private fun redactSecrets(m: Macro): Macro = redactRefs(m) { SecureValueRef(it.id, redacted = true) }

    private fun redactRefs(m: Macro, f: (SecureValueRef) -> SecureValueRef): Macro = m.copy(
        variables = m.variables.mapValues { (_, v) -> if (v is VariableValue.Secure) VariableValue.Secure(f(v.ref)) else v },
        steps = mapSteps(m.steps) { s ->
            val a = s.action
            if (a is ActionParameters.EnterText && a.text is TextValue.Secure) {
                s.copy(action = a.copy(text = TextValue.Secure(f(a.text.ref))))
            } else {
                s
            }
        },
    )

    private suspend fun inlineSecrets(m: Macro): Macro = m.copy(
        steps = mapSteps(m.steps) { s ->
            when (val a = s.action) {
                is ActionParameters.EnterText -> if (a.text is TextValue.Secure) {
                    val plain = secureValues.get(a.text.ref.id).getOrNull()
                    if (plain != null) s.copy(action = a.copy(text = TextValue.Literal(plain), sensitive = true)) else s
                } else {
                    s
                }
                else -> s
            }
        },
    )

    private fun textValues(a: ActionParameters): List<TextValue> = when (a) {
        is ActionParameters.EnterText -> listOf(a.text)
        is ActionParameters.OpenUrl -> listOf(a.url)
        is ActionParameters.SendNotification -> listOf(a.title, a.text)
        is ActionParameters.Log -> listOf(a.message)
        else -> emptyList()
    }

    private fun mapSteps(steps: List<MacroStep>, f: (MacroStep) -> MacroStep): List<MacroStep> = steps.map { s0 ->
        val s = f(s0)
        when (val a = s.action) {
            is ActionParameters.If -> s.copy(action = a.copy(then = mapSteps(a.then, f), `else` = mapSteps(a.`else`, f)))
            is ActionParameters.Repeat -> s.copy(action = a.copy(body = mapSteps(a.body, f)))
            is ActionParameters.Parallel -> s.copy(action = a.copy(children = mapSteps(a.children, f)))
            else -> s
        }
    }

    private companion object {
        const val BUFFER = 16 * 1024
    }
}
