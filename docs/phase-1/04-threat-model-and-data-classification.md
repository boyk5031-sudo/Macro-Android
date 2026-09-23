# 04 — Threat Model and Data Classification

## 1. Scope and assumptions

- The app runs as an ordinary, non-privileged, non-device-owner app installed from Google Play on Android 8.0–17.
- The device is not rooted. On a rooted or compromised device none of the app-level controls below hold; that is out of scope and stated in the privacy policy.
- The app has **no network access** (no `INTERNET` permission). There is no server, no telemetry, no crash reporting upload, no remote configuration.
- The user is the only principal who creates macros. There is no sharing service; import/export is file-based and user-initiated.

## 2. Assets

| Asset | Why it matters |
|---|---|
| A1. Macro definitions incl. sensitive parameters | May contain text the user types into other apps |
| A2. Accessibility window content observed during a run | Content of third-party apps' screens |
| A3. Audit and execution logs | Reveal user behaviour/patterns |
| A4. Imported APK metadata and persisted URI grants | Reveal what the user has downloaded; grants give read access to files |
| A5. Installed-app inventory and favourites | Fingerprinting-grade data |
| A6. Keystore master key | Protects A1 sensitive values |
| A7. The a11y capability itself | A powerful capability that must not be borrowed by other apps or by hostile macro files |

## 3. Trust boundaries

```
┌──────────────────────── Device ─────────────────────────┐
│  ┌────────── Macro-Android process (app UID) ─────────┐  │
│  │ UI ── ViewModels ── Repos ── Room/DataStore/Files  │  │
│  │                      │                             │  │
│  │   Engine ── AccessibilityGateway ── A11y Service   │  │
│  └───────────────┬────────────────────────────┬───────┘  │
│   B1 Binder      │   B2 SAF / DocumentsProvider│ B3 A11y  │
│  ┌───────────────▼──────┐   ┌──────────────────▼───────┐ │
│  │ System services      │   │ Other apps (targets,     │ │
│  │ (PM, WorkManager,    │   │ file providers, launcher,│ │
│  │  Keystore, Notif.)   │   │ browsers)                │ │
│  └──────────────────────┘   └──────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
   B4 User-chosen files (import/export) cross the device boundary via the user.
```

## 4. STRIDE analysis

