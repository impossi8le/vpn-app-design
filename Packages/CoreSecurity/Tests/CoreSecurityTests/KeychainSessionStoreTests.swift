// Tests/CoreSecurityTests/KeychainSessionStoreTests.swift
import XCTest
import CoreDomain
@testable import CoreSecurity

/// In-memory `KeychainBackend`. Optional failure hooks let tests assert that a
/// backend error propagates instead of being swallowed.
final class FakeKeychainBackend: KeychainBackend {
    private(set) var storage: [String: Data] = [:]
    var setError: Error?
    var getError: Error?
    var deleteError: Error?
    private(set) var setCallCount = 0
    private(set) var deleteCallCount = 0

    func set(_ data: Data, account: String) throws {
        setCallCount += 1
        if let error = setError { throw error }
        storage[account] = data
    }

    func get(account: String) throws -> Data? {
        if let error = getError { throw error }
        return storage[account]
    }

    func delete(account: String) throws {
        deleteCallCount += 1
        if let error = deleteError { throw error }
        storage[account] = nil
    }
}

final class KeychainSessionStoreTests: XCTestCase {

    private let account = "com.vpnapp.session"
    private func makeStore(_ backend: FakeKeychainBackend) -> KeychainSessionStore {
        KeychainSessionStore(backend: backend, account: account)
    }
    private func sampleSession() -> Session {
        Session(token: "tok-abc", expiresAt: Date(timeIntervalSince1970: 1_800_000_000), chatID: 987654321)
    }

    func testLoadReturnsNilBeforeAnySave() throws {
        let backend = FakeKeychainBackend()
        XCTAssertNil(try makeStore(backend).load())
    }

    func testSaveLoadRoundTripPreservesEveryField() throws {
        let backend = FakeKeychainBackend()
        let store = makeStore(backend)
        let session = sampleSession()

        try store.save(session)
        XCTAssertEqual(try store.load(), session)
    }

    func testSaveEncodesSessionIntoBackendAsData() throws {
        let backend = FakeKeychainBackend()
        try makeStore(backend).save(sampleSession())

        // Persisted as Data under the store's account, and decodable back to the
        // JSON shape (chat_id), proving encode -> Data -> backend.
        let data = try XCTUnwrap(backend.storage[account])
        let json = try XCTUnwrap(String(data: data, encoding: .utf8))
        XCTAssertTrue(json.contains("\"chat_id\""))
        XCTAssertTrue(json.contains("tok-abc"))
    }

    func testSaveOverwritesPreviousSession() throws {
        let backend = FakeKeychainBackend()
        let store = makeStore(backend)
        try store.save(sampleSession())
        let replacement = Session(token: "tok-new", expiresAt: Date(timeIntervalSince1970: 1_900_000_000), chatID: 1)
        try store.save(replacement)

        XCTAssertEqual(try store.load(), replacement)
    }

    func testClearRemovesSessionAndIsIdempotent() throws {
        let backend = FakeKeychainBackend()
        let store = makeStore(backend)
        try store.save(sampleSession())

        try store.clear()
        XCTAssertNil(try store.load())
        XCTAssertNil(backend.storage[account])

        try store.clear() // second clear must not throw
        XCTAssertNil(try store.load())
    }

    func testCorruptBackendDataSurfacesAsErrorNotSilentNil() throws {
        let backend = FakeKeychainBackend()
        try backend.set(Data("not-json".utf8), account: account)

        XCTAssertThrowsError(try makeStore(backend).load()) { error in
            XCTAssertEqual(error as? CoreSecurityError, .corruptData)
        }
    }

    func testBackendSetErrorPropagates() throws {
        let backend = FakeKeychainBackend()
        backend.setError = CoreSecurityError.corruptData
        XCTAssertThrowsError(try makeStore(backend).save(sampleSession()))
    }

    func testBackendDeleteErrorPropagates() throws {
        let backend = FakeKeychainBackend()
        backend.deleteError = CoreSecurityError.corruptData
        XCTAssertThrowsError(try makeStore(backend).clear())
    }

    func testLogoutClearsSessionAndNotifiesBackend() throws {
        let backend = FakeKeychainBackend()
        let store = makeStore(backend)
        try store.save(sampleSession())

        try store.clear()

        XCTAssertNil(try store.load())
        XCTAssertEqual(backend.deleteCallCount, 1)
    }
}
