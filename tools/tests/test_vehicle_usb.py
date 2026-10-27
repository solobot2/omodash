import contextlib
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("vehicle_usb", Path(__file__).parents[1] / "vehicle_usb.py")
usb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(usb)


class UsbSafetyTest(unittest.TestCase):
    def test_no_guessing_between_usb_devices(self):
        with self.assertRaises(usb.SetupError):
            usb.select_device("A device usb:1-1\nB device usb:1-2")
        self.assertEqual("B", usb.select_device("A device usb:1-1\nB device usb:1-2", "B"))

    def test_network_device_rejected_even_when_explicit(self):
        with self.assertRaises(usb.SetupError):
            usb.select_device("192.168.1.2:5555 device product:car", "192.168.1.2:5555")

    def test_emulator_cannot_receive_normal_setup(self):
        with self.assertRaises(usb.SetupError):
            usb.select_device("emulator-5554 device product:sdk", "emulator-5554")
        self.assertEqual("emulator-5554", usb.select_device("emulator-5554 device", "emulator-5554", True))

    def test_unauthorized_does_not_continue(self):
        with self.assertRaisesRegex(usb.SetupError, "unauthorized"):
            usb.select_device("CAR unauthorized usb:1-1")

    def test_windows_backend_can_confirm_usb_without_listing_usb_metadata(self):
        listing = "CAR device model:Car\n192.168.0.5:5555 device model:Car"
        self.assertEqual("CAR", usb.select_device(listing, confirmed_usb="CAR"))
        with self.assertRaises(usb.SetupError):
            usb.select_device(listing, "192.168.0.5:5555", confirmed_usb="CAR")

    def test_preserves_other_accessibility_services_and_short_names(self):
        existing = "other.app/.Service:" + usb.HELPER.replace(usb.PACKAGE + "/" + usb.PACKAGE, usb.PACKAGE + "/")
        self.assertEqual(existing, usb.merge_service(existing, usb.HELPER))
        self.assertEqual("other.app/.Service:" + usb.HELPER, usb.merge_service("other.app/.Service", usb.HELPER))
        self.assertEqual(usb.HELPER, usb.merge_service("null", usb.HELPER))

    def test_runtime_grant_in_another_user_is_not_success(self):
        dump = ("    install permissions:\n      android.permission.READ_LOGS: granted=true\n"
                "    User 0: installed=true\n      android.permission.ACCESS_FINE_LOCATION: granted=true\n"
                "    User 10: installed=true\n      android.permission.ACCESS_FINE_LOCATION: granted=false\n")
        self.assertTrue(usb.granted(dump, "READ_LOGS", 10))
        self.assertTrue(usb.granted(dump, "ACCESS_FINE_LOCATION", 0))
        self.assertFalse(usb.granted(dump, "ACCESS_FINE_LOCATION", 10))

    def test_requires_real_listener_approval_for_current_user(self):
        dump = ("Allowed notification listeners:\n"
                f" {usb.MEDIA} (user: 0 isPrimary: true)\nAll notification listeners (1)\n"
                f"Live notification listeners (1):\n ComponentInfo{{{usb.MEDIA}}} (user 0): proxy\n"
                "Snoozed notification listeners (0)")
        self.assertEqual((True, True), usb.listener_state(dump, usb.MEDIA, 0))
        self.assertEqual((False, False), usb.listener_state(dump, usb.MEDIA, 10))
        self.assertEqual((False, False), usb.listener_state("enabled_notification_listeners=" + usb.MEDIA, usb.MEDIA, 0))

    def test_enabled_helper_without_system_binding_is_not_success(self):
        header = "  * ServiceRecord{123 u0 com.openlauncher.app/.update.UpdateAccessibilityService}\n"
        self.assertFalse(usb.helper_bound(header + "app=null\n", 0))
        self.assertTrue(usb.helper_bound(header + "app=ProcessRecord{123}\nreceived=true hasBound=true\n", 0))
        self.assertFalse(usb.helper_bound(header + "app=ProcessRecord{123}\nreceived=true hasBound=true\n", 10))

    def test_setup_reports_failure_after_rejected_grant(self):
        vehicle = object.__new__(usb.Vehicle)
        vehicle.user = 0
        vehicle.start = lambda: None
        vehicle.status = lambda: {"READ_LOGS": False}
        def shell(*args):
            if args[:2] == ("pm", "grant"):
                raise usb.SetupError("SecurityException: denied")
            return "null"
        vehicle.shell = shell
        with patch.object(usb.time, "sleep"), patch.object(usb.time, "monotonic", side_effect=[0, 21]), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaisesRegex(usb.SetupError, "Настройка неполная"):
                vehicle.setup()

    def test_remote_shell_arguments_are_quoted(self):
        vehicle = object.__new__(usb.Vehicle)
        vehicle.adb, vehicle.serial = "adb", "CAR"
        with patch.object(usb, "execute", return_value="") as run:
            vehicle.shell("settings", "put", "secure", "services", "other.app/.Service;touch /tmp/no")
        self.assertEqual(run.call_args[0][0][-1], "settings put secure services 'other.app/.Service;touch /tmp/no'")

    def test_appops_allow_is_not_enough_if_android_api_denies_installation(self):
        vehicle = object.__new__(usb.Vehicle)
        vehicle.user = 0
        vehicle.start = lambda: None
        vehicle.status = lambda: {"AppOps": True}
        def shell(*args):
            if "VERIFY_SETUP" in " ".join(map(str, args)):
                return 'Broadcast completed: result=10728, data="install=false;media=true;helper=true;logs=true;secure=true"'
            return "null"
        vehicle.shell = shell
        with patch.object(usb.time, "sleep"), patch.object(usb.time, "monotonic", side_effect=[0, 21]), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaisesRegex(usb.SetupError, "Настройка неполная"):
                vehicle.setup()


if __name__ == "__main__":
    unittest.main()
