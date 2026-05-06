# Contributing to VeriSphere

Thanks for considering a contribution. VeriSphere is a small Android side-project run by a solo founder, and the codebase is intentionally boring — manual DI, no Hilt, no Koin, no nav library, single-module, Compose-first. Every PR is reviewed against that posture.

## Local setup

### Prerequisites

- **Android Studio Iguana 2024.x or later** (Hedgehog 2026.x once available will match the architecture's stated targets).
- **JDK 17+** (bundled with Android Studio).
- **A Gemini API key.** Get a free personal key at [https://aistudio.google.com/apikey](https://aistudio.google.com/apikey). The free tier is sufficient for development.

### `local.properties` configuration

Create or edit `local.properties` at the project root and add:

```properties
GEMINI_API_KEY=YOUR_GEMINI_API_KEY_HERE
```

`local.properties` is gitignored — it never leaves your machine. The build reads the key via `Gradle buildConfigField` and exposes it as `BuildConfig.GEMINI_API_KEY` at compile time.

The build **fails fast** with a clear `GradleException` if the key is missing from both `local.properties` and the `GEMINI_API_KEY` environment variable. The fallback to the env var is what CI uses.

> [!NOTE]
> The bundled API key is treated as a known-extractable secret per the architecture's NFR9 — mitigation is rate-limiting (30 captures / device / UTC-day) plus a documented rotation runbook in [SECURITY.md](./SECURITY.md), not obfuscation.

## Build commands

| Action | macOS / Linux | Windows |
|---|---|---|
| Debug build | `./gradlew :app:assembleDebug` | `gradlew.bat :app:assembleDebug` |
| Release build | `./gradlew :app:assembleRelease` | `gradlew.bat :app:assembleRelease` |
| Install on connected device | `./gradlew :app:installDebug` | `gradlew.bat :app:installDebug` |
| Unit tests | `./gradlew :app:testDebugUnitTest` | `gradlew.bat :app:testDebugUnitTest` |
| Instrumented tests | `./gradlew :app:connectedAndroidTest` | `gradlew.bat :app:connectedAndroidTest` |

Instrumented tests require a connected device or a running emulator (API 26 or later).

> [!NOTE]
> The release build is **unsigned** during pre-V1 development. Signing config and the release-signing CI workflow land with Story 1.3 / 7.3.

## Code style

Kotlin official style is the baseline. The architecture's Step 5 specifies these naming patterns; please follow them from the first line of code:

- **Packages:** lowercase, dotted (`com.verisphere.app.bubble`).
- **Classes / objects / interfaces:** `PascalCase` (`AppContainer`, `BubbleStateMachine`).
- **Functions:** `camelCase`, verb-first (`buildHttpClient`, not `httpClient`).
- **Constants:** `SCREAMING_SNAKE_CASE` (`MAX_HISTORY_ENTRIES`, `MAX_DAILY_CAPTURES`).
- **Composables:** `PascalCase`, noun (`VeriSphereTheme`, `BubbleOverlay`).
- **Composable parameters:** `camelCase`; `modifier: Modifier = Modifier` always last positional, defaulted.
- **Resources:** `snake_case`, area-prefixed (`vs_verdict_true`, `app_name`).
- **One public type per file.** File named after its public type (`AppContainer.kt` → `class AppContainer`).

Discipline rules carried over from the architecture:

- **No `GlobalScope`.** Every coroutine launches from a scoped owner (`Application`, `Service`, `viewModelScope`).
- **State updates use `MutableStateFlow.update { it.copy(...) }`.** Direct `state.value = ...` is forbidden in production code.
- **No hard-coded user-facing strings in Compose.** Pull them through `R.string.*`.
- **Logging tag format `"VS.<Component>"`** via `Logger.tag(name)`. No `println` anywhere.
- **No hex colour literals in Kotlin.** All colours come from `colors.xml` (`MaterialTheme.colorScheme.*` or `colorResource(R.color.vs_*)`).

Static enforcement of these rules via Detekt + Android Lint lands with **Story 1.3** (CI workflows). Until then, please review your own diffs against the list above.

## Commit conventions

Commit messages follow conventional commits with a story tag:

```
<type>(story-X.Y): <imperative summary>

[optional body — what and why, not how]
```

`<type>` ∈ `{feat, fix, chore, docs, refactor, test}`. Examples from the existing history:

- `feat(story-1.1): scaffold AppContainer, Application class, Logger and reset MainActivity`
- `chore(story-1.1): tighten app build, manifest, network config and ProGuard`
- `docs(story-1.2): add LICENSE, README, CONTRIBUTING, SECURITY, RELEASING, CHANGELOG`

Prefer atomic commits (one logical change per commit) over mega-commits. The story tag makes it easy to map commits back to internal implementation artefacts (kept private during pre-V1) and the published [CHANGELOG](./CHANGELOG.md).

## Pull requests

- Branch off `main`.
- Open a PR against `main` once your changes are ready.
- The PR template at `.github/pull_request_template.md` is auto-populated; fill in the Summary, Story / scope, and Test plan sections, then tick the discipline checklist.
- CI on every PR runs three required jobs: `lint` (`./gradlew :app:lintDebug`), `unit-tests` (`./gradlew :app:testDebugUnitTest`), and `assemble-debug` (`./gradlew :app:assembleDebug`). Branch protection on `main` requires all three to pass before merge. Detekt runs post-merge on `main` (we recommend `./gradlew :app:detekt` locally before opening the PR — it's faster than waiting for the post-merge red).

> [!IMPORTANT]
> **Forks are not supported in V1.** GitHub Actions does not pass repository secrets to workflow runs from forks (security feature). Because the build's `BuildConfig.GEMINI_API_KEY` field is populated at Gradle configuration time and the build fails fast when the key is absent, fork PRs will red-X immediately with `GradleException: GEMINI_API_KEY missing`. To contribute, either (a) request push access to a feature branch on this repo from the maintainer, or (b) build and test entirely locally with your own `local.properties` Gemini key, then send the patch as a `git format-patch` series via email or GitHub issue. V2 may revisit fork support if a contributor base materialises.

## Trademark and scope

- VeriSphere is an **unaffiliated third-party app** calling the public Google Gemini API. Contributors must not represent themselves, the project, or the project's outputs as endorsed by, sponsored by, or otherwise associated with Google, Anthropic, or any other vendor.
- The "Built with Gemini" attribution in the README and the in-app surfaces is the trust-transfer mechanism specified in the PRD; it is not a partnership claim.
- If you fork VeriSphere or reuse parts of it under the MIT licence, please rename the application — VeriSphere as a name is associated with this specific app and its specific stance on verification UX.

## Reporting issues

- **Bugs and feature requests:** open a [GitHub issue](https://github.com/Hernat/VeriSphere/issues).
- **Security vulnerabilities:** see [SECURITY.md](./SECURITY.md). Do not open a public issue for a security finding.

Thanks for reading this far.
