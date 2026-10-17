# Smart DNS Switcher for Android — план реализации

> Контекст зафиксирован по состоянию на Android 17 / API 37, октябрь 2026.
> Целевое устройство: Pixel на Android 17.
>
> Цель документа — сохранить постановку задачи, принятые архитектурные решения,
> требования к разрешениям и план разработки, чтобы к проекту можно было вернуться
> в другом чате без восстановления контекста с нуля.

## 1. Постановка проблемы

На телефоне настроен Android **Private DNS** в режиме конкретного провайдера
(`Private DNS provider hostname`). Это DoT (DNS-over-TLS) к собственному Smart DNS.

Smart DNS нужен в следующих сетях:

- мобильная сеть;
- публичные Wi‑Fi;
- любые другие сети, где нет домашнего прокси/Podkop.

Но в домашней сети используется OpenWrt + Podkop. Podkop для доменной маршрутизации
должен видеть DNS-запросы клиента / использовать свой FakeIP-механизм. Когда Android
Private DNS включён, телефон резолвит DNS напрямую через DoT и обходит DNS Podkop.

Фактически наблюдалось:

- Instagram работает;
- в YouTube загружаются превью, но не видео;
- в X/Twitter загружается текст, но не часть медиа;
- часть сайтов из proxy ruleset работает;
- если вручную выключить Android Private DNS — всё начинает работать нормально.

Практическая цель:

```text
Состояние сети изменилось
        |
        +-- Wi‑Fi -> получить SSID
        |             |
        |             +-- SSID есть в whitelist -> Private DNS OFF
        |             |
        |             +-- SSID не в whitelist  -> Private DNS ON
        |
        +-- mobile / Ethernet / другая сеть / Wi‑Fi не из whitelist
                      -> Private DNS ON
```

Дополнительное требование: иметь плитку **Quick Settings** в шторке для:

- отображения реального текущего состояния Private DNS;
- ручного переключения ON/OFF;
- желательно отображения `Auto / On / Off` или хотя бы `Smart DNS: On/Off`.

---

## 2. Почему конфликтует Private DNS и Podkop

Android Private DNS в режиме `hostname` отправляет DNS-запросы напрямую указанному
DoT-серверу. Роутер видит TLS-соединение с DNS-сервером, но не сами DNS-запросы.

Podkop в такой ситуации не получает доменную информацию, на которой завязана часть его
маршрутизации/FakeIP. Поэтому разные компоненты одного сайта могут вести себя по-разному:
HTML/API/превью доступны, а отдельные CDN для видео или медиа уходят не тем маршрутом.

Это было подтверждено экспериментально: **Private DNS OFF в домашнем Wi‑Fi полностью
устраняет проблему**.

---

## 3. Рассмотренные варианты

### 3.1. Полностью отказаться от Smart DNS

Отвергнуто.

Smart DNS нужен именно вне дома: мобильная сеть и публичные Wi‑Fi.

---

### 3.2. Использовать Smart DNS как upstream DNS Podkop дома

Технически возможно и остаётся хорошим вариантом для роутера, но не решает исходную
задачу полностью: Android Private DNS всё равно нужно каким-то образом переключать между
домашней сетью и остальными сетями.

Кроме того, текущая желаемая семантика проще:

- дома Android Private DNS выключен;
- вне дома Android Private DNS включён.

---

### 3.3. Tasker + Shizuku

Работает, но отвергнуто как избыточное решение.

Недостатки:

- Tasker — отдельное постоянно установленное приложение;
- автоматизация исполняется его сервисом;
- Shizuku без root после перезагрузки нужно снова запускать;
- функциональность проекта очень мала, поэтому универсальный automation framework
  выглядит тяжелее необходимого.

---

### 3.4. Только ручная Quick Settings tile

Очень простой и хороший fallback:

```text
нажал tile -> private_dns_mode = off / hostname
```

Плюсы:

- нет постоянного процесса;
- почти нулевая сложность;
- нет мониторинга Wi‑Fi.

