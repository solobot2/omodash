# Подписанные релизы и обновление в автомобиле

## Ключ подписи — сохранить до первой установки

Постоянный ключ создаётся один раз командой `python3 tools/signing.py`.
Если ключ уже существует, команда отказывается его заменять.

Приватные файлы находятся в `keystore/` и исключены из Git:

- `omodash-release.p12` — закрытый ключ подписи APK;
- `signing.properties` — путь, alias и пароли для Gradle;
- `omodash-certificate.pem` — публичный сертификат;
- `certificate-sha256.txt` — отпечаток сертификата.

Сделайте приватную резервную копию **всей папки `keystore/`** на отдельном
защищённом носителе. Не публикуйте эту папку и не добавляйте её в Git.
Потеря ключа лишит обычного обновления уже установленной версии. Покупать
сертификат у удостоверяющего центра для этой схемы не требуется.

## Сборки

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Debug имеет отдельный пакет `win.solobot.omodash.debug`; release сохраняет
постоянный пакет `win.solobot.omodash`. Release подписывается постоянным ключом,
оптимизируется R8, содержит уменьшенные ресурсы и не является debuggable.
Без настроенного ключа сборка release завершается ошибкой, а не создаёт
незаметно неподписанный APK.

Готовый release: `app/build/outputs/apk/release/app-release.apk`.
Файл расшифровки стеков R8: `app/build/outputs/mapping/release/mapping.txt`.

После переименования пакета Android считает `win.solobot.omodash` отдельным
приложением. Удалите прежний Omodash, установите новый подписанный APK и повторите
`python3 tools/vehicle_usb.py setup` из нового набора USB-tools. Настройки старого
приложения при удалении будут потеряны; при необходимости снова назначьте Omodash
домашним экраном. Постоянный сертификат Omodash сохраняется. Последующие обновления
нового пакета сохраняют его данные и права.

## Публикация на GitHub

Один раз авторизуйтесь на компьютере:

```sh
gh auth login --hostname github.com --git-protocol https --web
```

Вместо GitHub CLI поддерживаются локальные `GH_TOKEN` / `GITHUB_TOKEN` с правом
Contents: write для `solobot2/omodash`, либо Git credential helper. Токен никогда
не попадает в приложение, APK, метаданные обновления или аргументы Git-команд.

Перед публикацией закоммитьте изменения. Публикация разрешена из `main`; локальная
ветка должна содержать `origin/main`. Скрипт не делает force-push и не переписывает
опубликованные релизы.

```sh
# Проверить сборку и будущие артефакты без публикации и изменения версии:
./gradlew deploy -PdeployDryRun=true

# Следующая patch-версия автоматически:
./gradlew deploy

# Явно задать человекочитаемую версию:
./gradlew deploy -PreleaseVersion=0.1.0

# Добавить описание изменений из UTF-8 файла:
./gradlew deploy -PreleaseVersion=0.1.0 -PreleaseNotesFile=/path/to/notes.txt
```

Обычная сборка не увеличивает версию. `version.properties` хранит подготовленные
`versionCode` и `versionName`. При deploy номер Android берётся больше опубликованных
номеров, имя версии — явно заданное либо следующая patch-версия. Изменение версии
фиксируется отдельным коммитом. Параллельные deploy одного checkout блокируются.

Для графических изменений с явно выбранной визуальной проверкой без тестов:
`python3 tools/release.py deploy --skip-tests --notes-file /path/to/notes.txt`.
Этот флаг пропускает только тесты; lint, сборка, проверка подписи, версии и
артефактов остаются обязательными. По умолчанию deploy запускает тесты.

Deploy собирает подписанный APK, проверяет подпись и версию, отправляет коммит и тег
`vVERSION`, создаёт draft release, загружает **все** файлы и затем публикует релиз:

- `omodash.apk` — универсальный установочный файл;
- `update.json` — пакет, versionCode, versionName, minSdk, размер, SHA-256 APK,
  отпечаток сертификата, точный URL файла и исходный commit;
- `SHA256SUMS.txt` — контрольная сумма;
- `mapping.txt` — расшифровка стека для именно этой оптимизированной сборки;
- `omodash-usb-tools.zip` — переносимый USB-набор для Mac/Windows с инструкцией.

Локальные копии: `release/vVERSION/` (не добавляются в Git).
Если сеть пропала после создания draft, повторите deploy с той же явно указанной
версией: совместимый draft и тег используются повторно. Отличающиеся файлы не
перезаписываются автоматически.

Прямая ссылка на последний стабильный APK:
`https://github.com/solobot2/omodash/releases/latest/download/omodash.apk`.
Конкретная версия: `/releases/download/vVERSION/omodash.apk`.

## Однократная настройка в автомобиле

