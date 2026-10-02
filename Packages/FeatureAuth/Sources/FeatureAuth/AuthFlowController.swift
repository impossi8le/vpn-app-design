import Foundation
import CoreDomain
import CoreSecurity

/// Result of handing a deep link to the system.
public enum DeepLinkOpenResult: Equatable {
    case opened
    case appNotInstalled
}

/// Injected so the controller never touches UIKit/AppKit and stays testable.
public typealias DeepLinkOpener = (URL) -> DeepLinkOpenResult

/// Typed failures the flow can surface. Carries no payload, so it is safe to
/// render or log.
public enum AuthFlowError: Error, Equatable {
    case missingTelegram
    case linkRequestFailed
    case invalidCode
    case notStarted
    case nonceMismatch
    case operationExpired
    case operationDenied
    case attemptLimitExceeded
    case pollFailed
}

public enum AuthFlowState: Equatable {
    case idle
    case requestingLink
    case awaitingConfirmation(AuthLink)
    case awaitingCode
    case verifying
    case authenticated(Session)
    case failed(AuthFlowError)
}

/// Pure-Swift login state machine. No SwiftUI/Combine/UIKit — it drives
/// `AuthService` and exposes state a view model can render.
///
/// Login-CSRF safety (api-contract §2.1): the controller never knows the
/// `device_nonce` until the user reads it in Telegram and types it. There is
/// deliberately no code path that polls without a nonce.
public final class AuthFlowController {

    private let authService: AuthService
    private let sessionStore: SessionStore
    private let operationStore: AuthOperationStore
    private let openURL: DeepLinkOpener

    public init(authService: AuthService,
                sessionStore: SessionStore,
                operationStore: AuthOperationStore,
                openURL: @escaping DeepLinkOpener) {
        self.authService = authService
        self.sessionStore = sessionStore
        self.operationStore = operationStore
        self.openURL = openURL
    }

    public private(set) var state: AuthFlowState = .idle

    // In-flight login. `operation` is kept in memory and mirrored to the
    // operation store so a resumed app can continue after a cold start.
    private var link: AuthLink?
    private var operation: AuthOperation?
    private var nonce: String?

    /// Deep link the UI should offer to open. Non-nil through the whole flow,
    /// including `.failed(.missingTelegram)` so a browser fallback is possible.
    public func currentDeepLink() -> URL? { link?.deepLink }

    /// Redacted, log-safe description. Never contains the token or secret.
    public var stateDescription: String {
        switch state {
        case .idle:               return "idle"
        case .requestingLink:     return "requestingLink"
        case .awaitingConfirmation: return "awaitingConfirmation"
        case .awaitingCode:       return "awaitingCode"
        case .verifying:          return "verifying"
        case .authenticated:      return "authenticated"
        case .failed(let e):      return "failed(\(e))"
        }
    }

    /// Begin login: ask for a link, persist the in-flight operation, open the
    /// bot deep link.
    public func startFlow() async {
        link = nil
        operation = nil
        nonce = nil
        state = .requestingLink

        do {
            let (requestedLink, requestedOperation) = try await authService.requestLink()
            link = requestedLink
            operation = requestedOperation
            try? operationStore.save(requestedOperation)
            state = .awaitingConfirmation(requestedLink)

            if openURL(requestedLink.deepLink) == .appNotInstalled {
                state = .failed(.missingTelegram)
            }
        } catch {
            state = .failed(.linkRequestFailed)
        }
    }

    /// User is now looking at the code in Telegram and will type it in.
    public func enterCodeEntry() {
        if case .awaitingConfirmation = state { state = .awaitingCode }
    }

    /// Verify with the `device_nonce` the user read in the bot. Polling without
    /// a nonce is impossible here by construction.
    public func submitCode(_ nonce: String) async {
        let sanitized = nonce.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !sanitized.isEmpty else {
            state = .failed(.invalidCode)
            return
        }
        guard let inFlight = operation ?? (try? operationStore.load()) ?? nil else {
            state = .failed(.notStarted)
            return
        }
        await verify(inFlight, nonce: sanitized)
    }

    /// Called when the app returns to the foreground. iOS sleeps the app, so
    /// polling is not continuous — an unfinished operation is continued here.
    public func resume() async {
        let inFlight = operation ?? (try? operationStore.load()) ?? nil

        // A stored nonce means the user already submitted a code and the poll
        // failed; retry it with the same nonce.
        if let inFlight, let storedNonce = nonce {
            await verify(inFlight, nonce: storedNonce)
            return
        }
        // Otherwise rehydrate to the code-entry step (fresh app, or code not
        // entered yet). Never polls without a nonce.
        if inFlight != nil, case .awaitingConfirmation = state {
            state = .awaitingCode
        } else if inFlight != nil, state == .idle {
            state = .awaitingCode
        }
    }

    /// Abandon the current operation and start over — the recovery path for an
    /// expired/denied/consumed operation, so the flow never dead-ends.
    public func restart() {
        try? operationStore.clear()
        operation = nil
        nonce = nil
        link = nil
        state = .idle
    }

    /// Clear session and in-flight state. `logout` on the service is
    /// best-effort: local state is cleared even if the network call fails.
    public func logout() async {
        try? await authService.logout()
        try? sessionStore.clear()
        try? operationStore.clear()
        operation = nil
        nonce = nil
        link = nil
        state = .idle
    }

    // MARK: - private

    private func verify(_ inFlight: AuthOperation, nonce: String) async {
        self.nonce = nonce
        state = .verifying
        do {
            switch try await authService.pollSession(operation: inFlight, deviceNonce: nonce) {
            case .confirmed(let session):
                try? sessionStore.save(session)
                // Operation is spent: do not resume it again.
                try? operationStore.clear()
                operation = nil
                self.nonce = nil
                state = .authenticated(session)
            case .pending:
                // Нормальное состояние, пока пользователь в боте. Операция и nonce
                // сохраняются — `resume()` (или следующий опрос) продолжится.
                state = .awaitingCode
            case .expired, .denied, .attemptLimitExceeded:
                // Терминальные: операция больше не возобновляется, начинаем заново.
                try? operationStore.clear()
                operation = nil
                self.nonce = nil
                state = .failed(.pollFailed)
            }
        } catch let error as AuthFlowError {
            state = .failed(error)
        } catch {
            // Сеть/сервер: операцию и nonce сохраняем, `resume()` повторит.
            state = .failed(.pollFailed)
        }
    }
}