Минус:

- легко забыть включить Smart DNS после ухода из дома;
- теряется основная цель автоматизации.

Плитку всё равно стоит оставить как ручной override и индикатор состояния.

---

### 3.5. `ConnectivityManager.NetworkCallback` без foreground service

Сам callback очень лёгкий и событийный, без polling.

Проблема: регистрация callback существует только пока жив процесс приложения. Android
может убить процесс, после чего приложение перестанет узнавать о смене сети.

Это особенно опасный failure mode:

```text
дома -> DNS OFF
Android убил процесс
пользователь вышел из дома
-> DNS остался OFF
```

Для best-effort приложения это приемлемо, для сетевой автоматики — нет.

---

### 3.6. Старый manifest `BroadcastReceiver` на смену Wi‑Fi

Не подходит современному Android.

Старые implicit connectivity broadcasts для manifest receivers ограничены, поэтому
нельзя рассчитывать на модель «процесс всегда мёртв, а система запускает его при каждой
смене Wi‑Fi».

---

### 3.7. Постоянный foreground service

**Выбранный вариант.**

Foreground service живёт постоянно, но не делает polling:

```text
ForegroundService
      |
      +-- один ConnectivityManager.NetworkCallback
      |
      +-- событие -> пересчитать policy -> при необходимости поменять DNS
```

В idle:

- нет таймера;
- нет Wi‑Fi scanning;
- нет wake lock;
- нет сетевого трафика приложения;
- callback ждёт системных событий.

Поэтому CPU/battery overhead должен быть очень мал. Основная цена — сам постоянно живой
процесс и системный статус foreground service.

---

## 4. Что делать с уведомлением foreground service

### Важный результат

На Android 13+ приложение **не обязано получать `POST_NOTIFICATIONS`, чтобы запустить
foreground service**.

FGS всё равно обязан вызвать `startForeground()` с объектом `Notification`, но если
уведомления приложению запрещены, Android:

- **не показывает обычную карточку FGS-уведомления в notification drawer**;
- всё равно показывает приложение в системном **Task Manager / Active apps**.

На AOSP/Pixel `Active apps` доступен из нижней части шторки и показывает число/список
приложений с активными foreground services.

Это максимально близко к желаемому UX:

```text
обычная шторка:
  нет постоянной карточки "Smart DNS service is running"

нижняя часть шторки:
  Active apps (1/2/...)
      -> Smart DNS Switcher
```

Точная форма элемента (`овал`, `chip`, кнопка и т.п.) контролируется SystemUI и может
меняться между версиями Android; приложение не может потребовать конкретный внешний вид.

### Рекомендуемая реализация

**Вообще не запрашивать `POST_NOTIFICATIONS`.**

Можно даже не объявлять его в manifest, если приложению не нужны никакие другие
уведомления.

При этом service всё равно создаёт NotificationChannel и Notification и передаёт её в
`startForeground()`. Android использует её для жизненного цикла FGS, но не показывает
обычной карточкой при отсутствии notification permission.

Fallback: если захочется обычное уведомление, добавить `POST_NOTIFICATIONS`, а канал
сделать silent + low importance.

Официальные источники:

- Notification permission / FGS:
  https://developer.android.com/develop/ui/compose/notifications/notification-permission
- Active apps / Foreground Service Task Manager:
  https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping

---

## 5. Архитектура приложения

Предлагаемая структура:

```text
app/
  MainActivity
  DnsPolicyService
  DnsController
  NetworkMonitor
  TrustedWifiRepository
  DnsTileService
  BootReceiver
```

### `DnsController`

Единственная ответственность — читать и менять состояние Private DNS.

Используем:

```text
Settings.Global:
  private_dns_mode
  private_dns_specifier
```

Нужные режимы:

```text
off       -> Private DNS выключен
hostname  -> использовать сохранённый private_dns_specifier
```

`private_dns_specifier` менять при каждом переключении не нужно. Android специально
сохраняет hostname отдельно, чтобы временное выключение Private DNS не требовало вводить
провайдера заново.