| ID | Threat | Category | Entry | Impact | Mitigation (component) | Phase-0 risk |
|---|---|---|---|---|---|---|
| T1 | Hostile macro JSON crashes parser / exhausts memory / triggers unexpected actions | Tampering, DoS | B4 import | Crash; unwanted actions | Strict `kotlinx.serialization` (unknown keys rejected, closed sealed hierarchy), 1 MiB cap, depth ≤ 16, step budget, URL scheme allow-list, imported macros disabled until reviewed (`MacroImporter`, `MacroValidator`) | R3 |
| T2 | Malformed APK crashes `PackageParser` or fills storage | DoS | B2 | Crash; disk full | Size cap before read, ZIP magic check, parse via PM (system-hardened) in a try/catch, cached copy deleted in `finally`, cache quota (`ApkAnalyzer`) | R2 |
| T3 | A11y service reads sensitive screen content beyond what a macro needs | Information disclosure | B3 | Privacy | Gateway attached only during UI segments; only `rootInActiveWindow` queried; no event buffering; refuses `isPassword` nodes; no `flagRetrieveInteractiveWindows`; `accessibilityDataSensitive` nodes are invisible by platform design on 14+; audit entries store hashes, not text (`AccessibilityGateway`, `MacroAccessibilityService`) | R1 |
| T4 | A malicious app binds to or drives our a11y service / engine | Elevation of privilege | B1 | Other app gets automation | Service exported only with `BIND_ACCESSIBILITY_SERVICE` (system-only); no exported receivers/services/providers; `MainActivity` handles only its own deep links with validation; `intentMatchingFlags="enforceIntentFilter"` on 16+ (manifest, lint fatal on exported components) | R6 |
| T5 | Shortcut or notification `PendingIntent` hijacked to launch something else | Spoofing / EoP | B1 | Unwanted launches | All `PendingIntent`s `FLAG_IMMUTABLE`, explicit component, no user-controlled extras beyond ids; the trampoline re-resolves the package from the DB (`ShortcutTrampolineActivity`, `NotificationFactory`) | R7 |
| T6 | Sensitive macro text read from the DB by a backup, a debugger, or an attacker with file access | Information disclosure | Storage | Credential leak | AES-256-GCM with a non-exportable Keystore key; `allowBackup=false`; values masked in UI; excluded from logs/exports (`SecureValueStore`) | R5 |
| T7 | Diagnostics/export leaks PII | Information disclosure | B4 | Privacy | Redactor: removes sensitive values, hashes node text, strips display names of files, no device IDs; export dialog lists content (`DiagnosticsExporter`) | R10 |
| T8 | Macro used to spam notifications or hammer another app's UI | Abuse / DoS on others | Engine | Policy violation | Rate limits (FR-EXE-15), repeat caps, total timeout, single UI lock, no coordinate gestures in MVP (`RateLimiter`, `MacroValidator`) | R4 |
| T9 | Process death or crash leaves half-applied state | Tampering (integrity) | Runtime | Inconsistent history | Transactional state persistence; reconciliation on start/boot; no automatic resume (`ExecutionRepository`, `ReconcileWorker`) | R8 |
| T10 | Persisted URI grant revoked or file replaced under the same URI | Tampering | B2 | Wrong file analysed | Re-verify SHA-256 on every read of a stored APK; grant check before read; UNAVAILABLE state (`ApkRepository`) | R9 |
| T11 | Supply-chain compromise of a dependency | Tampering | Build | Anything | Version catalog pins; Gradle dependency verification with sha256; Dependabot; no dynamic versions; no plugins from unknown portals (build) | R11 |
| T12 | Social engineering: scammer instructs user to enable the a11y service for a malicious macro they import | EoP via user | B4 + human | Device abuse | Prominent disclosure; imported macros disabled and flagged; a11y steps show target package in review; execution history visible; Android 16+ blocks enabling a11y during calls (platform) | R12 |
| T13 | Background activity launch abuse (launch apps while user is elsewhere) | Annoyance / policy | Engine | Play violation | Launch only when app is visible or within 10 s of a user-tapped notification; otherwise BLOCKED (`ForegroundGate`) | R7 |
| T14 | Repudiation: user cannot tell what the app did | Repudiation | — | Trust | Append-only audit log with timestamps; per-step logs (`AuditRepository`) | — |
| T15 | Timing/side channel via `MediaStore.getVersion` or similar fingerprinting | Info disclosure | — | — | Not used; no network to exfiltrate anyway | — |

## 5. Data classification

| Class | Definition | Handling |
|---|---|---|
| **C0 Public** | Nothing user-specific | — |
| **C1 Internal** | App configuration, non-identifying | Plain DataStore/Room; may be exported |
| **C2 Personal** | Reveals user behaviour or environment | Plain Room; exported only in diagnostics with user action; retention-limited |
| **C3 Sensitive** | Could grant access to accounts or reveal private content | Encrypted at rest; never logged; excluded from export by default; masked in UI |

