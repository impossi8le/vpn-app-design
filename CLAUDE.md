# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Source of truth

- Runtime behavior is defined by code and passing tests. If documentation disagrees, follow the implementation and update the owning document.
- Keep one owner per cross-cutting topic; link to its document instead of copying the full explanation. `docs/README.md` is the documentation index.
- For Android UI, use `docs/design/2026-10-06-implemented-ui-spec.md`; `docs/design/mockup.html` is a visual companion, not the behavior authority.
- API details belong to `docs/architecture/2026-10-02-api-contract.md` and `docs/api/README.md`.

## Common commands

Run commands from Git Bash on Windows. Android requires the repository toolchain: JDK 17 at `.tools/jdk17`, Android SDK at `.tools/sdk`, and generated OpenVPN engine files under `android/vpnengine/src/main/`.

```bash
# Android: all unit tests (JVM; no emulator)
cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew test --no-daemon

# Android: one module's unit tests
cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --no-daemon

# Android: one JUnit 5 test class
cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --tests 'com.impossi8le.vpnapp.domain.tunnel.BypassRouteTest' --no-daemon

# Android: debug APK
cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :app:assembleDebug --no-daemon

# Android: lint the app
cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :app:lintDebug --no-daemon

# Android: launch emulator and install latest CI APK
scripts/run-emulator.sh

# Android: install latest CI APK on a USB-connected device
scripts/install-apk.sh

# Swift package: build/test one package
swift build --package-path Packages/CoreDomain
swift test --package-path Packages/CoreDomain
swift test --package-path Packages/CoreDomain --filter CoreDomainTests.ProtectionGateTests/testGateYieldsProtectedOnlyOnConfirmedProbe
```

Replace the Swift package path with the package being changed. The CI workflow builds and tests each package in `Packages/*`; `swift test --package-path <path> --filter <TestCase/testName>` runs a focused test. The Android app uses JUnit 5 for local unit tests; `connectedAndroidTest` is a separate JUnit 4 instrumentation-test path and requires an available Android device/emulator. Use `scripts/local-build.md` for toolchain setup and recovery details. Android Studio's bundled JDK 21 is not a substitute for the required JDK 17.

## Architecture

This repository contains two clients sharing the service contract, not a single cross-platform app.

- **Android** is the runnable client: `android/app` is the composition root (DI, navigation, lifecycle); `feature/*` owns screen/ViewModel flows; `core/domain` holds platform-independent models and interfaces; `core/network`, `core/config`, and `core/security` adapt API, profile persistence, and secret storage; `core/tunnel` and `core/protection` map Android VPN/runtime observations into domain state; `vpnservice` owns the foreground `VpnService`; `vpnengine` wraps the OpenVPN 3 native engine through JNI. Follow data flow from `AppGraph`/`MainActivity` through domain interfaces into adapters rather than adding I/O to feature UI.
- **iOS** currently consists of Swift packages under `Packages/`. `CoreDomain` defines shared concepts and ports; `CoreNetwork`, `CoreConfig`, and `CoreSecurity` implement boundaries; `TunnelKitCore` parses/builds tunnel configuration without importing NetworkExtension or TunnelKit; `CoreUI` contains reusable presentation primitives; `FeatureAuth` and `FeatureHome` compose feature logic; `TestSupport*` provide test doubles. Package tests run on Windows where supported; app/NetworkExtension integration, signing, and device tunnel verification require macOS/iPhone as described in `docs/architecture/2026-10-03-development-infrastructure.md`.
- **Security/status invariant:** a system-reported active VPN is not proof that traffic is protected. The Android UI currently presents established tunnel states as «Подключено» without claiming «защищено» because the probe is unavailable. Preserve this distinction when changing status mapping or presentation; see `docs/architecture/2026-10-03-android-architecture.md` and the implemented UI spec.
- **Profile boundary:** server-provided `.ovpn` content is untrusted input. Android parsing/sanitization and atomic profile persistence sit below feature modules; never log profile secrets or raw key blocks. See the architecture document's OpenVPN profile section and `docs/design/2026-10-06-implemented-ui-spec.md` for user-visible behavior.

## Tracker

Bugs and ideas live in GitHub Issues on `impossi8le/vpn-app-design`, not in `docs/`. Rules are owned by `docs/superpowers/specs/2026-10-15-tracker-board-design.md`; read it before filing or triaging.

- **Labels** (three axes, one of each): type `bug`/`feature`/`idea`/`task`; area `android`/`ios`/`backend`/`docs`/`infra`; priority `P0`/`P1`/`P2`. `P0` = app unusable, `P1` = works but hurts or a real protection gap, `P2` = everything else. No status labels — status lives on the board only.
- **Issues are raw material only.** A worked-out topic moves to `docs/superpowers/specs/` and the issue closes with a link. Never copy spec content into an issue.
- **Issues are public; the board is private.** Never put server IPs, hostnames, keys, `.ovpn` contents, or how bypasses actually work into an issue. Test: if it can't go in a commit to this public repo, it can't go in an issue.
- **File proactively** in the issue tracker when you find a defect or idea outside the current task — the issue is the report, don't also narrate it in the reply.
- The GitHub MCP exposes labels, issues and comments but **not** Projects v2. Board columns and project fields are the owner's to change in the browser; you can only set labels and issue state.