Псевдокод:

```kotlin
enum class PrivateDnsState {
    OFF,
    HOSTNAME,
    OTHER
}

fun enableSmartDns() {
    Settings.Global.putString(
        contentResolver,
        "private_dns_mode",
        "hostname"
    )
}

fun disableSmartDns() {
    Settings.Global.putString(
        contentResolver,
        "private_dns_mode",
        "off"
    )
}
```

При старте нужно убедиться, что `private_dns_specifier` непустой. Если он пуст — показать
ошибку в UI и не включать `hostname`.

---

### `TrustedWifiRepository`

Хранит whitelist SSID.

Для маленького личного приложения достаточно DataStore Preferences.

Например:

```text
trusted_ssids = {
  "Home",
  "Dacha"
}
```

UI:

```text
Trusted Wi‑Fi

[x] Home
[x] Dacha

[ Add current Wi‑Fi ]
```

SSID лучше хранить после нормализации без окружающих кавычек, которые Android иногда
возвращает из `WifiInfo.getSSID()`.

---

### `NetworkMonitor`

Использовать современный API:

```text
ConnectivityManager.NetworkCallback
```

и получать Wi‑Fi информацию из:

```text
NetworkCapabilities.transportInfo as? WifiInfo
```

`WifiManager.getConnectionInfo()` deprecated начиная с Android 12 и не нужен.

Для SSID в callback на Android 12+ нужно учитывать location-sensitive redaction.
`NetworkCallback` следует создавать с:

```kotlin
ConnectivityManager.NetworkCallback(
    ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
)
```

Иначе location-sensitive поля `WifiInfo`, включая SSID, могут быть вырезаны.

Обрабатывать минимум:

```text
onAvailable()
onCapabilitiesChanged()
onLost()
```

Но policy не стоит рассчитывать по отдельным событиям вручную. Лучше после любого
релевантного callback вызывать один `reconcile()`:

```kotlin
fun reconcile() {
    val state = readCurrentDefaultNetwork()

    when {
        state.isWifi && state.ssid in whitelist ->
            dnsController.disableSmartDns()

        else ->
            dnsController.enableSmartDns()
    }
}
```

Это делает обработку идемпотентной и устойчивой к последовательности callback'ов.

---

### Важное правило: смотреть на реальную физическую сеть, а не VPN как default network

Если в будущем на телефоне одновременно используется VPN/v2rayNG, default network с точки
зрения приложения может иметь `TRANSPORT_VPN`.

Поэтому NetworkMonitor должен корректно определять underlying Wi‑Fi / cellular либо
подписываться на Wi‑Fi network отдельно, а не просто делать:

```text
default network == Wi‑Fi ?
```

Практический вариант:

1. зарегистрировать callback на Wi‑Fi transport;
2. хранить набор currently available Wi‑Fi networks;
3. для Wi‑Fi получать SSID;
4. если есть подключённая trusted Wi‑Fi network -> DNS OFF;
5. если trusted Wi‑Fi нет -> DNS ON.

Это лучше соответствует требованию:

```text
trusted Wi‑Fi имеет приоритет независимо от наличия VPN
```

---

## 6. Policy / state machine

Нужна одна простая политика:

```text
trusted Wi‑Fi connected?
    yes -> desired DNS = OFF
    no  -> desired DNS = HOSTNAME
```

То есть:

| Сеть | Private DNS |
|---|---|
| Home Wi‑Fi | OFF |
| Dacha Wi‑Fi | OFF |
| Public Wi‑Fi | HOSTNAME |
| Mobile | HOSTNAME |
| Нет сети | HOSTNAME |
| VPN поверх Home Wi‑Fi | OFF |
| VPN поверх mobile/public Wi‑Fi | HOSTNAME |

Почему при отсутствии сети лучше держать `HOSTNAME`:

если пользователь выключил Wi‑Fi дома и телефон переходит на LTE, Private DNS уже должен
быть включён, а не ждать следующего успешного соединения.

---

