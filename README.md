# pDNS Auto Switch

Личное Android-приложение для ручного переключения Private DNS через Quick Settings.

## Реализовано

- Плитка **Smart DNS**: выбранный провайдер (`hostname`) ↔ выключено (`off`).
- Адрес провайдера не изменяется и сохраняется при выключении.
- Плитка и экран читают реальное системное состояние. Изменения в настройках Android
  отслеживаются, пока экран приложения или панель плиток видимы.
- Режим Android «Автоматически» показан отдельно; нажатие включает сохранённого провайдера.
- Кнопка добавления плитки, долгое нажатие для открытия приложения, разблокировка перед переключением.
- Проверка WRITE_SECURE_SETTINGS, копирование ADB-команды, переход к системным настройкам.

Автоматика по Wi-Fi, фоновые сервисы, автозапуск и запрос геолокации пока отсутствуют.
Состояние «Включён» показывает настройку Android, а не проверку доступности DNS-сервера.
Исходный план: [docs/smart_dns_switcher_plan.md](docs/smart_dns_switcher_plan.md).

## Первый запуск на Pixel

1. Установить `app/build/outputs/apk/debug/app-debug.apk`.
2. В Android настроить Private DNS: выбрать имя хоста провайдера, указать свой сервер и сохранить.
3. Включить USB-отладку, подключить телефон к ПК и разрешить этому ПК отладку.
4. Выдать разрешение один раз:

   ```text
   adb shell pm grant io.github.sorjo.pdnsautoswitch android.permission.WRITE_SECURE_SETTINGS
   ```

5. Вернуться в приложение: статус разрешения обновится. Нажать «Добавить плитку в шторку».
6. Проверить переключение в системных настройках Private DNS.

Root, Shizuku и постоянное подключение ADB не нужны. После удаления и повторной установки
разрешение нужно выдать заново. После перезагрузки проверить его сохранение.
Если плитка уже добавлена, Android сообщит об этом; её положение можно изменить в редакторе шторки.

## Инструменты и сборка

- Android Studio 2026.2.1 или совместимая версия.
- Android SDK Platform 37 (Android 17), Build Tools 36.0.0.
- Android Gradle Plugin 9.1.1, Gradle Wrapper 9.3.1, встроенный Kotlin AGP.
- Daemon JVM criteria в `gradle/gradle-daemon-jvm.properties` фиксируют JDK 25.
- minSdk 33, targetSdk 37, Java/Kotlin bytecode target 17.
- Application ID: `io.github.sorjo.pdnsautoswitch`.

Открыть корень репозитория в Android Studio и дождаться Gradle Sync.
`local.properties` содержит локальный путь SDK и не добавляется в Git.

PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Ручная проверка: [docs/manual-tile-checklist.md](docs/manual-tile-checklist.md).

## Что хранится в Git

Исходники, ресурсы, сборочные скрипты и Gradle Wrapper (включая JAR) нужны в репозитории.
`gradle-daemon-jvm.properties` — переносимые критерии JVM, это также часть сборки.
Исключены каталоги IDE, локальные пути SDK, кэши, результаты сборки, APK/AAB, дампы,
ключи подписи и локальные файлы с секретами.
