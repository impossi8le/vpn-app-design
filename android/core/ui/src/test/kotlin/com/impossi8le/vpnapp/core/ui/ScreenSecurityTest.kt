package com.impossi8le.vpnapp.core.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Защита экранов проверяется как данные.
 *
 * Смысл: требование «на этом экране не должно быть скриншотов» легко забыть при
 * добавлении экрана, и забывается оно молча. Здесь оно зафиксировано, и новый
 * экран обязан получить явное решение, а не унаследовать отсутствие защиты.
 */
class ScreenSecurityTest {

    @Test
    fun `экран ввода кода из бота защищён`() {
        // На экране device_nonce, который замыкает подтверждение входа.
        assertTrue(ScreenSecurity.requiresSecureFlag(SecureScreen.NONCE_ENTRY))
    }

    @Test
    fun `экран профиля защищён`() {
        // Внутри профиля лежит приватный ключ.
        assertTrue(ScreenSecurity.requiresSecureFlag(SecureScreen.PROFILE_VIEW))
    }

    @Test
    fun `главный экран и список подключений не защищаются`() {
        // Секретов там нет, а запрет скриншотов мешал бы показать состояние
        // поддержке. Защита всего подряд обесценивает защиту как сигнал.
        assertFalse(ScreenSecurity.requiresSecureFlag(SecureScreen.HOME))
        assertFalse(ScreenSecurity.requiresSecureFlag(SecureScreen.CONFIGS))
    }

    @Test
    fun `на защищённых экранах содержимое не логируется`() {
        // Флаг прячет окно от съёмки, но не мешает напечатать содержимое в лог.
        assertFalse(ScreenSecurity.mayLogContent(SecureScreen.NONCE_ENTRY))
        assertFalse(ScreenSecurity.mayLogContent(SecureScreen.PROFILE_VIEW))
    }

    @Test
    fun `на незащищённых экранах логирование допустимо`() {
        assertTrue(ScreenSecurity.mayLogContent(SecureScreen.HOME))
        assertTrue(ScreenSecurity.mayLogContent(SecureScreen.CONFIGS))
    }

    @ParameterizedTest(name = "у {0} есть явное решение")
    @EnumSource(SecureScreen::class)
    fun `у каждого экрана есть явное решение по защите`(screen: SecureScreen) {
        // Вызов просто обязан завершиться: если кто-то добавит экран и забудет
        // ветку в when, компилятор потребует решения — а тест докажет, что оно
        // вообще возвращается.
        ScreenSecurity.requiresSecureFlag(screen)
        ScreenSecurity.mayLogContent(screen)
    }

    @Test
    fun `набор защищённых экранов содержит ровно секретные`() {
        assertEquals(
            setOf(SecureScreen.NONCE_ENTRY, SecureScreen.PROFILE_VIEW),
            ScreenSecurity.secureScreens,
        )
    }
}
