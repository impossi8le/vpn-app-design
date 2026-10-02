// Sources/TestSupport/Fixtures.swift
import Foundation

public enum Fixtures {
    public static let me = """
    { "chat_id": 123456789,
      "configs": [
        { "id": "nl-ams-1", "name": "Нидерланды · Амстердам",
          "location": { "country_code": "NL", "city": "Амстердам" },
          "start_date": "2026-09-01T00:00:00Z", "end_date": "2026-12-01T00:00:00Z",
          "status": "active" },
        { "id": "nl-rtm-1", "name": "Нидерланды · Роттердам",
          "location": { "country_code": "NL", "city": "Роттердам" },
          "start_date": "2026-05-01T00:00:00Z", "end_date": "2026-09-01T00:00:00Z",
          "status": "expired" }
      ] }
    """

    public static let meEmpty = """
    { "chat_id": 123456789, "configs": [] }
    """

    public static let authLink = """
    { "public_code": "a7f3c9d2e1b4",
      "deep_link": "https://t.me/example_bot?start=login_a7f3c9d2e1b4",
      "expires_at": "2026-10-02T10:15:00Z" }
    """

    public static let pollPending = """
    { "status": "pending", "retry_after_ms": 2000 }
    """

    public static let pollConfirmed = """
    { "status": "confirmed", "session_token": "token-abc",
      "expires_at": "2026-11-02T10:00:00Z", "chat_id": 123456789 }
    """

    public static let errorNonceMismatch = """
    { "error": { "code": "nonce_mismatch", "message": "Неверный код", "retryable": true } }
    """

    public static let errorExpired = """
    { "error": { "code": "subscription_expired", "message": "Подписка истекла", "retryable": false } }
    """

    public static let errorRevoked = """
    { "error": { "code": "config_revoked", "message": "Доступ отозван", "retryable": false } }
    """
}
