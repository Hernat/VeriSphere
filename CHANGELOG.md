# Changelog

All notable changes to VeriSphere will be documented in this file.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/), with the **Pre-V1 Release Policy** documented in [RELEASING.md](./RELEASING.md): the project starts at `0.1.0`; patches and small evolutions stay on `0.x.y`; **`1.0.0` is reserved for the moment the founder substitution success criterion is validated for ≥ 1 week of personal usage.**

## [Unreleased]

### Added

- Initial repository documentation skeleton: `LICENSE` (MIT), `README.md`, `CONTRIBUTING.md`, `SECURITY.md`, `RELEASING.md`, `CHANGELOG.md` (Story 1.2).
- `.gitattributes` for consistent line-ending normalisation across platforms (Story 1.2).
- `/local/` entry in `.gitignore` per the AC list (Story 1.2).
- `AUTHORS.md` documenting maintainership and AI pair-programming tooling.
- GitHub Actions CI workflows (Story 1.3): `pr.yml` (lint + unit tests + assemble-debug), `main.yml` (same + Detekt), `release.yml` (signed assembleRelease + GitHub Release upload on tag, currently unsigned until Story 7.3 wires the keystore).
- Detekt 1.23.7 wired into Gradle with the three architecture-mandated rules: `GlobalCoroutineUsage`, `ForbiddenMethodCall` (println / print), and a placeholder for the missing-`@Serializable`-on-repository-types rule scoped to Story 1.10.
- `.github/pull_request_template.md` with the architecture-pattern discipline checklist.

## [0.1.0] - unreleased

### Added

- Project bootstrap from Android Studio Empty Activity Compose starter (Story 1.1).
- Manifest hardening: exactly 5 permissions, `allowBackup="false"`, network security config with HTTPS-only enforcement.
- `BuildConfig.GEMINI_API_KEY` injection from `local.properties` or the `GEMINI_API_KEY` environment variable; build fails fast when the key is absent.
- `BuildConfig.SKIP_RATE_LIMIT` debug-only bypass flag (release hard-codes `false`).
- Material 3 theme with `dynamicColor=false`, auto-follow-system, full UX Step 8 palette in `colors.xml` + `values-night/colors.xml`, typography overrides at 18 / 16 / 14 / 12 sp, `VSSpacing` scale at 4 / 8 / 12 / 16 / 24 / 32 dp.
- `AppContainer.kt`, `VeriSphereApplication.kt`, `util/Logger.kt` skeleton with `tag(name)` and `redact(value)` helpers.
- `version.properties` source-of-truth at `MAJOR=0`, `MINOR=1`, `PATCH=0`.
- ProGuard / R8 release rules: strip `Log.d/v/i`, keep `@Serializable` types.
- `.gitignore` covering Android build artefacts, IntelliJ per-developer config, local secrets, BMad workflow internals.

<!-- Reference link defs for [Unreleased] and [0.1.0] are restored when Story 7.3 publishes the first signed `v0.1.0` tag. -->
