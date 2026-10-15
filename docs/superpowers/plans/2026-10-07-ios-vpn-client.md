# iOS VPN Client Implementation Plan

> **For agentic workers:** Use the subagent-driven-development workflow for independent tasks and review each integration. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a complete SwiftUI iOS VPN client matching the current Android client, ready for credentialed CI signing, TestFlight distribution, and verification on the user's iPhone.

**Architecture:** Add an iOS app target and a separate `NEPacketTunnelProvider` extension, composing the existing pure-Swift packages at the app boundary. Keep domain/API/UI logic testable in Swift packages; isolate NetworkExtension and TunnelKit wiring to platform targets. Build independent feature slices against the established protocols, then integrate through the app composition root and macOS CI.

**Tech Stack:** Swift 5.9 package manifests, SwiftUI, XCTest, Xcode, NetworkExtension, App Groups, existing CoreDomain/CoreNetwork/CoreConfig/CoreSecurity/FeatureAuth/FeatureHome/TunnelKitCore packages, TunnelKit engine (subject to macOS link verification), GitHub Actions `macos-15`.

## Global Constraints

- Minimum supported iOS version is iOS 15.0, matching all existing Swift packages.
- The current Android behavior spec `docs/design/2026-10-06-implemented-ui-spec.md` is the UI behavior authority; do not use `docs/design/mockup.html` to override it.
- Connection UI reports actual tunnel connection state only; never say “защищено” or use a green protected state based only on NetworkExtension status.
- The tunnel runs only in `VpnTunnelExtension`; app UI initiates and observes it through `TunnelControlling`.
- Preserve route catalog/default routes; user-defined bypasses are additive and must not replace the shared route list.
- Do not expose purchase/renewal links or payment flows in the app; subscription sales remain in Telegram.
- Never log session tokens, login-operation secrets, or raw `.ovpn` contents/private-key blocks.
- Apple Team ID, profiles, certificates, and App Store Connect API credentials are supplied by the user later; until then, complete unsigned builds/tests and keep CI signing gated on secrets.

---

## File Map

### Existing reusable packages

- `Packages/CoreDomain/Sources/CoreDomain/` — models and protocols for auth, config, protection, status, and tunnel control; extend only when an iOS capability needs a domain boundary.
- `Packages/CoreNetwork/Sources/CoreNetwork/` — HTTPS API adapters; add bypass API adapters here, with unit tests in `Packages/CoreNetwork/Tests/CoreNetworkTests/`.
- `Packages/CoreConfig/Sources/CoreConfig/` — atomic profile persistence; reuse for shared App Group storage only if its boundary supports the extension safely, otherwise add a small adapter in the iOS project.
- `Packages/CoreSecurity/Sources/CoreSecurity/` — Keychain session and auth-operation stores.
- `Packages/CoreUI/Sources/CoreUI/DesignTokens.swift` — existing shared design tokens; add reusable SwiftUI primitives here if appropriate.
- `Packages/FeatureAuth/Sources/FeatureAuth/AuthFlowController.swift` — existing login state machine; preserve its nonce-bound polling behavior.
- `Packages/FeatureHome/Sources/FeatureHome/HomeViewModel.swift` and `HomeState.swift` — existing home behavior and button-state logic; preserve tested behavior while presenting Android-compatible connection semantics.
- `Packages/TunnelKitCore/Sources/TunnelKitCore/` — pure Swift `.ovpn` parsing/configuration; must remain free of NetworkExtension and TunnelKit imports.
- Existing package tests remain under each package's `Tests/<Package>Tests/` directory.

### New app and extension targets

