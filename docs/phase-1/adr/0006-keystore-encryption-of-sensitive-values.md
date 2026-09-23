# ADR-0006 — Keystore-backed AES-GCM for sensitive macro values; plaintext Room for everything else

- Status: Accepted (confirmed by owner)

## Context
`EnterText` steps may type text that is secret (tokens, codes, addresses). Full-database encryption (SQLCipher) adds a native dependency, key-management complexity, and slows every query for little benefit on a non-rooted device where app-private storage is already sandboxed. Jetpack Security is deprecated.

## Decision
- A `SecureValueCipher` in `core:security` using `AndroidKeyStore`, `AES/GCM/NoPadding`, 256-bit, alias `macro.secure.v1`, StrongBox when available, `setUserAuthenticationRequired(false)`, `setUnlockedDeviceRequired(false)` (workers must decrypt while the device may be locked; the *use* of the value is gated by the unlock precondition of the UI step).
- Per-value random IV, AAD = `"$macroId/$stepId/$param"`; ciphertext stored in `secure_values` with the alias and IV; the macro JSON holds only a `SecureValueRef`.
- Values flagged `sensitive` are: masked in the editor, never logged (`Redactor`), excluded from export/diagnostics unless explicitly included per export, never interpolated into templates.
- Key invalidation (`KeyPermanentlyInvalidatedException`, `UnrecoverableKeyException`) → value `UNAVAILABLE`; the macro fails validation with "Re-enter value"; the app never crashes on decrypt.
- Rotation: Settings → Privacy → "Rotate encryption key" re-encrypts all rows under a new alias in one transaction.
- `allowBackup=false` (see ADR-0009) because Keystore keys do not survive device transfer.

## Consequences
- Secrets at rest are protected by hardware-backed keys; everything else stays queryable and debuggable.
- Users must re-enter sensitive values after a device migration; export can include them only on explicit request.