Установить подписанный `omodash.apk` версии 0.0.9 или новее, затем один раз
подключить ноутбук по USB и запустить `python3 tools/vehicle_usb.py setup`.
[Пошаговая инструкция и диагностика](usb-setup.md).
На этой магнитоле флешка с ключом включает USB ADB; локальный TCP ADB при этом
может оставаться закрытым. Для скрипта TCP не нужен.

Скрипт настраивает только `win.solobot.omodash`: доступ к медиасессиям через
NotificationManager, установку APK, собственный Accessibility helper, чтение
системных логов, WRITE_SECURE_SETTINGS и геолокацию для GPS/погоды. Другие
включённые listeners и Accessibility services сохраняются. Штатные приложения,
назначение Home, настройки сети и Device Owner не изменяются.

В настройках остаются статусы прав. Пять дополнительных действий их выдачи
удалены: Configure vehicle permissions, Media access, Allow app installations,
Recheck permissions, Enable update helper. Обновление статусов установки
выполняется при Check updates. Переключатель Automatically confirm this
launcher's updates позволяет отключить автоподтверждение.

После настройки **Check updates → Download and install** скачивает APK,
проверяет пакет, версию, контрольную сумму и сертификат, затем запускает
установку. Если есть DownloadManager, загрузку ведёт Android; на прошивке без
него работает встроенная HTTPS-загрузка. Для встроенной загрузки нужен живой
процесс лаунчера; после его перезапуска можно повторить загрузку.

На Android 11 автоподтверждение выполняет включённый helper, только для нашей
проверенной установки. На Android 12+ дополнительно запрашивается штатная
возможность обновления без подтверждения; окончательное поведение зависит от
прошивки. Если helper не сработал, подтвердить системное окно вручную.

Обычное обновление с тем же пакетом и сертификатом сохраняет данные и выданные
права. Удаление приложения, сброс или политика OEM могут их сбросить.
Проверка на эмуляторе не гарантирует поведение конкретной магнитолы.

## Проверки разработки

На OEM-прошивке без Download Provider (authority `downloads`) используется собственная HTTPS загрузка с теми же проверками размера, SHA-256, версии, пакета и сертификата. Этот запасной загрузчик не создаёт аудиопотоков или уведомлений. Если Android завершит его процесс, загрузка повторится при следующем открытии Updates; сохранённый частичный файл не устанавливается. При наличии DownloadManager сохраняется прежняя фоновая загрузка.

Для проверки запасного загрузчика в изолированном `updateTestMode` доступен `-PinternalDownloadTest=true`. В обычном APK этот флаг всегда false.

```sh
python3 -m unittest discover -s tools/tests -v
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew :app:testDebugUnitTest
# На отдельном тестовом эмуляторе:
ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedDebugAndroidTest
```

Для испытания A → B без изменения основного установленного пакета предусмотрен
явный тестовый режим. Он использует тот же release-ключ и оптимизацию, но отдельный
пакет `win.solobot.omodash.updatetest` и локальный HTTP feed:

```sh
./gradlew :app:assembleRelease \
  -PupdateTestMode=true \
  -PupdateFeedUrl=http://10.0.2.2:8765/update.json \
  -PreleaseVersion=0.0.6 -PreleaseCode=7
```

Публикация тестового пакета запрещена. Обычный release принимает метаданные и APK
только из `solobot2/omodash` по HTTPS. Debug не обновляется production APK с другой
подписью и именем пакета.

Работа на эмуляторе не заменяет проверку специальных возможностей, установщика
и возврата на Home на конкретной прошивке автомобиля.

## Omodash 0.0.14

Production AA использует только помощник через локальный TCP ADB, без отдельного
экспериментального приёмника. Подготовка: `python3 tools/vehicle_usb.py enable-android-auto`
после обычного USB setup. [Подробности и ограничения](projection.md).
Сетка 6×2 автоматически переносит прежние размеры и позиции; оба масштаба 100–200%.

## Omodash 0.0.16

Если APK уже скачан, но установка ещё не разрешена, вместо отключённой Install
показывается **Allow installation**. На Android с рабочим экраном разрешений
открывается настройка только этого лаунчера; после возврата разрешение проверяется
один раз. На прошивках с заглушкой показывается USB-инструкция и **Recheck and install**.
Повторного скачивания не требуется. Периодический опрос разрешения не добавлен.

## 0.0.17

Vehicle presets OMODA 9 and Default are selected in onboarding and Settings. App defaults resolve VehiclePresets.current(); old package assignments are discarded. See [vehicle presets](vehicle-presets.md).

Now Playing observes all media sessions and their metadata, including artwork changes with unchanged track labels. Standard vehicle shortcuts retain theme icons. Desay binding/read failures can retry.

Android 11 uses the native INSTALL_PACKAGE/FileProvider route found in the saved JCT APK, with manual system confirmation. Newer Android retains PackageInstaller. The single Install update action downloads, verifies, prepares access and installs; it can use the locally authorized TCP ADB connection when no permission screen is available. See [JCT comparison](update-jct-comparison.md).