## 7. Quick Settings tile

`DnsTileService : TileService`.

Плитка должна читать **реальный** `Settings.Global.private_dns_mode`, а не локальный state
приложения.

Например:

```text
ACTIVE:
  label    = "Smart DNS"
  subtitle = "On"

INACTIVE:
  label    = "Smart DNS"
  subtitle = "Off"
```

Нажатие:

```text
если mode == hostname -> off
иначе                 -> hostname
```

После ручного изменения есть два возможных UX.

### Вариант A — manual override до следующей смены сети

Рекомендуется.

Нажатие плитки меняет DNS немедленно. Автоматический policy не вмешивается до следующего
network callback.

Например дома:

```text
Auto решил OFF
пользователь нажал tile -> ON
...
сеть изменилась / переподключилась
Auto снова применяет policy -> OFF
```

Это предсказуемо и не требует отдельного режима override.

### Вариант B — полноценные режимы Auto / On / Off

Более сложный UI:

```text
Auto
Force On
Force Off
```

Для первой версии не нужен.

---

## 8. Foreground service

Имя: `DnsPolicyService`.

Работа:

```text
onCreate():
  create notification channel
  startForeground(...)
  register NetworkCallback
  reconcile()

onDestroy():
  unregister callback
```

### Тип FGS

Начиная с Android 14 foreground service должен иметь declared type.

Для этой задачи ни один специализированный тип (`mediaPlayback`, `dataSync`,
`connectedDevice` и т.п.) корректно не подходит.

Наиболее соответствующий тип:

```xml
android:foregroundServiceType="specialUse"
```

Нужно также:

```xml
<uses-permission
    android:name="android.permission.FOREGROUND_SERVICE" />

<uses-permission
    android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

И внутри `<service>`:

```xml
<property
    android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
    android:value="Continuously monitors network transport/SSID changes to switch the user's Private DNS mode according to a local trusted-Wi-Fi policy." />
```

`specialUse` предназначен для корректных долгоживущих FGS use cases, которые не подходят
под другие типы. У него нет runtime prerequisites.

Текущие documented time limits для FGS относятся к `dataSync`, `mediaProcessing` и
`shortService`; `specialUse` не имеет их 6-часового/короткого лимита.

Для личного sideload APK Google Play review этого subtype не имеет значения. Если когда-то
публиковать приложение в Play, `specialUse` будет предметом policy review.

Источники:

- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/services/fgs/declare
- https://developer.android.com/develop/background-work/services/fgs/timeout

---

## 9. Автозапуск после reboot

Нужно:

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

и `BootReceiver` на:

```text
android.intent.action.BOOT_COMPLETED
android.intent.action.MY_PACKAGE_REPLACED
```

`BootReceiver` вызывает:

```kotlin
ContextCompat.startForegroundService(...)
```

`BOOT_COMPLETED` входит в исключения, при которых Android разрешает запуск FGS из
background.

Начиная с Android 15 запуск с boot запрещён для некоторых типов FGS (`dataSync`,
`camera`, `mediaPlayback`, `phoneCall`, `mediaProjection`, `microphone`), но `specialUse`
в опубликованном списке запретов отсутствует.

В актуальном списке Android 17 нет отдельного изменения, запрещающего этот сценарий.

Тем не менее автозапуск после reboot — обязательный тест на реальном Pixel/Android 17.

Источники:

- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/about/versions/15/behavior-changes-15
- https://developer.android.com/about/versions/17/behavior-changes-17

---

## 10. Разрешения — итоговая проверка

### Обязательные обычные permissions

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />

<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

`ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, FGS permissions и `RECEIVE_BOOT_COMPLETED`
не требуют root.

---

### Доступ к SSID

SSID Android считает **location-sensitive information**.

`WifiInfo` документация прямо говорит, что доступ к location-sensitive полям, включая
`getSSID()`, требует тех же permissions, что и `WifiManager.getScanResults()`.

Для приложений с современным target SDK `getScanResults()` требует:

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

и включённые системные Location services.

