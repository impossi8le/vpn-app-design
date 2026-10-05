package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.ProfileMeta
import com.impossi8le.vpnapp.domain.config.ProfileMetaStore

/** Метаданные в памяти. Для тестов, которым важен не файл, а факт записи. */
class FakeProfileMetaStore(initial: ProfileMeta? = null) : ProfileMetaStore {
    var value: ProfileMeta? = initial
        private set
    var saveCount: Int = 0
        private set

    override fun load(): ProfileMeta? = value

    override fun save(meta: ProfileMeta) {
        value = meta
        saveCount++
    }

    override fun clear() {
        value = null
    }
}
