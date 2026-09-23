# ADR-0008 — Accessibility service scope, disclosure, and hard limits

- Status: Accepted

## Context
Google Play permits the AccessibilityService API for deterministic, user-defined automation with prominent disclosure and consent, prohibits autonomous initiate/plan/execute behaviour, and lists automation tools as **not** eligible for `isAccessibilityTool` (Play Console Help 10964491, read 2026-09-23).

## Decision
1. Configuration as in `06-permission-matrix.md §3`: window-state/content events only, `canRetrieveWindowContent`, no gestures/screenshots/key filtering in v1, `isAccessibilityTool` absent.
2. The service is inert when no execution holds the UI lock: `onAccessibilityEvent` returns immediately unless a gateway is attached; no window content is retained.
3. Hard limits enforced in `AccessibilityGateway` regardless of macro content: refuse `isPassword` nodes; only act on nodes `isVisibleToUser`; never interact with `TYPE_SYSTEM` windows or the keyguard; rate limit 5 actions/s; require `isInteractive && !isKeyguardLocked`.
4. Every action is audited (type, target package, view id, text hash).
5. Prominent disclosure screen (separate, in-flow, affirmative consent, versioned) before the system toggle; withdrawal disables affected macros.
6. Play Console: accessibility declaration as a non-tool; video showing disclosure → consent → toggle → a macro clicking a visible node.

## Consequences
- Some automations users may want (blind taps, reading protected content, acting on the lock screen) are impossible by design and documented in Help.