Поэтому для первой надёжной реализации whitelist именно по SSID надо исходить из того,
что потребуется runtime grant **Precise location**.

Также callback создаётся с:

```text
FLAG_INCLUDE_LOCATION_INFO
```

иначе Android 12+ по умолчанию редактирует location-sensitive данные в
`NetworkCapabilities`.

#### А нужен ли `NEARBY_WIFI_DEVICES`?

Для собственно чтения SSID через `WifiInfo` официальный API reference отсылает к
permissions `getScanResults()`, то есть к `ACCESS_FINE_LOCATION + ACCESS_WIFI_STATE`.

`NEARBY_WIFI_DEVICES` нужен для ряда Wi‑Fi management/discovery API, но **не стоит
рассчитывать, что он заменит Fine Location именно для `WifiInfo.getSSID()`**.

Мы не сканируем сети и не управляем Wi‑Fi, поэтому в MVP `NEARBY_WIFI_DEVICES` можно не
добавлять без необходимости.

#### Нужен ли `ACCESS_BACKGROUND_LOCATION`?

Это место нужно проверить на реальном Pixel.

Документация однозначно требует Fine Location + включённый Location для SSID и отдельно
говорит, что `FLAG_INCLUDE_LOCATION_INFO` проверяет location permission и Location toggle.
Но для конкретной комбинации:

```text
Android 17
+ постоянно работающий specialUse FGS
+ NetworkCallback
+ WifiInfo.getSSID()
```

документация не формулирует так же однозначно отдельное требование
`ACCESS_BACKGROUND_LOCATION`.

Поэтому план:

1. **не запрашивать `ACCESS_BACKGROUND_LOCATION` в первой версии**;
2. проверить SSID:
   - когда Activity открыта;
   - после выключения экрана;
   - через 10–30 минут;
   - после reboot, когда service стартовал через `BOOT_COMPLETED`;
3. если Android возвращает `UNKNOWN_SSID` только в background — отдельно исследовать и
   добавлять background location только если это реально необходимо.

Не следует заранее просить более сильное permission без необходимости.

Источники:

- WifiInfo:
  https://developer.android.com/reference/android/net/wifi/WifiInfo
- NetworkCallback / `FLAG_INCLUDE_LOCATION_INFO`:
  https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback
- Wi‑Fi scan permission requirements:
  https://developer.android.com/develop/connectivity/wifi/wifi-scan
- Nearby Wi‑Fi permissions:
  https://developer.android.com/develop/connectivity/wifi/wifi-permissions

---

### Разрешение на изменение Private DNS

Ключевое permission:

```xml
<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS" />
```

В AOSP оно имеет protection level:

```text
signature | privileged | development | role | installer
```

Обычному стороннему APK Android сам его через runtime dialog не выдаст.

Но `development` permission можно один раз выдать через ADB shell:

```bash
adb shell pm grant <package.name> android.permission.WRITE_SECURE_SETTINGS
```

Например:

```bash
adb shell pm grant dev.example.smartdnsswitcher \
    android.permission.WRITE_SECURE_SETTINGS
```

После этого приложение самостоятельно пишет `Settings.Global`.

### Root нужен?

**Нет.**

Нужен только одноразовый ADB grant после установки APK.

После grant:

- root не нужен;
- Shizuku не нужен;
- ADB не должен оставаться подключён;
- Developer options не участвуют в ежедневной работе приложения.

После обычного reboot permission сохраняется.

После uninstall + новой установки нужно выдать permission снова.

При обычном обновлении APK с тем же package/signature grant обычно сохраняется, но это
нужно включить в regression test.

AOSP источники:

- объявление `WRITE_SECURE_SETTINGS`:
  https://android.googlesource.com/platform/frameworks/base/+/main/core/res/AndroidManifest.xml
- `Settings.Global.putString()` требует `WRITE_SECURE_SETTINGS`:
  https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/provider/Settings.java
- `private_dns_mode` / `private_dns_specifier`:
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-dev/core/java/android/provider/Settings.java