- `Apps/VpnApp/VpnApp.xcodeproj/project.pbxproj` — Xcode project containing iOS application and packet-tunnel extension targets, package references, App Group/NetworkExtension entitlements, and deployment target.
- `Apps/VpnApp/App/` — SwiftUI app entry point, dependency composition root, navigation, scene lifecycle, and app-level services/adapters.
- `Apps/VpnApp/PacketTunnel/` — `NEPacketTunnelProvider` entry point and extension-side configuration loading/TunnelKit session lifecycle.
- `Apps/VpnApp/Shared/` — App Group constants and the narrow cross-process profile/configuration contract shared by both targets.
- `Apps/VpnApp/Resources/` — app metadata, localized user-facing strings, and assets.
- `Apps/VpnApp/Tests/` — app/adaptor tests runnable on macOS where system APIs allow; no test depends on a live VPN server.
- `Apps/VpnApp/UITests/` — launch and golden-path UI tests using injected fake services/tunnel state.
- New `Packages/FeatureBypass/` — pure view state/controller and SwiftUI screen for service catalog, current bypasses, search, and custom routes; tests under `Packages/FeatureBypass/Tests/FeatureBypassTests/`.
- `.github/workflows/ios.yml` — extend existing package CI to build/test the Xcode project unsigned before Apple secrets exist, and use the existing secret-backed archive/upload job only when signing credentials are configured.
- `.github/exportOptions.plist` — replace example bundle IDs/profile labels when Apple identifiers and profiles are known; include both app and extension profile mappings.

### Dependency direction and cross-task contracts

- App composition constructs concrete API, Keychain, profile-store, and `TunnelControlling` adapters and injects protocols into feature view models.
- `FeatureBypass` consumes domain protocols for route catalog, user bypass records, and writes; it must not import SwiftUI-dependent app code or NetworkExtension.
- The app-side tunnel adapter implements `TunnelControlling`; the extension receives a validated profile/configuration through the App Group and owns the actual TunnelKit connection.
- App and extension share only App Group identifiers and serialized configuration types, not view models or `NETunnelProviderManager` instances.
- Every subagent must limit edits to its assigned files and report changed paths, tests run, and unresolved macOS/iPhone-only checks.

---

### Task 1: Establish the iOS app and extension targets

**Files:**
- Create: `Apps/VpnApp/VpnApp.xcodeproj/project.pbxproj`
- Create: `Apps/VpnApp/App/VpnApp.swift`
- Create: `Apps/VpnApp/App/AppComposition.swift`
- Create: `Apps/VpnApp/Shared/AppGroupConstants.swift`
- Create: `Apps/VpnApp/Resources/Info.plist`
- Create: `Apps/VpnApp/App/VpnApp.entitlements`
- Create: `Apps/VpnApp/PacketTunnel/PacketTunnel.entitlements`
- Modify: `.github/workflows/ios.yml`

**Interfaces:**
- Consumes: existing Swift package products under `Packages/`.
- Produces: `VpnApp` iOS app target and `VpnPacketTunnel` Network Extension target, app-group identifier constant shared by both targets, app composition root to be filled as adapters land.

- [ ] Add the Xcode project with app + packet-tunnel-extension targets, iOS 15 deployment target, existing local package references, and separate entitlements. Keep Team ID/signing unset so unsigned CI builds work without credentials.
- [ ] Add a minimal SwiftUI app entry point that launches an injected root view and does not start a tunnel during app launch.
- [ ] Add the shared App Group identifier in one shared source file and include it in both entitlements; use a stable reverse-DNS namespace for bundle IDs and record the chosen IDs in project settings and export configuration.
- [ ] Extend the `packages` CI job to build and test all existing packages and add a macOS `xcodebuild` build/test step for the unsigned app project. Do not decode or require signing secrets for this path.
- [ ] Verify locally available Swift packages with `swift test --package-path Packages/CoreDomain`, `swift test --package-path Packages/FeatureAuth`, `swift test --package-path Packages/FeatureHome`, and `swift test --package-path Packages/TunnelKitCore`; verify Xcode project build on macOS CI.
- [ ] Record app/extension bundle IDs, App Group, capability identifiers, and which signing values remain user-provided in the existing iOS infrastructure documentation.

### Task 2: Implement bypass domain, API, and feature screen

**Files:**
- Create: `Packages/CoreDomain/Sources/CoreDomain/Bypass.swift`
- Modify: `Packages/CoreDomain/Tests/CoreDomainTests/` (add bypass route/model tests)
- Modify: `Packages/CoreNetwork/Sources/CoreNetwork/` (bypass API clients)
- Modify: `Packages/CoreNetwork/Tests/CoreNetworkTests/` (API contract tests)
- Create: `Packages/FeatureBypass/Package.swift`
- Create: `Packages/FeatureBypass/Sources/FeatureBypass/BypassViewModel.swift`
- Create: `Packages/FeatureBypass/Sources/FeatureBypass/BypassScreen.swift`
- Create: `Packages/FeatureBypass/Tests/FeatureBypassTests/BypassViewModelTests.swift`
- Modify: `Apps/VpnApp/App/AppComposition.swift`

