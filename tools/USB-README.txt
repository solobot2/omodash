OMODASH: ОДНОКРАТНАЯ НАСТРОЙКА ПО USB

1. На магнитоле должен быть подписанный Omodash 0.0.9 или новее.
   Устанавливать поверх старой версии, не удаляя данные.
2. Включить USB ADB своей флешкой с ключом и подключить ноутбук кабелем.
   Если появится запрос отладки от ноутбука — подтвердить на магнитоле.
3. macOS: открыть tools/setup-usb.command.
   Windows: открыть tools/setup-usb.cmd.
4. Дождаться OK у всех проверок прав и результата попытки включить TCP ADB.
   НЕТ / ОШИБКА означает, что соответствующий этап не подтверждён (код завершения 1).
5. В лаунчере: Settings → Updates → Check updates.

Нужны Python 3.9+ и Android SDK Platform-Tools.
На Mac с Android Studio ADB обычно находится автоматически.
Если ADB не найден: https://developer.android.com/tools/releases/platform-tools
В терминале можно указать: python3 tools/vehicle_usb.py setup --adb /путь/к/adb
На Windows вместо python3 можно использовать py -3 и путь к adb.exe.

Команды из корня распакованного набора / проекта:

python3 tools/vehicle_usb.py setup    — права нашего пакета и попытка включить TCP ADB
python3 tools/vehicle_usb.py status   — проверка без запуска приложения
python3 tools/vehicle_usb.py collect  — диагностический ZIP на ноутбук
python3 tools/vehicle_usb.py stop     — подготовка компонентов и проверенный force-stop

Если устройств несколько: добавить --serial НОМЕР_ИЗ_ADB_DEVICES.
Команды скрипта выполняются только через USB; IP-подключение не используется.
В конце setup включается TCP ADB на ГУ (порт 5555), проверяется ответ команды
и состояние adbd после его перезапуска. Порт может быть доступен также по сети ГУ.
Подключение Omodash к 127.0.0.1:5555 проверяется при открытии Android Auto.
Root и смена Device Owner не нужны. Штатные приложения и default Home не меняются.

Подвисания / ALERT:
сделать collect → stop → проверить радио → collect ещё раз.
После stop не открывать Omodash и не нажимать Home до проверки:
Android может снова запустить default Home. Ручной запуск восстановит компоненты.
ZIP лежит в tools/usb-results/. Системный журнал может содержать данные других программ.

Без ноутбука в приложении:
Settings → Diagnostics → Record logs for 2 minutes → Start recording.
После USB-настройки можно отметить Include system logs.
Воспроизвести проблему, затем Send / save diagnostic ZIP.
Чтобы приложить системную запись, в форме ZIP тоже отметить Also include system logs.
Остановка приложения прекращает запись; уже сохранённые логи остаются.

Подробности: docs/usb-setup.md.
Проверено на отдельном AOSP Android 11. Проверка конкретной магнитолы ещё требуется.

Android Auto в плитках Omodash 0.0.14+:
Обычный setup уже пробует включить TCP ADB. Для повтора только этого шага:
  python3 tools/vehicle_usb.py enable-android-auto
После команды откройте Android Auto в лаунчере и подтвердите USB debugging на ГУ.
TCP ADB может отключаться после полного перезапуска ГУ; тогда повторите команду по USB.
Подробности: docs/usb-setup.md

С Android-телефона: Termux + Termux:API + Python + termux-adb (USB без root).
Телефон должен поддерживать USB Host/OTG. После подключения и авторизации:
  python3 tools/vehicle_usb.py setup --adb "$(command -v termux-adb)"
Установка termux-adb: https://github.com/nohajc/termux-adb
Пошаговые указания и вариант восстановления медиа через Bugjaeger: docs/usb-setup.md.
Запуск с телефона на конкретном ГУ пока не проверен.