---

## 11. `POST_NOTIFICATIONS` — намеренно НЕ нужен

Рекомендуемый manifest **не содержит**:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Android 13+ позволяет запускать FGS без этого runtime permission.

Приложение всё равно делает:

```kotlin
startForeground(notificationId, notification)
```

но при отсутствии notification permission пользователь не должен видеть FGS notice
обычной карточкой в notification drawer.

Приложение остаётся видимым в:

```text
Notification shade
  -> Active apps
```

То есть пользователь сохраняет системную возможность увидеть и принудительно остановить
foreground service.

Это сознательно выбранное поведение.

---

## 12. Пример итогового manifest

Это именно набросок, не финальный compile-ready manifest:

```xml
<manifest ...>

    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />

    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS" />

    <!-- POST_NOTIFICATIONS intentionally omitted -->

    <application ...>

        <service
            android:name=".DnsPolicyService"
            android:exported="false"
            android:foregroundServiceType="specialUse">

            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Monitor current Wi-Fi identity and switch Private DNS according to the user's trusted-Wi-Fi policy." />
        </service>

        <service
            android:name=".DnsTileService"
            android:exported="true"
            android:label="Smart DNS"
            android:icon="@drawable/ic_dns"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">

            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE" />
            </intent-filter>

            <meta-data
                android:name="android.service.quicksettings.TOGGLEABLE_TILE"
                android:value="true" />
        </service>

        <receiver
            android:name=".BootReceiver"
            android:enabled="true"
            android:exported="false">

            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>

    </application>

</manifest>
```

---

## 13. UI первой версии

Одна Activity:

```text
Smart DNS Switcher

Service
  [ ON ]

Private DNS
  Provider: dns.example.com
  Current state: ON

Automatic switching
  [ ON ]

Trusted Wi‑Fi
  ✓ Home
  ✓ Dacha

  [ Add current Wi‑Fi ]

Permissions
  ✓ WRITE_SECURE_SETTINGS
  ✓ Location
  ✓ Foreground service

Quick Settings
  [ Add tile ]
```

Можно добавить кнопку:

```text
[ Re-evaluate now ]
```

которая вызывает `reconcile()`.

---

## 14. Поведение при ошибках

### Нет `WRITE_SECURE_SETTINGS`

Не пытаться циклически менять настройку.

UI:

```text
System permission required

adb shell pm grant ...
```

Service можно не запускать до grant.

---

### Нет Fine Location / Location выключен / SSID redacted

**Fail-safe: DNS ON.**

Логика:

```text
не можем доказать, что это trusted Wi‑Fi
        ->
Private DNS = hostname
```

Почему так:

Smart DNS вне дома является желаемым безопасным default. Ошибка идентификации сети не
должна оставлять Private DNS выключенным на публичном Wi‑Fi.

---

### Provider hostname пуст

Не записывать `hostname`.

Показать setup error:

```text
Private DNS provider is not configured in Android settings.
```

---

### Пользователь вручную поменял Private DNS через Settings

Tile всегда читает реальное значение.

При следующем network event Auto policy снова применит желаемое состояние.

---

## 15. Логирование

Для личного приложения достаточно ring buffer последних 50–100 событий:

```text
12:01:03 SERVICE_STARTED
12:01:03 WIFI_AVAILABLE ssid=Home
12:01:03 POLICY trusted=true desired=OFF
12:01:03 DNS hostname -> off

13:15:48 WIFI_LOST ssid=Home
13:15:48 POLICY trusted=false desired=HOSTNAME
13:15:48 DNS off -> hostname
```

В UI:

```text
[ Copy diagnostics ]
```

Не писать пароли Wi‑Fi, BSSID и другую лишнюю информацию.

---

## 16. План реализации по шагам

### Этап 1 — минимальная ручная плитка

Реализовать:

- `DnsController`;
- чтение `private_dns_mode`;
- запись `off` / `hostname`;
- `DnsTileService`;
- экран проверки `WRITE_SECURE_SETTINGS`.

