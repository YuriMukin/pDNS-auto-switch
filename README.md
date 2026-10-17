# pDNS Auto Switch

Личное Android-приложение для переключения Private DNS в зависимости от доверенного Wi-Fi.

## Текущее состояние

Создан базовый проект на Kotlin со стартовым экраном. Переключение DNS, мониторинг сети,
foreground service и плитка быстрых настроек пока не реализованы.
Исходный план: [docs/smart_dns_switcher_plan.md](docs/smart_dns_switcher_plan.md).

## Инструменты

- Android Studio 2026.2.1 или совместимая версия.
- Android SDK Platform 37 (Android 17), Build Tools 36.0.0.
- Android Gradle Plugin 9.1.1, Gradle Wrapper 9.3.1, встроенный Kotlin AGP.
- JDK 17+; на этом ПК сборка проверяется на JBR 25 из Android Studio.
- minSdk 33, targetSdk 37, Java/Kotlin bytecode target 17.
- Application ID: `io.github.sorjo.pdnsautoswitch`.

## Открытие и сборка

Открыть корень репозитория в Android Studio и дождаться Gradle Sync.
`local.properties` содержит локальный путь SDK и не добавляется в Git.

PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Следующий этап

Реализовать DnsController и Quick Settings tile, затем проверить выдачу
WRITE_SECURE_SETTINGS через ADB на реальном Pixel. После этого проверить
доступность SSID в фоне и после перезагрузки согласно исходному плану.
