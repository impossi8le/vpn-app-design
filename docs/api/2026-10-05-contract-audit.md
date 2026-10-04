# Аудит: контракт API ↔ Android-код

Дата: 2026-10-05. Первоисточник истины — код. Код не менялся.
Сверено: контракт `docs/architecture/2026-10-02-api-contract.md` (далее К) против `android/core/network`, `core/domain`, `core/config`, `feature/*`.

## Таблица расхождений

| Раздел К | В контракте | В коде | Вердикт |
|---|---|---|---|
| §1 | `public_code`, `secret_hash`, `device_name`, `platform`, `app_version` | Совпадают дословно, `AuthApi.kt:37–41`. `platform="android"` (`ApiClient.kt:29`) | код прав |
| §1 | `device_name` = `"iPhone 15"` | Android шлёт значение, которое никто не заполняет: `AuthViewModel.kt:47,60`, `MODEL` не используется | **надо уточнить** — риск пустого поля |
| §1 | ошибки `409 public_code_conflict`, `429` | `ErrorMapping.kt:31–32` → `Conflict`/`RateLimited` | код прав |
| §2 | `retry_after_ms` | Код читает `retry_after_ms` (`AuthApi.kt:118`); iOS `HTTPAuthClient.swift:160` — тоже. Задача упоминала `refresh_after_ms` — в репозитории его нет | код прав |
| §2 | `chat_id` только в `/auth/poll` | То же (`AuthApi.kt:116`) | код прав |
| §2 | `expires_at` в ответах `/link` и `/poll` | **К не описывает `expires_at` у `/auth/poll`**, код ждёт его (`AuthApi.kt:114`) | **контракт прав, в К пробел** |
| §3 | `chat_id`, `configs[].{id,name,location,start_date,end_date,status}` | Все совпадают (`ConfigApi.kt:49–60`) | код прав |
| §4 | `X-Config-Version`, `X-Config-Hash` | Имена совпадают (`ConfigApi.kt:104–105`) | код прав |
| §4 | `401 unauthorized` | `ErrorMapping.kt:26` | код прав |
| §4 | `403 config_revoked` / `subscription_expired` | Различаются по `code`, не по статусу (`ErrorMapping.kt:28–29`) | код прав |
| §4 | `410 config_retired` | → `ConfigRevoked` (`ErrorMapping.kt:34`) | код прав |
| §4 | `404 config_not_found` | → `NotFound` (`ErrorMapping.kt:30`) | код прав |
| §4 | `402` только у `subscription_expired` | По `402` без `code` → `Unexpected` (`ErrorMapping.kt:35`); `toConfigFetchError` не покрывает | **надо уточнить**: сервер обязан слать `code` |
| §5 | `ProfileStore.stage/commit/rollback` | Существует, реализован (`FileProfileStore.kt`) | код прав |
| §5 | `X-Config-Hash` не совпал → `rollback` | `ConfigManager.kt:87–92` | код прав |
| §5 | невалидный `.ovpn` → stage отклоняет | `FileProfileStore.kt:49`, `ConfigManager.kt:78–83` | код прав |
| §5 | сеть недоступна → последний профиль + плашка | `ConfigManager.kt:67–70`; фон — ниже | код прав |

### Коды ошибок
Обработаны все 12 из К. Сверх К код знает `404 operation_not_found` (→`NotFound`, `ErrorMapping.kt:30`) и `409`+`code` (`Conflict`). Переиспользование `403` учтено верно.

## Чего не хватает для Android

- **`expires_at` в `/auth/poll`** не описан в §2 — код обязан его читать.
- **Таймауты** (К не описывает): `ApiClient.kt:21–23` — connect **15 с**, read **30 с**, call **45 с**. Внести в §0.
- **`device_name`**: кто и как заполняет на Android — не определено; К предполагает осмысленное значение.
- Не описан фон «нет связи»: `ConfigManager` отдаёт `NetworkUnavailable`, но TTL «последнего профиля» не задан.

## ОПАСНЫЕ расхождения

1. **`device_name` пустой у Android** — поддержка/диагностика теряют данные, а поле обязательное по К.
2. **`expires_at` в `/auth/poll`** — при отсутствии код получит `0` (`JsonExt.kt:24`), сессия запишется с нулевым сроком → преждевременный выход.
3. **`402` без `code`** → `Unexpected`: экран покажет «ошибку» вместо «подписка истекла».
