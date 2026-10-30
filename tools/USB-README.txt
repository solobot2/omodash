OMODASH: ОДНОКРАТНАЯ НАСТРОЙКА ПО USB

1. На магнитоле должен быть подписанный Omodash 0.0.9 или новее.
   Устанавливать поверх старой версии, не удаляя данные.
2. Включить USB ADB своей флешкой с ключом и подключить ноутбук кабелем.
   Если появится запрос отладки от ноутбука — подтвердить на магнитоле.
3. macOS: открыть tools/setup-usb.command.
   Windows: открыть tools/setup-usb.cmd.
4. Дождаться OK у всех проверок и сообщения о завершении настройки.
   НЕТ / ОШИБКА означает, что настройка не завершилась.
5. В лаунчере: Settings → Updates → Check updates.

Нужны Python 3.9+ и Android SDK Platform-Tools.
На Mac с Android Studio ADB обычно находится автоматически.
Если ADB не найден: https://developer.android.com/tools/releases/platform-tools
В терминале можно указать: python3 tools/vehicle_usb.py setup --adb /путь/к/adb
На Windows вместо python3 можно использовать py -3 и путь к adb.exe.

Команды из корня распакованного набора / проекта:

python3 tools/vehicle_usb.py setup    — настройка только нашего пакета
python3 tools/vehicle_usb.py status   — проверка без запуска приложения
python3 tools/vehicle_usb.py collect  — диагностический ZIP на ноутбук
python3 tools/vehicle_usb.py stop     — подготовка компонентов и проверенный force-stop

Если устройств несколько: добавить --serial НОМЕР_ИЗ_ADB_DEVICES.
Сетевой ADB не включается; IP-подключение не используется.
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
  python3 tools/vehicle_usb.py enable-android-auto
После команды откройте Android Auto в лаунчере и подтвердите USB debugging на ГУ.
TCP ADB может отключаться после полного перезапуска ГУ; тогда повторите команду по USB.
Подробности: docs/usb-setup.md
