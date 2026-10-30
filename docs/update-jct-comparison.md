# Android 11 update compatibility

Inspected the owner's saved JCarTools APK decompilation under `JCarTools-behavior-20261023/decoded/` (offline, not a vehicle runtime test):

- `e0/y2.java:c`: privileged `JCTProxy.ip(files)` when configured; Device Owner installs through ProgressActivity; otherwise `android.intent.action.INSTALL_PACKAGE` with a FileProvider URI.
- `e0/j2.java`: ProgressActivity streams the APK into a PackageInstaller session.
- `com/example/navistart/PermActivity.java:y`: opens MANAGE_UNKNOWN_APP_SOURCES.
- `com/example/navistart/MyA11yService.java`: installer UI automation. Omodash's existing automation remains limited to its own PackageInstaller sessions.

Omodash now uses INSTALL_PACKAGE + a read-only URI grant on API 21–30, including the owner's Android 11. The APK is checked against application ID, version, release certificate, SHA-256 and expected size before the installer opens. The user confirms installation in the native system window; cancelling leaves Install update available for retry. FileProvider exposes only the updates directory and existing report directory.

On API 31+ the owned PackageInstaller session path remains, including supported automatic self-updates. When the native unknown-sources screen is absent, the single Install update action first uses the existing loopback-only, locally authorized TCP ADB identity to grant REQUEST_INSTALL_PACKAGES only to this application and current Android user. When no authorized local ADB connection exists, the same flow opens Android permission settings. If the firmware lacks that screen too, it displays the USB setup prerequisite and the same Install update button remains available for retry. It never grants Device Owner or invokes JCT's private proxy.

Emulator checks validate Android 11's native installation path and media/preset behavior. They do not establish successful installation on the physical head unit or its policy configuration. Version 0.0.15 cannot acquire this repair without first installing the new APK using an available installation method.

Install update starts download and verification and retains the request across process death. Permission preparation is attempted once per click, preventing settings loops after denial. Android 11 may kill the app UID when access changes; startup restores Settings/Updates and continues the pending installation automatically.

Verified on the dedicated Android 11 emulator: one application Install update click downloaded and verified the signed APK, opened native permission settings when local ADB was unavailable, restored the pending flow after Android killed/restarted the app on access change, and opened PackageInstaller. Cancelling left the same Install update action enabled; retry installed versionCode 201 over 200 without another APK download. The installer was `com.android.packageinstaller`, as expected for INSTALL_PACKAGE. Only the base APK was installed through host ADB; the target was downloaded and installed through the app/native UI.
