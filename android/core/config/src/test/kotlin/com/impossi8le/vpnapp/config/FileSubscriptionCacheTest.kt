package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.ConfigList
import com.impossi8le.vpnapp.domain.config.ConfigSummary
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Кэш списка подключений: запись, чтение, устойчивость к порче.
 *
 * Главное здесь — round-trip: то, что записали, читается назад БЕЗ потерь.
 * Потеря поля (срока, статуса, id) тихо превратила бы кэш в источник неверных
 * данных, а он показан пользователю на первом кадре.
 */
class FileSubscriptionCacheTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `записанный список читается обратно без потерь`() {
        val store = FileSubscriptionCache(dir)
        val list = ConfigList(
            chatId = 42L,
            configs = listOf(
                ConfigSummary(
                    id = "nl-ams-1",
                    name = "Нидерланды · Амстердам",
                    countryCode = "NL",
                    city = "Амстердам",
                    startDateEpochSeconds = 1_700_000_000L,
                    endDateEpochSeconds = 1_800_000_000L,
                    status = SubscriptionStatus.ACTIVE,
                ),
                ConfigSummary(
                    id = "nl-rtm-1",
                    name = "Нидерланды · Роттердам",
                    countryCode = "NL",
                    city = "Роттердам",
                    startDateEpochSeconds = 1_600_000_000L,
                    endDateEpochSeconds = 1_650_000_000L,
                    status = SubscriptionStatus.EXPIRED,
                ),
            ),
            connectionsLimit = 3,
            subscriptionUntilEpochSeconds = 1_800_000_000L,
        )

        store.save(list)

        assertEquals(list, store.load())
    }

    @Test
    fun `пустое хранилище возвращает null`() {
        assertNull(FileSubscriptionCache(dir).load())
    }

    @Test
    fun `отсутствие срока подписки сохраняется как null, а не как 1970`() {
        // Тот же принцип, что у `ConfigApi`: «данных нет» — честный null, а не
        // правдоподобная дата из нуля.
        val store = FileSubscriptionCache(dir)

        store.save(ConfigList(chatId = 1L, configs = emptyList(), subscriptionUntilEpochSeconds = null))

        assertNull(store.load()!!.subscriptionUntilEpochSeconds)
    }

    @Test
    fun `неизвестный статус читается как истёкший`() {
        // Кэш разбирает статус теми же правилами, что и сеть: непонятное —
        // «не активен». Иначе сохранённое и полученное разошлись бы, и
        // неизвестный статус стал бы рабочим после перезапуска.
        File(dir, "config-cache.json").writeText(
            """{"chat_id":1,"configs":[{"id":"x","name":"x","country_code":"","city":"", """ +
                """"start_date":1,"end_date":99999999999,"status":"weird"}]}""",
        )

        assertEquals(SubscriptionStatus.EXPIRED, FileSubscriptionCache(dir).load()!!.configs[0].status)
    }

    @Test
    fun `повреждённый файл не роняет приложение`() {
        File(dir, "config-cache.json").writeText("{ это не json")

        assertNull(FileSubscriptionCache(dir).load(), "испорченный кэш — это «нет кэша», не падение")
    }

    @Test
    fun `запись атомарна — при повторном сохранении не остаётся обрывка`() {
        val store = FileSubscriptionCache(dir)
        store.save(listOf(id = "первый"))

        store.save(listOf(id = "второй"))

        assertNotNull(store.load())
        assertEquals("второй", store.load()!!.configs[0].id)
        // Временный файл не остаётся после успешного переименования.
        assertEquals(false, File(dir, "config-cache.json.tmp").exists())
    }

    @Test
    fun `clear стирает кэш`() {
        val store = FileSubscriptionCache(dir)
        store.save(listOf(id = "x"))

        store.clear()

        assertNull(store.load())
    }

    private fun listOf(id: String) = ConfigList(
        chatId = 1L,
        configs = listOf(
            ConfigSummary(
                id = id,
                name = "n",
                countryCode = "NL",
                city = "c",
                startDateEpochSeconds = 0L,
                endDateEpochSeconds = 1_900_000_000L,
                status = SubscriptionStatus.ACTIVE,
            ),
        ),
    )
}