| Data item | Class | Store | Retention | Export |
|---|---|---|---|---|
| Theme, dynamic color, limits, toggles | C1 | DataStore (Preferences) | Until reset | Diagnostics |
| Consent version + timestamp | C2 | DataStore | Until withdrawn | Diagnostics |
| Macro name, description, tags, profile, steps (non-sensitive params) | C2 | Room `macros`, `macro_steps` (JSON column) | Until deleted | Macro export |
| `EnterText.text`/`SetVariable.value` with `sensitive=true` | **C3** | Room `secure_values` (ciphertext, IV, key alias, AAD hash) | Until step deleted | Redacted unless user opts in per export |
| Installed-app favourites | C2 | Room `app_favorites` | Until removed | Diagnostics (package names only) |
| Imported APK record (URI, name, size, hash, metadata, error) | C2 | Room `imported_apks` | Until deleted | Diagnostics: hash + package + status; **URI and display name omitted** |
| Local APK copies (opt-in) | C2 | `filesDir/apk-copies/` | Until deleted | Never |
| Schedules | C2 | Room `schedules` | Until deleted | Macro export (optional section) + diagnostics |
| Execution records | C2 | Room `executions`, `execution_steps` | 30 d / 5 000 (configurable) | Diagnostics (last 200) |
| Logs | C2 (may reference C3 only via redaction) | Room `log_entries` | 200 000 rows / retention | Diagnostics |
| A11y audit entries (action type, target package, node id, SHA-256[0:12] of node text) | C2 | Room `audit_entries` | Same retention | Diagnostics |
| A11y window content during a run | **C3 transient** | Memory only, within the step's coroutine | Freed at step end; never persisted | Never |
| Keystore master key | C3 | AndroidKeyStore (non-exportable) | Rotated on demand (Settings → Privacy → Rotate key; re-encrypts all values) | Never |

## 6. Redaction rules (applied by `Redactor`, tested in Phase 6/9)

1. Any `SecureValueRef` is rendered as `«sensitive»`.
2. Node text/content-description in logs and audit: `sha256(text).hex.take(12)` prefixed `h:`; view id resource names are kept (they are developer identifiers, not user content).
3. URLs in logs: scheme + host only (`https://example.com/…`).
4. File display names from SAF: kept in the local UI, replaced by `«file»` in diagnostics.
5. Exceptions: class name + our error code only; no `Throwable.message` from platform/other apps in release builds (they can echo user content); full stack traces only in debug logcat.
6. Device: `Build.MODEL`, `Build.VERSION.SDK_INT`, `Build.VERSION.SECURITY_PATCH` only. No `ANDROID_ID`, serial, IMEI, account names.

## 7. Encryption design (ADR-0006 summary)

- Key: `AES/GCM/NoPadding`, 256-bit, alias `macro.secure.v1`, generated with `KeyGenParameterSpec` — purposes ENCRYPT|DECRYPT, `setRandomizedEncryptionRequired(true)`, `setUserAuthenticationRequired(false)` (scheduled runs must decrypt without the user), `setUnlockedDeviceRequired(false)` (workers may run while locked; the value is only *used* after the unlock precondition anyway), `setIsStrongBoxBacked(true)` attempted first, fallback to TEE on `StrongBoxUnavailableException`.
- Per value: random 12-byte IV from the cipher; AAD = UTF-8 of `"$macroId/$stepId/$paramName"`, binding ciphertext to its location (prevents swapping values between steps by editing the DB).
- Storage row: `secure_values(id, macro_id, step_id, param, key_alias, iv BLOB, ciphertext BLOB, created_at)`.
- Failure modes: `KeyPermanentlyInvalidatedException`/`UnrecoverableKeyException` (e.g., factory reset restore) → value becomes `UNAVAILABLE`, step validation ERROR "Re-enter value"; never crashes.
- Rotation: new alias `macro.secure.v2`; re-encrypt all rows in a transaction; delete old alias afterwards.
- Not used: Jetpack Security `EncryptedSharedPreferences`/`EncryptedFile` (deprecated, and their key-wrapping adds nothing here).

## 8. Privacy policy requirements (input to Phase 11)

The policy must state: data is stored only on the device; no network transmission; what the accessibility service reads and when; that sensitive values are encrypted; retention defaults and user controls; that export files are user-controlled; contact for questions; Play Data Safety answers: **no data collected, no data shared**, with the accessibility declaration answering "No" to collecting/sharing personal data via the API (because nothing leaves the device) — subject to the reviewer's interpretation; the Phase 11 doc will include the exact form answers.