**Interfaces:**
- Consumes: `ConfigService`, the existing `HTTPTransport`/`APIClient` conventions, `CoreUI` tokens, and the Android behavior in `docs/design/2026-10-06-implemented-ui-spec.md` §4b.
- Produces: typed base routes, service catalog, custom user route records, route resolution/write services, and an injectable bypass view model.

- [ ] Define tests before implementation for strict IPv4 CIDR parsing (prefix 8...32), invalid/malformed CIDRs, API response/error decoding, loaded-empty versus read-failure state, and additive route composition.
- [ ] Define bypass domain types and protocols so base/catalog routes are fetched separately from user-defined routes and remain independently representable.
- [ ] Implement API calls matching Android's existing service contract: platform-aware base routes, service catalog, domain resolution, current custom routes, and write operations. Preserve server errors as typed failures rather than replacing failed reads with an empty list.
- [ ] Build the screen with current routes, “Обойти все РФ сервисы”, service search, custom CIDR/name input, remove actions, loading/applying/error states, and supported/unsupported capability messaging. Do not add a purchase link.
- [ ] Ensure enabling all services composes those resolved service routes with the existing shared/default route set; adding or deleting a custom route must never overwrite or hide that base set.
- [ ] Add tests for successful write followed by authoritative refresh, failed refresh, invalid CIDR, duplicate/overlapping inputs, and no accidental empty replacement when a source read fails.
- [ ] Run `swift test --package-path Packages/CoreDomain`, `swift test --package-path Packages/CoreNetwork`, and `swift test --package-path Packages/FeatureBypass`.

### Task 3: Build authentication UI and lifecycle integration

**Files:**
- Create: `Packages/FeatureAuth/Sources/FeatureAuth/LoginViewModel.swift`
- Create: `Packages/FeatureAuth/Sources/FeatureAuth/LoginScreen.swift`
- Create: `Packages/FeatureAuth/Sources/FeatureAuth/LoginWaitingScreen.swift`
- Create: `Packages/FeatureAuth/Tests/FeatureAuthTests/LoginViewModelTests.swift`
- Modify: `Apps/VpnApp/App/AppComposition.swift`
- Modify: `Apps/VpnApp/App/RootView.swift`

**Interfaces:**
- Consumes: `AuthFlowController`, `AuthFlowState`, `AuthService`, `SessionStore`, and injected deep-link opener; Telegram nonce flow stays owned by the existing controller.
- Produces: SwiftUI login and waiting screens and root navigation transitions for idle, in-flight, authenticated, and error states.

- [ ] Write view-model tests for initial login, Telegram unavailable/browser fallback, six-digit nonce sanitization, pending poll, expired operation restart, authenticated transition, and resume after returning from background.
- [ ] Implement a main-actor presentation adapter around the existing controller; publish state without moving authentication/security logic into SwiftUI views.
- [ ] Implement login and login-waiting screens with current Android copy, six-digit numeric input, live five-minute countdown, retry/reopen bot actions, and all accessibility labels/test identifiers needed by UI tests.
- [ ] Wire universal/deep-link opening through an injected iOS URL opener; do not poll while the app is suspended, and resume the stored operation when the app becomes active.
- [ ] Test that secrets and session tokens never enter view state, visible error strings, or logs.
- [ ] Run `swift test --package-path Packages/FeatureAuth`; add focused XCUITest coverage after root navigation exists.

### Task 4: Implement home, connection list, account, and informational screens

**Files:**
- Modify: `Packages/FeatureHome/Sources/FeatureHome/HomeViewModel.swift`
- Modify: `Packages/FeatureHome/Sources/FeatureHome/HomeState.swift`
- Modify: `Packages/FeatureHome/Tests/FeatureHomeTests/` (status, selection, and action tests)
- Create: `Apps/VpnApp/App/RootView.swift`
- Create: `Apps/VpnApp/App/HomeScreen.swift`
- Create: `Apps/VpnApp/App/AccountScreens.swift`
- Create: `Apps/VpnApp/App/NavigationState.swift`
- Modify: `Packages/CoreUI/Sources/CoreUI/DesignTokens.swift`
- Modify: `Apps/VpnApp/App/AppComposition.swift`

