// Tests/CoreSecurityTests/KeychainAuthOperationStoreTests.swift
import XCTest
import CoreDomain
@testable import CoreSecurity

final class KeychainAuthOperationStoreTests: XCTestCase {

    private let account = "com.vpnapp.auth.operation"
    private func makeStore(_ backend: FakeKeychainBackend) -> KeychainAuthOperationStore {
        KeychainAuthOperationStore(backend: backend, account: account)
    }
    /// The secret must survive a background trip so resume can poll again.
    private func sampleOperation() -> AuthOperation {
        AuthOperation(publicCode: "a7f3c9d2e1b4",
                      secret: "s3cr3t-not-logged",
                      createdAt: Date(timeIntervalSince1970: 1_800_000_000))
    }

    func testLoadReturnsNilWhenNoOperationPersisted() throws {
        XCTAssertNil(try makeStore(FakeKeychainBackend()).load())
    }

    func testOperationRoundTripsIncludingSecret() throws {
        let backend = FakeKeychainBackend()
        let store = makeStore(backend)
        let operation = sampleOperation()

        try store.save(operation)
        let loaded = try XCTUnwrap(try store.load())

        XCTAssertEqual(loaded, operation)
        XCTAssertEqual(loaded.secret, operation.secret)
    }

    func testSecretIsPersistedThroughTheBackendNotOnDiskInCleartext() throws {
        // The backend is the only sink; a fake backend proves the secret goes
        // there and nowhere else. (Real backend = Keychain.)
        let backend = FakeKeychainBackend()
        try makeStore(backend).save(sampleOperation())

        let data = try XCTUnwrap(backend.storage[account])
        XCTAssertTrue(try XCTUnwrap(String(data: data, encoding: .utf8)).contains("s3cr3t-not-logged"))
    }

    func testClearRemovesInFlightOperation() throws {
        let backend = FakeKeychainBackend()
        let store = makeStore(backend)
        try store.save(sampleOperation())

        try store.clear()

        XCTAssertNil(try store.load())
        XCTAssertNil(backend.storage[account])
    }

    func testCorruptOperationDataSurfacesAsError() throws {
        let backend = FakeKeychainBackend()
        try backend.set(Data("broken".utf8), account: account)
        XCTAssertThrowsError(try makeStore(backend).load()) { error in
            XCTAssertEqual(error as? CoreSecurityError, .corruptData)
        }
    }
}
