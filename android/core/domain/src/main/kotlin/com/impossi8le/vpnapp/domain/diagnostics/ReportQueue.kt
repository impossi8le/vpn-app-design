package com.impossi8le.vpnapp.domain.diagnostics

/**
 * Очередь недоотправленного.
 *
 * Порт, а не реализация: домену всё равно, файл это или база, а тесты подставляют
 * фейк без Android. Задача очереди — выжить перезапуск: отправка идёт в фоне, и
 * приложение может закрыться между попытками.
 *
 * Элемент — уже сериализованный JSON: транспорт шлёт батч как есть и не зависит
 * от модели.
 */
interface ReportQueue {
    suspend fun enqueue(json: String)
    suspend fun peek(): List<String>
    suspend fun remove(ids: List<String>)
    suspend fun size(): Int

    /**
     * Очистка целиком — на случай `401`. Без токена отправить нечего, а копить
     * события чужого пользователя после выхода из аккаунта нельзя.
     */
    suspend fun clear()
}
