#!/usr/bin/env python3
"""One-time USB setup and diagnostics for the production Omodash package. Stdlib only."""
import argparse
import datetime
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time
import zipfile

PACKAGE = "com.openlauncher.app"
MEDIA = PACKAGE + "/" + PACKAGE + ".service.MediaListenerService"
HELPER = PACKAGE + "/" + PACKAGE + ".update.UpdateAccessibilityService"
ACTIVITY = PACKAGE + "/" + PACKAGE + ".MainActivity"
PERMISSIONS = ("WRITE_SECURE_SETTINGS", "READ_LOGS", "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION")


class SetupError(RuntimeError):
    pass


def adb_path(explicit=None):
    if explicit:
        path = Path(explicit).expanduser()
        if not path.is_file():
            raise SetupError("Указанный файл ADB не найден: " + str(path))
        return str(path.resolve())
    candidates = [explicit, shutil.which("adb")]
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                 str(Path.home() / "Library/Android/sdk"),
                 str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk"),
                 str(Path.home() / "Android/Sdk"), str(Path(__file__).resolve().parent)):
        if root:
            candidates += [str(Path(root) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return str(Path(candidate).resolve())
    raise SetupError("ADB не найден. Установите Android SDK Platform-Tools или укажите --adb /путь/к/adb.")


def execute(args, timeout=30):
    try:
        result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise SetupError(str(error)) from error
    if result.returncode:
        raise SetupError((result.stderr or result.stdout).strip()[:2000] or f"Код ошибки {result.returncode}")
    return result.stdout.strip()


def select_device(listing, serial=None, allow_emulator=False, confirmed_usb=None):
    devices = []
    for line in listing.splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[1] in {"device", "unauthorized", "offline", "recovery", "sideload"}:
            devices.append((fields[0], fields[1], fields[0] == confirmed_usb or any(f.startswith("usb:") for f in fields[2:])))
    if serial:
        devices = [d for d in devices if d[0] == serial]
    else:
        devices = [d for d in devices if d[2] or (allow_emulator and d[0].startswith("emulator-"))]
    if len(devices) != 1:
        raise SetupError("Нужен один USB-аппарат. Включите ADB флешкой, подключите кабель передачи данных. "
                         "При нескольких устройствах укажите --serial СЕРИЙНЫЙ_НОМЕР. Проверьте adb devices -l.")
    serial, state, usb = devices[0]
    if not usb and not (allow_emulator and serial.startswith("emulator-")):
        raise SetupError("Разрешено только USB-подключение. Сетевой ADB этот скрипт не включает и не использует.")
    if state != "device":
        raise SetupError(f"Устройство {state}. Подтвердите запрос отладки от ноутбука на магнитоле; проверьте флешку и кабель.")
    return serial


def normalize_component(value):
    if "/" not in value:
        return value
    package, name = value.split("/", 1)
    return package + "/" + (package + name if name.startswith(".") else name)


def merge_service(value, component):
    entries = [] if value.strip() in {"", "null"} else value.strip().split(":")
    if normalize_component(component) not in map(normalize_component, entries):
        entries.append(component)
    return ":".join(entries)


def user_block(dump, user):
    blocks = re.split(r"(?m)^\s{4}User (\d+):", dump)
    for index in range(1, len(blocks), 2):
        if int(blocks[index]) == user:
            return blocks[index + 1]
    return ""


def granted(dump, permission, user):
    # Install permissions are global; runtime permissions must belong to this Android user.
    global_part = re.split(r"(?m)^\s{4}User \d+:", dump)[0]
    return bool(re.search(re.escape("android.permission." + permission) + r": granted=true\b",
                          global_part + "\n" + user_block(dump, user)))


def listener_state(dump, component, user):
    def section(start, end):
        return dump.split(start, 1)[-1].split(end, 1)[0] if start in dump else ""
    approved = section("Allowed notification listeners:", "All notification listeners")
    live = section("Live notification listeners", "Snoozed notification listeners")
    def found(text, marker):
        return any(component in line and marker in line for line in text.splitlines())
    short = component.replace(PACKAGE + "/" + PACKAGE + ".", PACKAGE + "/.")
    return (found(approved, f"(user: {user} ") or any(short in l and f"(user: {user} " in l for l in approved.splitlines()),
            found(live, f"(user {user})") or any(short in l and f"(user {user})" in l for l in live.splitlines()))


def helper_bound(dump, user):
    for block in re.split(r"(?m)^  \* ServiceRecord", dump)[1:]:
        first = block.splitlines()[0]
        if f" u{user} " in first and normalize_component(first.rstrip("}").split()[-1]) == HELPER:
            return "app=ProcessRecord{" in block and "received=true hasBound=true" in block
    return False


class Vehicle:
    def __init__(self, adb, serial):
        self.adb, self.serial = adb, serial
        current = self.shell("am", "get-current-user")
        if not re.fullmatch(r"\d+", current):
            raise SetupError("Не удалось определить активного пользователя Android: " + current)
        self.user = int(current)

    def shell(self, *args, timeout=30):
        # adb shell executes a remote shell even with a host-side argument list.
        return execute([self.adb, "-s", self.serial, "shell", shlex.join(map(str, args))], timeout)

    def preflight(self, setup=False):
        dump = self.shell("dumpsys", "package", PACKAGE)
        if not re.search(r"Package \[" + re.escape(PACKAGE) + r"\]", dump) or "installed=true" not in user_block(dump, self.user):
            raise SetupError("Релизный Open Launcher не установлен для активного пользователя. Установите omodash.apk без удаления данных.")
        if setup and "android.permission.WRITE_SECURE_SETTINGS" not in dump:
            raise SetupError("Сначала обновите APK до Omodash 0.0.9 или новее (без удаления данных).")
        code = re.search(r"versionCode=(\d+)", dump)
        if setup and (not code or int(code[1]) < 10):
            raise SetupError("Для полной USB-проверки нужен Omodash 0.0.9 или новее. Сначала установите новый APK без удаления данных.")
        version = re.search(r"versionName=(\S+)", dump)
        print(f"Устройство: {self.serial}; Android-пользователь: {self.user}; Omodash: {version[1] if version else '?'}")
        return dump

    def start(self):
        output = self.shell("am", "start", "--user", self.user, "-n", ACTIVITY)
        if "Error:" in output or "Exception" in output:
            raise SetupError(output[:1000])

    def status(self):
        dump = self.shell("dumpsys", "package", PACKAGE)
        result = {p: granted(dump, p, self.user) for p in PERMISSIONS}
        op = self.shell("appops", "get", "--user", self.user, PACKAGE, "REQUEST_INSTALL_PACKAGES")
        result["Установка обновлений (AppOps)"] = bool(re.search(r"REQUEST_INSTALL_PACKAGES:\s*allow\b", op))
        services = self.shell("settings", "--user", self.user, "get", "secure", "enabled_accessibility_services")
        enabled = self.shell("settings", "--user", self.user, "get", "secure", "accessibility_enabled")
        result["Хелпер автоподтверждения включён"] = enabled == "1" and HELPER in map(normalize_component, services.split(":"))
        result["Хелпер: подключение службы"] = helper_bound(self.shell("dumpsys", "activity", "services", PACKAGE), self.user)
        notification = self.shell("dumpsys", "notification")
        approved, live = listener_state(notification, MEDIA, self.user)
        result["Медиа: одобрение NotificationManager"] = approved
        result["Медиа: подключение службы"] = live
        for key, value in result.items():
            print(f"  {'OK' if value else 'НЕТ'}  {key}")
        # Live binding can lag approval; a missing binding is still reported, never hidden.
        return result

    def setup(self):
        failures = []
        def step(label, *args):
            try:
                output = self.shell(*args)
                if re.search(r"(?i)(exception|error:|unknown command|not allowed)", output):
                    raise SetupError(output[:1000])
            except SetupError as error:
                failures.append(label)
                print(f"ОШИБКА: {label}: {error}", file=sys.stderr)

        # A manual start restores components temporarily suspended by our Stop button.
        self.start()
        # On Android 11 shell cannot directly enable components of a non-debuggable APK.
        # Our DUMP-protected receiver restores only its own fixed component list as its own UID.
        restored = self.shell("am", "broadcast", "--user", self.user,
                              "-a", "com.openlauncher.app.action.RESTORE_COMPONENTS",
                              "-n", PACKAGE + "/" + PACKAGE + ".diagnostics.UsbControlReceiver")
        if "result=10729" not in restored:
            failures.append("Восстановление компонентов")
            print("ОШИБКА: приложение не подтвердило восстановление собственных компонентов.", file=sys.stderr)
        dump = self.shell("dumpsys", "package", PACKAGE)
        for permission in PERMISSIONS:
            if not granted(dump, permission, self.user):
                step(permission, "pm", "grant", "--user", self.user, PACKAGE, "android.permission." + permission)
        step("Медиа", "cmd", "notification", "allow_listener", MEDIA, self.user)
        before = self.shell("settings", "--user", self.user, "get", "secure", "enabled_accessibility_services")
        merged = merge_service(before, HELPER)
        if merged != before:
            step("Хелпер", "settings", "--user", self.user, "put", "secure", "enabled_accessibility_services", merged)
        if self.shell("settings", "--user", self.user, "get", "secure", "accessibility_enabled") != "1":
            step("Accessibility", "settings", "--user", self.user, "put", "secure", "accessibility_enabled", "1")
        # Changing this app-op can kill the app UID on Android 11. The USB host survives it.
        op = self.shell("appops", "get", "--user", self.user, PACKAGE, "REQUEST_INSTALL_PACKAGES")
        if not re.search(r"REQUEST_INSTALL_PACKAGES:\s*allow\b", op):
            step("Установка APK", "appops", "set", "--user", self.user, PACKAGE, "REQUEST_INSTALL_PACKAGES", "allow")
        self.start()
        print("Ожидаю подключения медиаслужбы и хелпера (до 35 секунд)…", flush=True)
        # The app's one-time rebind runs after Android's batched package-change notification.
        # Do not report success before that scheduled restoration has finished.
        time.sleep(13)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if helper_bound(self.shell("dumpsys", "activity", "services", PACKAGE), self.user):
                if listener_state(self.shell("dumpsys", "notification"), MEDIA, self.user)[1]:
                    break
            time.sleep(1)
        print("\nПроверка результата:")
        result = self.status()
        # Check the real in-app Android APIs as well: AppOps alone can miss OEM/user restrictions.
        api = self.shell("am", "broadcast", "--user", self.user,
                         "-a", "com.openlauncher.app.action.VERIFY_SETUP",
                         "-n", PACKAGE + "/" + PACKAGE + ".diagnostics.UsbControlReceiver")
        data = re.search(r'data="([^"]+)"', api)
        api_ok = "result=10728" in api and data is not None and set(data[1].split(";")) == {
            "install=true", "media=true", "helper=true", "logs=true", "secure=true"}
        print("  " + ("OK" if api_ok else "НЕТ") + "  Проверка через Android API приложения")
        if not api_ok:
            print("Проверка API: " + (data[1] if data else "нужен Omodash 0.0.9+ с USB-проверкой"))
        if failures or not all(result.values()) or not api_ok:
            raise SetupError("Настройка неполная: смотрите строки НЕТ/ОШИБКА выше. Прошивка могла запретить отдельное действие. "
                             "Запустите режим collect для диагностики; не удаляйте приложение.")
        print("\nПрава настроены. Обычное обновление APK с той же подписью сохраняет их и настройки приложения.\n"
              "Проверьте Check updates в лаунчере. После перезагрузки можно повторно проверить режимом status.")

    def stop(self):
        prepared = self.shell("am", "broadcast", "--user", self.user,
                              "-a", "com.openlauncher.app.action.PREPARE_STOP",
                              "-n", PACKAGE + "/" + PACKAGE + ".diagnostics.UsbControlReceiver")
        if "result=10727" not in prepared:
            raise SetupError("Не удалось подготовить остановку. Нужен Omodash 0.0.9 или новее. "
                             "Обычный force-stop может сразу перезапустить медиаслужбу; успех не подтверждён.")
        self.shell("am", "force-stop", "--user", self.user, PACKAGE)
        time.sleep(2)
        processes = self.shell("ps", "-A", "-o", "PID,NAME")
        own = [line for line in processes.splitlines() if re.search(r"\s" + re.escape(PACKAGE) + r"(?::\S+)?$", line)]
        dump = self.shell("dumpsys", "package", PACKAGE)
        if own or "stopped=true" not in user_block(dump, self.user):
            raise SetupError("Остановка не подтверждена: процесс есть или пакет не находится в stopped-состоянии. "
                             "Штатная система могла открыть default Home заново. Выберите штатный Home и повторите.")
        print("Android force-stop подтверждён: процессов нашего пакета нет. Не открывайте Omodash/кнопку Home до проверки радио.")

    def collect(self, destination):
        destination.mkdir(parents=True, exist_ok=True)
        filename = destination / ("omodash-usb-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f") + ".zip")
        commands = {
            "system-logcat.txt": ("logcat", "-d", "-b", "all", "-v", "threadtime", "-t", "6000"),
            "app-memory.txt": ("dumpsys", "meminfo", PACKAGE),
            "system-cpu.txt": ("dumpsys", "cpuinfo"),
            "app-package.txt": ("dumpsys", "package", PACKAGE),
            "processes.txt": ("ps", "-A", "-o", "PID,NAME"),
        }
        with zipfile.ZipFile(filename, "x", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("README.txt", "USB diagnostic snapshot. System logs may contain data from other apps. "
                             "Does not launch Omodash or change device settings. No ADB/private signing keys collected.\n")
            archive.writestr("device.txt", "\n".join(self.shell("getprop", p) for p in
                              ("ro.product.model", "ro.build.version.sdk", "ro.build.fingerprint")) + f"\nuser={self.user}\n")
            for name, command in commands.items():
                try:
                    value = self.shell(*command, timeout=40)
                except SetupError as error:
                    value = "SOURCE ERROR: " + str(error)
                archive.writestr(name, value[-2 * 1024 * 1024:])
        print("ZIP сохранён: " + str(filename.resolve()))
        return filename


def main():
    parser = argparse.ArgumentParser(description="USB-настройка Omodash. Не включает сетевой ADB, не меняет штатные приложения.")
    parser.add_argument("action", choices=("setup", "status", "collect", "stop"), nargs="?", default="setup")
    parser.add_argument("--adb", help="Путь к adb / adb.exe из Android SDK Platform-Tools")
    parser.add_argument("--serial", help="USB serial из adb devices -l")
    parser.add_argument("--allow-emulator", action="store_true", help="Только для разработки: разрешить явно выбранный эмулятор")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent / "usb-results")
    args = parser.parse_args()
    try:
        adb = adb_path(args.adb)
        listing = execute([adb, "devices", "-l"])
        # Some Windows ADB backends omit usb: from devices -l. Let ADB confirm USB,
        # never infer it from a serial string or from the user's --serial argument.
        confirmed_usb = None
        try:
            confirmed_usb = execute([adb, "-d", "get-serialno"])
        except SetupError:
            if args.serial:
                try:
                    if execute([adb, "-s", args.serial, "get-devpath"]).startswith("usb:"):
                        confirmed_usb = args.serial
                except SetupError:
                    pass
        serial = select_device(listing, args.serial, args.allow_emulator, confirmed_usb)
        vehicle = Vehicle(adb, serial)
        vehicle.preflight(setup=args.action == "setup")
        if args.action == "setup":
            vehicle.setup()
        elif args.action == "status":
            if not all(vehicle.status().values()):
                return 1
        elif args.action == "collect":
            vehicle.collect(args.output)
        else:
            vehicle.stop()
        return 0
    except SetupError as error:
        print("\n" + str(error), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nОстановлено пользователем. Можно безопасно повторить setup.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