Проверить:

- ADB grant работает без root;
- tile корректно показывает фактическое состояние;
- переключение совпадает с Settings -> Private DNS;
- сохранённый provider возвращается после `off -> hostname`.

Это самый важный proof-of-concept.

---

### Этап 2 — чтение SSID

Реализовать:

- `NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)`;
- получение `WifiInfo` через `NetworkCapabilities.transportInfo`;
- нормализацию SSID;
- вывод текущего SSID в Activity.

Проверить permission matrix на Pixel Android 17:

- Activity foreground;
- Activity закрыта;
- экран выключен;
- service работает 30+ минут;
- после reboot.

До завершения этого теста **не добавлять `ACCESS_BACKGROUND_LOCATION`**.

---

### Этап 3 — whitelist + policy engine

Реализовать DataStore:

```text
trusted SSIDs
auto enabled
```

Реализовать идемпотентный:

```text
reconcile()
```

Правило:

```text
trusted Wi‑Fi -> OFF
всё остальное -> HOSTNAME
```

Добавить небольшой debounce (например 300–1000 ms), чтобы handover Wi‑Fi -> mobile не
создавал лишнюю серию записей, но correctness не должен зависеть от debounce.

---

### Этап 4 — foreground service

Добавить:

- `DnsPolicyService`;
- FGS type `specialUse`;
- callback registration;
- старт из Activity;
- корректный stop/restart;
- отсутствие `POST_NOTIFICATIONS`.

Проверить:

- нет обычной постоянной карточки уведомления;
- приложение присутствует в `Active apps`;
- Android не убивает service в нормальном режиме;
- battery usage минимальный.

---

### Этап 5 — reboot

Добавить `BootReceiver`.

Проверить:

```text
reboot дома
-> service starts
-> Home распознан
-> DNS OFF

reboot на mobile/public Wi‑Fi
-> service starts
-> DNS HOSTNAME
```

Особенно проверить получение SSID после boot без открытия Activity.

---

### Этап 6 — QS manual override

Интегрировать tile с Auto.

Первая версия:

```text
tap tile -> непосредственный toggle
следующее изменение сети -> Auto снова применяет policy
```

Tile subtitle желательно показывать:

```text
On
Off
```

или:

```text
On · Auto
Off · Home
```

если SystemUI позволяет нужный subtitle.

---

### Этап 7 — hardening

Тест-кейсы:

1. Home Wi‑Fi -> mobile.
2. Mobile -> Home Wi‑Fi.
3. Home -> public Wi‑Fi.
4. Public -> Home.
5. Wi‑Fi выключен вручную.
6. Airplane mode.
7. Captive portal.
8. VPN/v2rayNG поверх Home.
9. VPN/v2rayNG поверх mobile.
10. Reboot дома.
11. Reboot вне дома.
12. Location permission отозван.
13. Location toggle выключен.
14. `WRITE_SECURE_SETTINGS` отозван через ADB.
15. Android Private DNS provider вручную удалён.
16. App update поверх установленной версии.
17. Force stop приложения через `Active apps`.
18. После force stop — открыть приложение и убедиться, что policy восстанавливается.

---

## 17. Что НЕ нужно приложению

В текущем дизайне не нужны:

- root;
- VPN API;
- локальный VPN;
- Shizuku в runtime;
- Accessibility Service;
- Device Owner;
- polling;
- Wi‑Fi scanning;
- wake lock;
- INTERNET permission;
- CHANGE_WIFI_STATE;
- постоянное соединение с каким-либо сервером;
- Firebase;
- WorkManager для основного мониторинга.

---

## 18. Оценка сложности

Проект маленький.

Примерный объём:

```text
DnsController          ~50–100 LOC
NetworkMonitor         ~100–200 LOC
DnsPolicyService       ~100–150 LOC
DnsTileService         ~50–100 LOC
Whitelist/DataStore    ~100 LOC
MainActivity/UI        зависит от UI
BootReceiver           ~20 LOC
```

