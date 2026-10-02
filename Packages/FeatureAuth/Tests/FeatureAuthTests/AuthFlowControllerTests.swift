// Tests/FeatureAuthTests/AuthFlowControllerTests.swift
import XCTest
import Foundation
import CoreDomain
import CoreSecurity
import TestSupport
@testable import FeatureAuth

/// In-memory `KeychainBackend` reused for the CoreSecurity stores used here.
final class MemoryBackend: KeychainBackend {
    var storage: [String: Data] = [:]
    func set(_ data: Data, account: String) throws { storage[account] = data }
    func get(account: String) throws -> Data? { storage[account] }
    func delete(account: String) throws { storage[account] = nil }
}

/// A stand-in for a non-typed networking failure (W3 will throw typed errors).
private struct TransportError: Error {}

final class AuthFlowControllerTests: XCTestCase {

    private func makeHarness(
        executor: AuthService? = nil,
        opener: @escaping DeepLinkOpener = { _ in .opened }
    ) -> (AuthFlowController, FakeAuthService, FakeSessionStore, InMemoryOperationStore) {
        let auth = (executor as? FakeAuthService) ?? FakeAuthService()
        let sessions = FakeSessionStore()
        let operations = InMemoryOperationStore()
        let controller = AuthFlowController(authService: auth,
                                            sessionStore: sessions,
                                            operationStore: operations,
                                            openURL: opener)
        return (controller, auth, sessions, operations)
    }

    private func session() -> Session {
        Session(token: "tok-1", expiresAt: Date(timeIntervalSince1970: 2_000_000_000), chatID: 42)
    }

    // MARK: - startFlow

    func testInitialStateIsIdleWithNoDeepLink() {
        let (controller, _, _, _) = makeHarness()
        XCTAssertEqual(controller.state, .idle)
        XCTAssertNil(controller.currentDeepLink())
    }

    func testStartFlowMovesToAwaitingConfirmationAndExposesDeepLink() async {
        let (controller, _, _, _) = makeHarness()

        await controller.startFlow()

        guard case .awaitingConfirmation(let link) = controller.state else {
            return XCTFail("expected awaitingConfirmation, got \(controller.state)")
        }
        XCTAssertEqual(link.publicCode, "code")
        XCTAssertEqual(controller.currentDeepLink(), link.deepLink)
    }

    func testStartFlowOpensTheDeepLinkViaInjectedOpener() async {
        var opened: [URL] = []
        let (controller, _, _, _) = makeHarness(opener: { url in opened.append(url); return .opened })

        await controller.startFlow()

        XCTAssertEqual(opened, [controller.currentDeepLink()].compactMap { $0 })
    }

    func testStartFlowPersistsInFlightOperationForResume() async throws {
        let (controller, _, _, operations) = makeHarness()

        await controller.startFlow()

        let persisted = try operations.load()
        XCTAssertEqual(persisted?.publicCode, "code")
    }

    func testLinkRequestFailureBecomesFailedStateNotCrash() async {
        let failing = FailingAuthService(requestLinkError: TransportError())
        let controller = AuthFlowController(authService: failing,
                                            sessionStore: FakeSessionStore(),
                                            operationStore: InMemoryOperationStore(),
                                            openURL: { _ in .opened })

        await controller.startFlow()

        guard case .failed = controller.state else {
            return XCTFail("expected failed, got \(controller.state)")
        }
    }

    func testMissingTelegramIsAHandledState() async {
        let (controller, _, _, _) = makeHarness(opener: { _ in .appNotInstalled })

        await controller.startFlow()

        XCTAssertEqual(controller.state, .failed(.missingTelegram))
        // The link is still recoverable so the UI can offer a browser fallback.
        XCTAssertNotNil(controller.currentDeepLink())
    }

    // MARK: - code entry + nonce

    func testEnterCodeEntryMovesFromAwaitingConfirmationToAwaitingCode() async {
        let (controller, _, _, _) = makeHarness()
        await controller.startFlow()

        controller.enterCodeEntry()

        XCTAssertEqual(controller.state, .awaitingCode)
    }

    func testSubmitCodePassesTheNonceToPollSessionAndAuthenticates() async {
        let (controller, auth, sessions, _) = makeHarness()
        auth.session = session()
        await controller.startFlow()
        controller.enterCodeEntry()

        await controller.submitCode("4821")

        XCTAssertEqual(auth.lastNonce, "4821")
        XCTAssertEqual(controller.state, .authenticated(session()))
        XCTAssertEqual(sessions.session, session())
    }

    func testSubmitCodeNeverPollsWithoutANonce() async {
        let (controller, auth, _, _) = makeHarness()
        auth.session = session()
        await controller.startFlow()
        controller.enterCodeEntry()

        // No submitCode call at all -> poll must not have happened.
        XCTAssertNil(auth.lastNonce)

        await controller.submitCode("   ")   // blank nonce is not a nonce
        XCTAssertNil(auth.lastNonce)
        XCTAssertEqual(controller.state, .failed(.invalidCode))
    }

    func testSubmitCodeBeforeStartIsRejectedWithoutPolling() async {
        let (controller, auth, _, _) = makeHarness()
        auth.session = session()

        await controller.submitCode("4821")

        XCTAssertNil(auth.lastNonce)
        XCTAssertEqual(controller.state, .failed(.notStarted))
    }

    // MARK: - poll outcomes

    func testNonceMismatchSurfacesAsTypedFailure() async {
        let (controller, auth, _, _) = makeHarness()
        auth.pollError = AuthFlowError.nonceMismatch
        await controller.startFlow()
        controller.enterCodeEntry()

        await controller.submitCode("0000")

        XCTAssertEqual(controller.state, .failed(.nonceMismatch))
    }

