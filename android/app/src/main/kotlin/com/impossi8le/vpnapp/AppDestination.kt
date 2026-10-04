package com.impossi8le.vpnapp

/**
 * Куда ведёт пользователя приложение.
 *
 * Экраны перечислены по `docs/design/mockup.html` — восемнадцать состояний,
 * включая отказные. Отказные здесь так же важны, как рабочие: без них
 * пользователь упирается в тупик, и именно такие тупики находились на ревью
 * макета (после «Не разрешать» приложение молчало, а системный диалог больше не
 * показывается).
 *
 * **Состояние подключения здесь НЕ является отдельными пунктами навигации.**
 * «Подключение», «Проверка защиты», «Подключено», «Ошибка» — это один экран
 * `Connection`, который меняет вид по `ConnectionStatus`. Разводить их по
 * маршрутам значило бы дублировать переходы и рассинхронизировать их с
 * состоянием туннеля: маршрут можно выставить неверно, а состояние приходит от
 * сервиса.
 *
 * Навигация без библиотеки: маршрутов немного, а `navigation-compose` приносит
 * транзакции бэкстека, аргументы и сохранение состояния, из которых ничего не
 * нужно. Собственный стек — двадцать строк, и его видно целиком.
 */
sealed interface AppDestination {

    /** Проверка сохранённой сессии при запуске. */
    data object Startup : AppDestination

    /** Вход через Telegram. */
    data object Login : AppDestination

    /** Ждём подтверждения входа в боте. */
    data object LoginWaiting : AppDestination

    /**
     * Главный экран: состояния подключения.
     *
     * Один маршрут на восемь состояний — см. док-комментарий выше.
     */
    data object Connection : AppDestination

    /** Аккаунт: данные, тумблеры, поддержка, выход. */
    data object Account : AppDestination

    /** Подписки нет: этот Telegram не привязан. */
    data object NoSubscription : AppDestination

    /** Доступ к стране отозван сервером. */
    data object AccessRevoked : AppDestination

    /** Как включить VPN вручную, если в разрешении отказали. */
    data object HowToEnableVpn : AppDestination

    /** Подробнее о сборке: она перестанет работать в указанную дату. */
    data object BuildExpiry : AppDestination

    /** О сервисе. */
    data object About : AppDestination

    /** Демонстрация интерфейса без подписки. */
    data object Demo : AppDestination
}

/**
 * Стек навигации.
 *
 * Свой вместо библиотеки, потому что нужен ровно один вид движения: вперёд по
 * действию пользователя и назад по системной кнопке. Возврат из Telegram — это
 * `onResume`, а не пункт стека, и он обрабатывается в `AuthViewModel`.
 */
class NavigationStack(initial: AppDestination) {

    private val back = ArrayDeque<AppDestination>()
    private var current: AppDestination = initial

    /** Где мы сейчас. */
    fun current(): AppDestination = current

    /**
     * Перейти вперёд.
     *
     * Одинаковые переходы подряд отбрасываются: двойной тап по пункту меню
     * создавал бы два уровня, и «назад» пришлось бы нажимать дважды — это
     * ловилось на ревью макета.
     */
    fun push(destination: AppDestination) {
        if (current == destination) return
        back.addLast(current)
        current = destination
    }

    /**
     * Шаг назад.
     *
     * Возвращает `false`, если возвращаться некуда: экран решает, отдать ли
     * событие системе (закрыть приложение) — вместо того чтобы вести себя как
     * кнопка «Назад», которая молча ничего не делает.
     */
    fun pop(): Boolean {
        if (back.isEmpty()) return false
        current = back.removeLast()
        return true
    }

    /** Сбросить историю: после входа возврат на экран входа бессмысленен. */
    fun resetTo(destination: AppDestination) {
        back.clear()
        current = destination
    }
}