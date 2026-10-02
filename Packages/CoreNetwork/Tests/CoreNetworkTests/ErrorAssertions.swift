// Tests/CoreNetworkTests/ErrorAssertions.swift
import XCTest
import CoreNetwork

extension XCTestCase {
    /// Утверждает, что выражение бросает `APIError` с заданным кодом контракта.
    func expectAPIError(_ expected: APIErrorCode,
                        file: StaticString = #filePath, line: UInt = #line,
                        _ expression: () async throws -> Void) async {
        do {
            try await expression()
            XCTFail("ожидалась ошибка \(expected), но вызов завершился успешно", file: file, line: line)
        } catch let error as APIError {
            XCTAssertEqual(error.code, expected,
                           "получен код \(error.code), ожидался \(expected)", file: file, line: line)
        } catch {
            XCTFail("ожидалась типизированная APIError, получено \(error)", file: file, line: line)
        }
    }

    /// Сахар для читаемости: код как строку из таблиц контракта.
    /// `APIErrorCode(rawValue:)` не failable — неизвестный код становится `.unknown`.
    func expectAPIError(_ expected: String,
                        file: StaticString = #filePath, line: UInt = #line,
                        _ expression: () async throws -> Void) async {
        await expectAPIError(APIErrorCode(rawValue: expected), file: file, line: line, expression)
    }
}