**Interfaces:**
- Consumes: `HomeViewModel`, `HomeViewState`, `Connection`, `TunnelControlling`, `ConfigService`, existing CoreUI tokens, and the current Android UI behavior spec.
- Produces: root routing and screens for connection, configs/country selection, account, no subscription, access revoked, VPN permission guidance, about, and non-connecting demo.

- [ ] Add/adjust tests for each action/status mapping and enforce that a system `.connected` state renders “Подключено”, never “Защищено” or green protection confirmation.
- [ ] Implement app-level observable presentation adapters as needed; retain domain decision logic in existing pure Swift view models.
- [ ] Implement connection screen, config selection/expiry/revocation states, account and support routes, permission instruction, about, and demo mode with no tunnel side effects.
- [ ] Add the bypass card/navigation entry and display applied bypass count only when available from actual tunnel configuration; when unavailable, use the neutral Android-compatible subtitle.
- [ ] Apply CoreUI design tokens and accessibility identifiers; support dynamic type and safe areas on iPhone-sized layouts.
- [ ] Add UI tests for unauthenticated launch, successful login-to-home transition with fakes, connect/disconnect button state, expired/revoked access, and demo mode never starting a tunnel.
- [ ] Run `swift test --package-path Packages/FeatureHome` and XCUITest on macOS simulator.

### Task 5: Implement app-side NetworkExtension tunnel manager

**Files:**
- Create: `Apps/VpnApp/App/TunnelManager.swift`
- Create: `Apps/VpnApp/App/ProtectionProbe.swift` only if a real probe can be implemented and verified; otherwise do not create it and keep the UI connection-only.
- Create: `Apps/VpnApp/Tests/TunnelManagerTests.swift`
- Modify: `Apps/VpnApp/App/AppComposition.swift`
- Modify: `Packages/TunnelKitCore/Sources/TunnelKitCore/TunnelConfiguration.swift` only if tests prove required configuration data is absent.

**Interfaces:**
- Consumes: `TunnelControlling`, `Profile`, `TunnelConfigurationBuilder`, and the shared App Group contract.
- Produces: app-side `TunnelControlling` adapter using `NETunnelProviderManager`, preference save/load, connect/disconnect, current state, and a multi-subscriber status stream that replays current state.

- [ ] Write adapter tests around an injectable manager/preferences boundary for manager creation, persistence, connect/disconnect, permission/configuration failures, and status replay.
- [ ] Inspect the pinned/selected TunnelKit version's actual API and license before adding it as a dependency; do not invent adapter calls. Confirm a minimal NetworkExtension target can link the engine on `macos-15` CI before implementing full session wiring.
- [ ] Implement `NETunnelProviderManager` configuration and `NEVPNStatus` mapping to domain statuses; map `.connected` to the existing verification/in-progress state or connection-only presentation, never directly to a protected verdict.
- [ ] Persist the validated profile/configuration atomically into the shared App Group before starting the provider; clear it on logout and ensure failed profile refresh leaves the last valid profile intact.
- [ ] Configure included/excluded routes so built-in and user-added bypass CIDRs are both retained. Do not combine `includeAllNetworks = true` with excluded routes: on iOS, excluded routes would not provide the intended bypass under that setting. Treat this route/kill-switch compatibility as a build/device verification gate, not an assumption.
- [ ] Verify the app-side target builds and adapter unit tests pass on macOS; defer only device permission prompts and live tunnel traffic validation.

### Task 6: Implement packet-tunnel extension and OpenVPN session adapter

**Files:**
- Create: `Apps/VpnApp/PacketTunnel/PacketTunnelProvider.swift`
- Create: `Apps/VpnApp/PacketTunnel/TunnelKitSession.swift`
- Create: `Apps/VpnApp/Shared/SharedTunnelConfiguration.swift`
- Create: `Apps/VpnApp/Tests/SharedTunnelConfigurationTests.swift`
- Modify: `Apps/VpnApp/VpnApp.xcodeproj/project.pbxproj`
- Modify: `.github/workflows/ios.yml`

**Interfaces:**
- Consumes: serialized validated profile and route settings from the App Group; existing pure-Swift `TunnelKitCore` parser/configuration; selected TunnelKit API after Task 5's link gate.
- Produces: `NEPacketTunnelProvider` start/stop lifecycle that configures `NEPacketTunnelNetworkSettings`, launches/stops the OpenVPN session, and reports typed startup errors to the system.

