package com.impossi8le.vpnapp.domain.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProfileReuseTest {

    private val meta = ProfileMeta(
        configId = "GEclient94",
        version = "2026-10-02T12:45:56Z",
        endDateEpochSeconds = 1_000_000L,
    )

    @Test
    fun `профиля нет — нужен запрос`() {
        assertEquals(
            ProfileUse.Missing,
            decideProfileUse(hasProfile = false, meta = meta, requestedConfigId = "GEclient94", nowEpochSeconds = 0L),
        )
    }

    @Test
    fun `метаданных нет — нужен запрос`() {
        assertEquals(
            ProfileUse.Missing,
            decideProfileUse(hasProfile = true, meta = null, requestedConfigId = "GEclient94", nowEpochSeconds = 0L),
        )
    }

    @Test
    fun `другой конфиг — нужен запрос`() {
        assertEquals(
            ProfileUse.Missing,
            decideProfileUse(hasProfile = true, meta = meta, requestedConfigId = "FIclient07", nowEpochSeconds = 0L),
        )
    }

    @Test
    fun `срок вышел — профиль недействителен`() {
        assertEquals(
            ProfileUse.Expired,
            decideProfileUse(hasProfile = true, meta = meta, requestedConfigId = "GEclient94", nowEpochSeconds = 1_000_000L),
        )
    }

    @Test
    fun `срок впереди — профиль годится повторно`() {
        assertEquals(
            ProfileUse.Reusable,
            decideProfileUse(hasProfile = true, meta = meta, requestedConfigId = "GEclient94", nowEpochSeconds = 999_999L),
        )
    }
}
