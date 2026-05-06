<!--
This is a self-checklist for the PR author, not a merge gate.
The merge gate is the green CI check on PR (lint + unit-tests + assemble-debug).
Tick what applies; cross out (~~text~~) what doesn't apply with a one-line reason.
-->

## Summary

<!-- 1-3 bullets describing what this PR changes and why. -->

## Story / scope

<!-- Story key (e.g. `1-4-securestorage`) or "out-of-band fix"; link to the story file if applicable. -->

## Discipline checklist

- [ ] No `GlobalScope`. Coroutines launch from a scoped owner (`Application`, `Service`, `viewModelScope`).
- [ ] State updates use `MutableStateFlow.update { it.copy(...) }`, never `.value =`.
- [ ] No hard-coded user-facing strings in Compose; all strings come from `R.string.*`.
- [ ] Logger tag format `"VS.<Component>"` via `Logger.tag(name)`. No `println`.
- [ ] One public type per file, file named after the type.
- [ ] Every new composable has at least one `@Preview`, with at least one dark-theme variant.
- [ ] No new manifest permissions added (manifest minimalism — NFR10).
- [ ] If a new dependency is added, the APK size delta is noted below.

## APK size delta

<!-- Required only if you added a dependency. Run `./gradlew :app:assembleDebug` before and after, compare the APK size in `app/build/outputs/apk/debug/`. -->

`Before: <size>` → `After: <size>` (delta: `<+/- delta>`)

## Test plan

- [ ] `./gradlew :app:lintDebug` passes locally
- [ ] `./gradlew :app:testDebugUnitTest` passes locally
- [ ] `./gradlew :app:assembleDebug` passes locally
- [ ] `./gradlew :app:detekt` passes locally
- [ ] Manual / instrumented testing where applicable (describe below)