    func testExpiredOperationOffersRestartInsteadOfDeadEnd() async throws {
        let (controller, auth, _, operations) = makeHarness()
        auth.pollError = AuthFlowError.operationExpired
        await controller.startFlow()
        controller.enterCodeEntry()

        await controller.submitCode("4821")
        XCTAssertEqual(controller.state, .failed(.operationExpired))

        controller.restart()
        XCTAssertEqual(controller.state, .idle)
        XCTAssertNil(try operations.load())   // in-flight op dropped

        // And the flow can start over cleanly.
        await controller.startFlow()
        guard case .awaitingConfirmation = controller.state else {
            return XCTFail("expected a fresh flow, got \(controller.state)")
        }
    }

    func testGenericPollErrorIsWrappedNotSwallowed() async {
        let (controller, auth, _, _) = makeHarness()
        auth.pollError = TransportError()
        await controller.startFlow()
        controller.enterCodeEntry()

        await controller.submitCode("4821")

        guard case .failed(.pollFailed) = controller.state else {
            return XCTFail("expected failed(.pollFailed), got \(controller.state)")
        }
    }

    // MARK: - resume / background

    func testResumeAfterBackgroundContinuesTheUnfinishedOperation() async {
        let (controller, _, _, _) = makeHarness()
        await controller.startFlow()

        await controller.resume()   // app returns to foreground

        XCTAssertEqual(controller.state, .awaitingCode)
    }

    func testResumeRehydratesOperationFromStoreInAFreshProcess() async {
        let backend = MemoryBackend()
        let operations = KeychainAuthOperationStore(backend: backend)
        let sessions = KeychainSessionStore(backend: backend)
        let auth = FakeAuthService()

        // First process starts a flow, then the app is killed before code entry.
        let first = AuthFlowController(authService: auth, sessionStore: sessions,
                                       operationStore: operations, openURL: { _ in .opened })
        await first.startFlow()

        // Fresh controller, same Keychain: resume must find the operation.
        let second = AuthFlowController(authService: auth, sessionStore: sessions,
                                        operationStore: operations, openURL: { _ in .opened })
        await second.resume()

        XCTAssertEqual(second.state, .awaitingCode)
    }

    func testResumeWithStoredNonceRePollsAndAuthenticates() async {
        let (controller, auth, _, _) = makeHarness()
        auth.pollError = TransportError()
        await controller.startFlow()
        controller.enterCodeEntry()
        await controller.submitCode("4821")           // network failed
        guard case .failed = controller.state else { return XCTFail("precondition") }

        auth.pollError = nil
        auth.session = session()
        await controller.resume()                     // foreground retry

        XCTAssertEqual(controller.state, .authenticated(session()))
        XCTAssertEqual(auth.lastNonce, "4821")
    }

    func testResumeWithoutNonceDoesNotPoll() async {
        let (controller, auth, _, _) = makeHarness()
        auth.session = session()
        await controller.startFlow()

        await controller.resume()

        XCTAssertNil(auth.lastNonce)
    }

    func testResumeWithNothingToResumeStaysIdle() async {
        let (controller, _, _, _) = makeHarness()
        await controller.resume()
        XCTAssertEqual(controller.state, .idle)
    }

    // MARK: - logout

    func testLogoutClearsSessionAndInFlightState() async throws {
        let (controller, auth, sessions, operations) = makeHarness()
        auth.session = session()
        await controller.startFlow()
        controller.enterCodeEntry()
        await controller.submitCode("4821")

        await controller.logout()

        XCTAssertEqual(controller.state, .idle)
        XCTAssertNil(sessions.session)
        XCTAssertNil(try operations.load())
        XCTAssertTrue(auth.logoutCalled)
    }

    func testLogoutClearsKeychainViaInjectedStores() async {
        let backend = MemoryBackend()
        let operations = KeychainAuthOperationStore(backend: backend)
        let sessions = KeychainSessionStore(backend: backend)
        let auth = FakeAuthService()
        auth.session = session()
        let controller = AuthFlowController(authService: auth, sessionStore: sessions,
                                            operationStore: operations, openURL: { _ in .opened })
        await controller.startFlow()
        controller.enterCodeEntry()
        await controller.submitCode("4821")

        await controller.logout()

        XCTAssertNil(backend.storage["com.vpnapp.session"])
        XCTAssertNil(backend.storage["com.vpnapp.auth.operation"])
    }

    // MARK: - no secrets in state/log output

    func testStateDescriptionsNeverContainTheSecret() async {
        let (controller, _, _, _) = makeHarness()
        await controller.startFlow()
        controller.enterCodeEntry()

        let leaked = [controller.stateDescription,
                      String(describing: controller.state)]
            .joined(separator: "\n")

        XCTAssertFalse(leaked.contains("secret"))
    }
}

// MARK: - test doubles

/// Minimal `AuthOperationStore` fake, independent of CoreSecurity.
final class InMemoryOperationStore: AuthOperationStore {
    private(set) var operation: AuthOperation?
    func save(_ operation: AuthOperation) throws { self.operation = operation }
    func load() throws -> AuthOperation? { operation }
    func clear() throws { operation = nil }
}

private final class FailingAuthService: AuthService {
    let requestLinkError: Error
    init(requestLinkError: Error) { self.requestLinkError = requestLinkError }
    func requestLink() async throws -> (link: AuthLink, operation: AuthOperation) {
        throw requestLinkError
    }
    func pollSession(operation: AuthOperation, deviceNonce: String) async throws -> Session {
        throw requestLinkError
    }
    func logout() async throws {}
}