- [ ] Add tests for App Group serialization round-trip, versioned required fields, and route configuration retaining both default and user-defined bypass networks.
- [ ] Implement provider startup to read and validate the shared configuration, configure DNS/IPv4/IPv6 and route policies, and fail closed when the profile/configuration is invalid.
- [ ] Integrate the real TunnelKit session inside the extension process only; stop it cleanly from `stopTunnel` and propagate startup/runtime failures without logging raw profile data.
- [ ] Ensure IPv6 cannot escape outside the tunnel; verify the actual NetworkExtension routing behavior on a physical iPhone before claiming any protection guarantee.
- [ ] Add the extension product/framework linkage and capability settings to the Xcode project and keep the provider independent from app-side `NETunnelProviderManager` code.
- [ ] Run Xcode build/link and extension unit tests on `macos-15`; record physical-device-only checks for when the user supplies membership/device access.

### Task 7: Complete app navigation, integration tests, and CI readiness

**Files:**
- Modify: `Apps/VpnApp/App/RootView.swift`
- Modify: `Apps/VpnApp/App/AppComposition.swift`
- Create: `Apps/VpnApp/UITests/VpnAppFlowTests.swift`
- Modify: `.github/workflows/ios.yml`
- Modify: `.github/exportOptions.plist`
- Modify: `docs/architecture/2026-10-03-development-infrastructure.md`
- Modify: `docs/README.md`

**Interfaces:**
- Consumes: completed auth, home, bypass, tunnel, app, and extension slices.
- Produces: coherent application flow, green unsigned macOS build/test CI, explicit credential checklist, and a user-ready gate for signing/TestFlight/device tests.

- [ ] Exercise launch → Telegram login → connection list → connect/disconnect → bypass screen → account/logout with injected API/tunnel fakes; assert no app path leaks tokens or enters an unsupported “protected” state.
- [ ] Add failure-path UI tests for API unavailable, profile rejected, VPN permission denied, tunnel startup failure, expired access, and bypass list read failure.
- [ ] Update CI to build the app and extension and run package/app/UI tests without signing on every PR; retain a separate signing/upload job gated by all required GitHub secrets.
- [ ] Ensure export options map provisioning profiles for both app and extension, and that CI validation gives a clear missing-secret message instead of failing halfway through signing.
- [ ] Document which account settings/profiles/secrets the user must provide later, exact GitHub secret names, and the remaining physical iPhone validation steps; do not place secret values in the repository.
- [ ] Run all Swift package tests and the full unsigned Xcode build/test on macOS CI; leave TestFlight upload and device installation marked as blocked until credentials/device access are supplied.

## Parallelization and Integration Order

- Start Task 1 first because all app/extension work needs stable target IDs and package links.
- Then parallelize Task 2 (bypass package/API/UI), Task 3 (auth UI), and Task 5 (app-side tunnel manager) once their interfaces are agreed. Task 4 can begin using the existing home interfaces in parallel with Tasks 2 and 3, with final integration after the app shell exists.
- Task 6 depends on Task 1 and the TunnelKit macOS link gate in Task 5; do not parallelize extension implementation against an unverified or guessed TunnelKit API.
- Task 7 runs after feature and tunnel slices are integrated; keep unsigned build/test separate from credentialed distribution.
- Each delegated task must stay within its file ownership above; integration changes to `AppComposition.swift`, project settings, and CI are coordinated by the lead to avoid merge conflicts.

## Acceptance Criteria

- The iOS app includes all currently specified user-facing flows, including bypass management, and is usable without paid Apple credentials in unsigned macOS simulator builds where platform limitations permit.
- Base/default bypass routes remain present when service or custom bypass routes are added, removed, or refreshed.
- Auth uses the existing secret + user-entered nonce protocol and resumes safely after backgrounding.
- Tunnel lifecycle is isolated in NetworkExtension; no UI state claims the VPN is secure merely because the system reports connected.
- Existing and new Swift package tests pass, and macOS CI builds the app and extension unsigned.
- Remaining external blockers are explicitly limited to Apple Developer membership/team configuration, provisioning/signing/App Store Connect secrets, TestFlight review, and physical-iPhone tunnel verification.
