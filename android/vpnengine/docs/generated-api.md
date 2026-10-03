# Сгенерированный Java-API ядра OpenVPN 3

Справка составлена **по собранным классам**, а не по исходникам C++: имена и
сигнатуры выписаны из `net.openvpn.ovpn3`, которые выдал SWIG. Нужна, чтобы не
гадать о форме API при написании Kotlin-обёртки — на догадках уже был написан и
выброшен один вариант.

Источник: артефакт `ovpn3-engine-arm64` прогона спайка, 42 класса + `ovpn3.so`.

## Связь с VpnService — главное

```java
public class ClientAPI_OpenVPNClient
        extends ClientAPI_TunBuilderBase          // ← вот это ключевое
        implements LogReceiverSwigInterface
```

Клиент **сам является** реализацией `TunBuilderBase`. То есть мост к
`VpnService` пишется не отдельным объектом, а **наследованием от
`ClientAPI_OpenVPNClient`** с переопределением методов `tun_builder_*`.
Композиция здесь не сработала бы: вызовы идут из C++ в Java через виртуальную
таблицу (в SWIG включены `directors="1"`).

## `ClientAPI_TunBuilderBase` — методы, которые надо переопределить

Именно эти вызовы ядро делает, когда хочет поднять интерфейс:

| Метод | Смысл |
|---|---|
| `boolean tun_builder_new()` | Начать настройку: сбросить состояние |
| `boolean tun_builder_set_layer(int layer)` | Уровень (3 = сетевой) |
| `boolean tun_builder_set_remote_address(String address, boolean ipv6)` | Адрес сервера |
| `boolean tun_builder_add_address(String address, int prefix_length, String gateway, boolean ipv6, boolean net30)` | Адрес интерфейса |
| `boolean tun_builder_set_route_metric_default(int metric)` | Метрика маршрута по умолчанию |
| `boolean tun_builder_reroute_gw(boolean ipv4, boolean ipv6, long flags)` | **Перехват всего трафика** |
| `boolean tun_builder_add_route(String address, int prefix_length, int metric, boolean ipv6)` | Маршрут |
| `boolean tun_builder_exclude_route(String address, int prefix_length, int metric, boolean ipv6)` | Исключение из туннеля |
| `boolean tun_builder_set_dns_options(DnsOptions dns)` | **DNS из профиля** |
| `boolean tun_builder_set_mtu(int mtu)` | MTU |
| `boolean tun_builder_set_session_name(String name)` | Имя сессии |
| `boolean tun_builder_set_allow_family(int af, boolean allow)` | Разрешить семейство адресов |
| `boolean tun_builder_set_allow_local_dns(boolean allow)` | Локальный DNS — **оставлять false** |
| `int tun_builder_establish()` | **Вернуть дескриптор из `VpnService`** |
| `boolean tun_builder_persist()` | Просить сохранять интерфейс |

**Про `tun_builder_establish()`:** возвращает `int`, и это файловый дескриптор
туннеля. Получить его можно только у `VpnService.Builder.establish()`, то есть
метод требует живого сервиса. В тесте его не подделать — как и `Builder`.

**Про `tun_builder_reroute_gw`:** это и есть `redirect-gateway` из профиля.
Флаги приходят из ядра, и именно здесь решается, пойдёт ли весь трафик в
туннель. Наша проверка защиты (§6) обязана подтвердить результат отдельно —
ядро сообщает о своём намерении, а не о факте.

**Про `tun_builder_set_allow_local_dns(false)`:** локальный DNS означает запросы
мимо туннеля. Это тот же класс утечки, что и незакрытый IPv6.

## `ClientAPI_OpenVPNClientHelper` — разбор профиля

```java
public ClientAPI_OpenVPNClientHelper()
public ClientAPI_EvalConfig eval_config(ClientAPI_Config config)
public ClientAPI_MergeConfig merge_config(String path, boolean follow_references)
public ClientAPI_MergeConfig merge_config_string(String config_content)
public static int max_profile_size()
public static boolean parse_dynamic_challenge(String cookie, ClientAPI_DynamicChallenge dc)
public String crypto_self_test()
public static String platform()
public static String copyright()
```

Для нас важны два: `eval_config` (годен ли профиль, какие серверы, нужен ли
пароль) и `merge_config_string` (развернуть встроенные блоки в единый профиль).

## `ClientAPI_OpenVPNClient` — жизненный цикл

```java
public ClientAPI_OpenVPNClient()
public ClientAPI_EvalConfig eval_config(ClientAPI_Config arg0)
public ClientAPI_Status provide_creds(ClientAPI_ProvideCreds arg0)
public boolean socket_protect(int socket, String remote, boolean ipv6)
public ClientAPI_Status connect()
public ClientAPI_ConnectionInfo connection_info()
public boolean session_token(ClientAPI_SessionToken tok)
public void stop()
public void pause(String reason)
public void resume()
public void reconnect(int seconds)
public boolean pause_on_connection_timeout()
```

`socket_protect` — критично: без него сокет ядра может уйти мимо туннеля, и
это утечка. На Android за ним стоит `VpnService.protect(fd)`.

## `ClientAPI_Config` — и чего в нём НЕТ

```java
public void setContent(String value)              // текст профиля
public void setProtoOverride(String value)
public void setProtoVersionOverride(int value)
public void setAllowUnusedAddrFamilies(String value)
public void setCompressionMode(String value)
public void setExternalPkiAlias(String value)
public void setContentList(...)                   // вектор KeyValue
public void setPeerInfo(...)
```

**Сеттера уровня логирования в этом классе НЕТ.** Проверено поиском по
сгенерированному файлу: `setVerb`/`Verb` не встречается ни разу. Ядро читает
`verb` **из текста профиля**, поэтому боевой `verb 3` доходит до него дословно
и включает печать тел PEM — приватного ключа — в logcat.

Следствие для реализации: перед `setContent` текст обязан пройти
`ProfileSanitizer.sanitizeVerb` (core:domain). Это не подстраховка, а
единственная защита: настройки в конфиге для этого не существует.

## `ClientAPI_EvalConfig` — что можно узнать о профиле

`getError`, `getMessage`, `getUserlockedUsername`, `getProfileName`,
`getFriendlyName`, `getAutologin`, `getExternalPki`, `getVpnCa`,
`getStaticChallenge`, `getPrivateKeyPasswordRequired`, `getAllowPasswordSave`,
`getRemoteHost`, `getRemotePort`, `getRemoteProto`, `getWindowsDriver`,
`getDcoCompatible`, `getDcoIncompatibilityReason`.

`getPrivateKeyPasswordRequired` важен: боевой профиль может требовать пароль к
ключу, и это надо узнать **до** подключения, а не в момент ошибки.

## `ClientAPI_Status` — результат операций

`getError`, `getStatus`, `getMessage`. `error == false` — успех.

## `ClientAPI_Event` — события ядра

`getError`, `getFatal`, `getName`, `getInfo`. Различие `error` и `fatal`
существенно: `fatal` означает разрыв, `error` — некритичный сбой.

## `LogInfo` / `LogReceiver`

`LogInfo.getText()` — строка лога. `ClientAPI_OpenVPNClient` реализует
`LogReceiverSwigInterface`, поэтому лог перехватывается переопределением
`log(ClientAPI_LogInfo)`.

**Логи ядра не должны уходить в logcat без фильтра:** даже при `verb 1` туда
попадают адреса серверов и имена профилей, а при `verb >= 3` — тела PEM. Строки
надо пропускать через проверку перед записью.
