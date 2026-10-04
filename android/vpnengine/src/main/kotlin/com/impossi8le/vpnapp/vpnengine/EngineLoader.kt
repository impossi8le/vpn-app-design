package com.impossi8le.vpnapp.vpnengine

/**
 * Загрузка нативной библиотеки ядра.
 *
 * **Зачем отдельный объект, а не вызов на месте.** Сгенерированные SWIG-классы
 * содержат статический блок, который зовёт `swig_module_init()`, но НЕ зовут
 * `System.loadLibrary` — проверить это можно по `ovpncliJNI.java`: там есть
 * только `private final static native void swig_module_init()` в статическом
 * блоке. Библиотеку обязан загрузить вызывающий. Если этого не сделать, первое
 * же обращение к любому классу ядра падает с `UnsatisfiedLinkError`, причём уже
 * в момент выполнения, а не сборки.
 *
 * **Почему `loadLibrary`, а не `load`.** Android пакует в APK только файлы
 * `lib<имя>.so` и достаёт их из архива сам; `load` потребовал бы распаковывать
 * библиотеку вручную в записываемый каталог. Поэтому в CMake стоит `PREFIX "lib"`,
 * и имя для `loadLibrary` — `ovpn3`, без префикса и расширения.
 *
 * **Почему загрузка ровно один раз и с сохранением ошибки.** `System.loadLibrary`
 * при повторном вызове для той же библиотеки — no-op, а вот исключение при
 * первой попытке повторять бессмысленно: причина не изменится. Сохранённая
 * ошибка даёт внятное сообщение вместо невнятного падения в другом месте.
 */
object EngineLoader {

    /** Имя без `lib` и без `.so`: ровно то, что ждёт `System.loadLibrary`. */
    private const val LIBRARY_NAME = "ovpn3"

    private var loadFailure: Throwable? = null
    private var loaded = false

    /**
     * Загрузить ядро, если ещё не загружено.
     *
     * @return успех загрузки. Ошибка не глотается: её показывают вызывающему,
     *   чтобы он мог честно сказать пользователю, что движок недоступен, вместо
     *   попытки работать с незагруженным ядром.
     */
    @Synchronized
    fun load(): Boolean {
        if (loaded) return true
        loadFailure?.let { return false }

        return try {
            System.loadLibrary(LIBRARY_NAME)
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            // Самая частая причина — библиотеки нет в APK для этой архитектуры.
            // Запоминаем, чтобы не повторять попытку и не терять причину.
            loadFailure = e
            false
        }
    }

    /** Загружено ли ядро. Нужно до вызова любых классов `net.openvpn.ovpn3`. */
    val isLoaded: Boolean get() = loaded

    /**
     * Причина неудачи загрузки, если она была.
     *
     * Позволяет показать пользователю осмысленное сообщение: «движок недоступен
     * на этом устройстве» вместо падения с `UnsatisfiedLinkError`.
     */
    val failureReason: String?
        get() = loadFailure?.let { "не удалось загрузить libovpn3.so: ${it.message}" }
}
