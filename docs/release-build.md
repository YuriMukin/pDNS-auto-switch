# Подписанная release-сборка

Release APK подписывается постоянным ключом владельца. Debug APK использует отдельный
отладочный ключ. Application ID и версия задаются в `app/build.gradle.kts`.

## Ключ и пароли

Создайте собственный PKCS12 keystore через Android Studio (Generate Signed Bundle / APK)
или `keytool`. Храните ключ и файл свойств вне репозитория. Не используйте debug-ключ
для release и не передавайте пароли через аргументы командной строки.

Файл `signing.properties` должен содержать:

```properties
storeFile=release.p12
storePassword=YOUR_STORE_PASSWORD
keyAlias=pdns-release
keyPassword=YOUR_KEY_PASSWORD
```

`storeFile` — путь относительно каталога файла свойств (либо абсолютный путь).
Переменная окружения `PDNS_SIGNING_PROPERTIES` задаёт путь к файлу свойств.
Пароли не включаются в исходники. Без реквизитов release-сборка завершается ошибкой
предварительной проверки; debug-сборка их не требует.

Сохраните резервную копию keystore и паролей в защищённом месте. Для обновлений
установленного приложения нужны тот же ключ и application ID, а для следующих
версий следует увеличивать `versionCode`. Потеря ключа помешает обычному обновлению.

## Сборка и проверка

В PowerShell из корня проекта, с настроенными SDK и JDK:

```powershell
$env:PDNS_SIGNING_PROPERTIES = Join-Path $env:USERPROFILE '.android/pdns-auto-switch-release/signing.properties'
.\gradlew.bat assembleRelease testDebugUnitTest lintRelease
```

Результат: `app/build/release/pdns-auto-switch-0.3.5.apk`.
Имя имеет формат `pdns-auto-switch-<versionName>.apk`: версия берётся из конфигурации
автоматически. Задача `exportReleaseApk` выполняется вместе с `assembleRelease` и копирует
подписанный APK без изменения содержимого. Служебный `app-release.apk` остаётся в каталоге
Android Gradle Plugin; для распространения используйте файл из `app/build/release/`.
Для debug-проверок по-прежнему используется команда из README.

Проверьте APK утилитами установленной версии Android SDK Build Tools:

```powershell
$toolsDir = Join-Path $env:LOCALAPPDATA 'Android/Sdk/build-tools/36.0.0'
& "$toolsDir/apksigner.bat" verify --verbose --print-certs app/build/release/pdns-auto-switch-0.3.5.apk
& "$toolsDir/zipalign.exe" -c -P 16 -v 4 app/build/release/pdns-auto-switch-0.3.5.apk
Get-FileHash app/build/release/pdns-auto-switch-0.3.5.apk -Algorithm SHA256
```

В `aapt dump badging` должны быть ожидаемые package/version/minSdk/targetSdk
и отсутствовать `application-debuggable`. Для публикации прикрепляют APK и его
SHA-256; keystore и файл свойств не публикуют. Тег GitHub должен указывать на
коммит с исходниками, из которых собран APK. Публикация выполняется отдельно.

## Переход с debug на release

APK с другой подписью нельзя установить как обычное обновление поверх debug-версии.
Потребуется удалить debug-приложение и установить release APK: локальный выбор палитры
будет сброшен, ADB-разрешение нужно выдать заново, плитку может потребоваться добавить
повторно. Провайдер Private DNS хранится в настройках Android, отдельно от приложения.
Удаление и установка на основном телефоне выполняются только по указанию владельца.

## Границы проверки

Успешные сборка, JVM-тесты, Lint, проверка подписи и выравнивания не заменяют проверку
установленного APK на Android. Оставшиеся сценарии перечислены в
[чек-листе плитки](manual-tile-checklist.md).