Основная сложность не в бизнес-логике, а в проверке Android permission/lifecycle edge
cases, особенно получения SSID после boot и в background на Android 17.

---

## 19. Рекомендуемая конечная архитектура

```text
                      Android SystemUI
                           |
                     Quick Settings
                           |
                       DnsTileService
                           |
                           v
+--------------------------------------------------+
|                Smart DNS Switcher                |
|                                                  |
|  DnsPolicyService (FGS specialUse)               |
|       |                                          |
|       +-- ConnectivityManager.NetworkCallback    |
|       |                                          |
|       +-- NetworkMonitor                         |
|       |      |                                   |
|       |      +-- Wi‑Fi + SSID                    |
|       |                                          |
|       +-- TrustedWifiRepository                  |
|       |                                          |
|       +-- reconcile()                            |
|              |                                   |
|              +-- trusted -> DNS OFF              |
|              +-- other   -> DNS HOSTNAME         |
|                         |                        |
|                         v                        |
|                   DnsController                  |
|                         |                        |
+-------------------------|------------------------+
                          |
                          v
              Settings.Global.private_dns_mode
```

FGS notification:

```text
POST_NOTIFICATIONS not granted / not declared
          |
          +-- no normal notification card
          |
          +-- service remains visible in
              SystemUI -> Active apps
```

---

## 20. Решение на текущий момент

Для первой версии выбрать:

- Kotlin;
- minSdk можно оставить современным (например 33+), если приложение только для личных
  Pixel;
- targetSdk 37;
- foreground service `specialUse`;
- `ConnectivityManager.NetworkCallback`;
- whitelist по SSID;
- `Settings.Global.private_dns_mode`;
- one-time `WRITE_SECURE_SETTINGS` grant через ADB;
- без root;
- без Shizuku;
- без `POST_NOTIFICATIONS`;
- Quick Settings tile;
- автозапуск через `BOOT_COMPLETED`;
- fail-safe при неизвестном SSID: **Private DNS ON**.

Главный эксперимент до полировки UI:

> Проверить, что `WifiInfo.getSSID()` остаётся доступным foreground service на Pixel /
> Android 17 после закрытия Activity и после boot с `ACCESS_FINE_LOCATION`,
> `FLAG_INCLUDE_LOCATION_INFO` и включённой системной Location.

Если это проходит — остальная архитектура прямолинейна.

---

## 21. Ключевые официальные источники

### Private DNS / системные настройки

- AOSP `WRITE_SECURE_SETTINGS`:
  https://android.googlesource.com/platform/frameworks/base/+/main/core/res/AndroidManifest.xml
- AOSP `Settings.java`:
  https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/provider/Settings.java
- AOSP Private DNS keys:
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-dev/core/java/android/provider/Settings.java

### Connectivity / Wi‑Fi

- Reading network state:
  https://developer.android.com/develop/connectivity/network-ops/reading-network-state
- `ConnectivityManager.NetworkCallback`:
  https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback
- `WifiInfo`:
  https://developer.android.com/reference/android/net/wifi/WifiInfo
- Wi‑Fi permissions:
  https://developer.android.com/develop/connectivity/wifi/wifi-permissions
- Wi‑Fi scan permission requirements:
  https://developer.android.com/develop/connectivity/wifi/wifi-scan

### Foreground service

- FGS types:
  https://developer.android.com/develop/background-work/services/fgs/service-types
- FGS declaration:
  https://developer.android.com/develop/background-work/services/fgs/declare
- Background start restrictions:
  https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- FGS timeouts:
  https://developer.android.com/develop/background-work/services/fgs/timeout
- Android 17 behavior changes:
  https://developer.android.com/about/versions/17/behavior-changes-17

### Notifications / Active apps

- Notification runtime permission:
  https://developer.android.com/develop/ui/compose/notifications/notification-permission
- Foreground Service Task Manager / Active apps:
  https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping

### Quick Settings

- `TileService`:
  https://developer.android.com/reference/android/service/quicksettings/TileService
