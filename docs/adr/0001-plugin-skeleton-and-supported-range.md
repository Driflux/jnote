# 0001. Plugin skeleton and supported range

- Status: Proposed
- Date: 2026-11-26
- Issue: #1
- PR: #24, #25

## Context
JNote must never slow the IDE down (intent.md section 2) and supports IntelliJ IDEA 2024.2 (build 242) and later (intent.md section 3). Step 1 needs an empty plugin that builds, tests, and loads, so later issues add features on a settled base.

## Decision
- **Build.** Kotlin with Gradle Kotlin DSL and the IntelliJ Platform Gradle Plugin 2.x (2.20.1), wrapper committed (Gradle 9.6.1). The legacy `gradle-intellij-plugin` 1.x is not used.
- **Toolchain.** JDK 21 (`jvmToolchain(21)`). The 2024.2 platform runs on JDK 21, so tests and `runIde` need it. Plugin classes target 21 bytecode, which every IDE since 2024.2 can load.
- **Supported range.** `sinceBuild = 242`, `untilBuild` unset. An unset `untilBuild` means the Marketplace shows the plugin as compatible with future IDEs without a re-release each quarter. The risk is a future API break; Plugin Verifier runs (step 6) and user reports cover it.
- **Build target.** Compile and test against `intellijIdeaCommunity("2024.2")`, the same API surface as `sinceBuild`. Building against a newer IDE could silently use APIs missing in 242.
- **Modules.** Depend only on `com.intellij.modules.platform`. No Java plugin, so the plugin loads in every IntelliJ-based IDE.
- **Dynamic plugin.** The descriptor declares no restart-requiring parts: no `postStartupActivity`, no application listeners, no eager services, no components. The empty plugin has no extensions at all. Each later issue must keep these conditions: use only dynamic extension points, register nothing at startup, and release all resources on unload.
- **Dependencies.** None beyond the platform and test tools. `sqlite-jdbc` arrives with the DB issue.
- **Identity (open).** Plugin id `io.github.driflux.jnote`, name `JNote`, vendor `Driflux`, package root `io.github.driflux.jnote`. **The id cannot change after the first Marketplace publication**, so confirm it before then. The name is also unchecked against Marketplace (intent.md section 3).
- **Version.** `0.1.0-SNAPSHOT` in `gradle.properties`. The MIT license is the repository `LICENSE` file. The build does not yet declare it in plugin metadata.
- **Tests.** A `BasePlatformTestCase` asserts the plugin descriptor is registered with the expected name and vendor, which proves the plugin loads in a headless IDE.

## Alternatives considered
- Build against the locally installed IDEA 2026.2 (`local(...)`): fast with no download, but the API surface is newer than 242 and hides incompatibilities. Kept only as a local fallback. The 2024.2 download worked, so the fallback was not needed.
- Set `untilBuild` (for example `242.*`): safe against breakage but forces a release for every IDE version. Rejected for a free, small plugin.
- Gradle Groovy DSL or plugin 1.x: older and not the supported path for new plugins.
- JDK 17 toolchain: cannot run the 2024.2 test and `runIde` sandboxes.

## Consequences
- Pros
  - One config verifies the real supported API surface, and the skeleton adds zero startup cost.
  - Platform-only modules keep the plugin installable on all IntelliJ-based IDEs.
- Cons and what we accept
  - First build downloads the 2024.2 IDE (several minutes, large cache).
  - Developers need JDK 21 available to Gradle.
  - Unset `untilBuild` can let a broken release stay listed on a newer IDE until we react.
- Follow-up work
  - Confirm plugin id, name, and vendor before first publication.
  - Configure `pluginVerification` IDEs for `verifyPlugin` (step 6).
  - Declare the MIT license in plugin metadata at publishing time.
