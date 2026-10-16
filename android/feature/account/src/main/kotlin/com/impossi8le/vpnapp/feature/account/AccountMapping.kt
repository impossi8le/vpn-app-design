package com.impossi8le.vpnapp.feature.account

import com.impossi8le.vpnapp.domain.config.ConfigList
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Значение, которое показывает строка «Подписка до», когда сервер срока не
 * прислал. Именно прочерк, а не пустая строка и не выдуманная дата: «—» честно
 * говорит «данных нет», тогда как `01.01.1970` выглядел бы правдоподобно.
 */
const val SUBSCRIPTION_UNKNOWN = "—"

/**
 * Состояние экрана аккаунта по умолчанию.
 *
 * Живёт здесь, рядом с [AccountScreenState], а не в точке сборки `AppRootState`:
 * это умолчание самого экрана, и держать его в двух местах (в `AppRoot` и в
 * `MainActivity`) значило бы развести их при первой же правке. `AppRoot` берёт
 * этот объект дефолтом, `MainActivity` — основой, поверх которой кладутся
 * данные из `/me`.
 *
 * Данные аккаунта (`activeConnections`, `subscriptionUntil`) тут нулевые: их
 * заполняет ответ сервера, а не константа — до него экран показывает «0 из 0».
 *
 * `telegramId` — тоже `null`, а не выдуманное число: ранее тут стоял
 * литерал вида «•••• 4821», и кнопка «Показать» раскрывала его как настоящий ID.
 * Чужое число на экране хуже пустоты — пользователь считает его своим.
 */
val DefaultAccountScreenState = AccountScreenState(
    telegramId = null,
    telegramIdRevealed = false,
    activeConnections = 0,
    totalConnections = 0,
    expiredConnections = 0,
    subscriptionUntil = SUBSCRIPTION_UNKNOWN,
    confirmCountrySwitch = true,
)

/**
 * Наложить данные ответа `/me` на состояние экрана аккаунта.
 *
 * До этой функции экран показывал «Подключений активно 0 из 0» и «Подписка до —»
 * даже когда пользователь вошёл и подключения есть: список из `/me` питал
 * ТОЛЬКО перечень подключений, а поля аккаунта никто не заполнял. Здесь они
 * берутся из того же ответа — сервер отдаёт `connections_limit` и
 * `subscription_until` (§3 контракта).
 *
 * Функция чистая и не знает про UI: счётчики и дату считает она, экран только
 * показывает готовые строки. Поэтому её поведение проверяется тестом без рендера.
 *
 * «Истекшими» считаем ВСЁ, что не активно (`expired` и `revoked`): лимит занят
 * только действующими подключениями, и строка «N истекли» должна говорить
 * правду о том, что не работает.
 *
 * Telegram ID — это `chat_id` из того же ответа (§3 контракта): сервер отдаёт
 * его числом. Сервер прислал ноль или поле вовсе — честное `null` («данных
 * нет»), а не выдуманное число, которое пользователь принял бы за своё.
 *
 * @param base состояние, чьи поля о функции не знают (тумблеры, флаг раскрытия
 *   ID): они переносятся как есть.
 */
fun ConfigList.toAccountScreenState(base: AccountScreenState): AccountScreenState = base.copy(
    telegramId = chatId.takeIf { it != 0L }?.toString(),
    activeConnections = configs.count { it.status == SubscriptionStatus.ACTIVE },
    expiredConnections = configs.count { it.status != SubscriptionStatus.ACTIVE },
    totalConnections = connectionsLimit,
    subscriptionUntil = subscriptionUntilEpochSeconds?.let(::formatAccountDate)
        ?: SUBSCRIPTION_UNKNOWN,
)

/** Дата в формате «dd.MM.yyyy» — та же, что у списка подключений. */
private val ACCOUNT_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private fun formatAccountDate(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDate()
        .format(ACCOUNT_DATE_FORMAT)
