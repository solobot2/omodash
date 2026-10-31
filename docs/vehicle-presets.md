# Vehicle presets

`app/src/main/java/win/solobot/omodash/data/VehiclePreset.kt` is the catalog of vehicle bindings and launch protocols. The current selection is returned by `VehiclePresets.current()` and persisted as `vehicle_preset` by SettingsRepository. Startup restores that selection before constructing settings defaults. No selected preset means Default. Onboarding and Settings use the same selector.

OMODA 9 HW110:

| Type | Package | Opening |
|---|---|---|
| Radio | `com.desaysv.svaudioapp` | Desay OPEN_SOURCE, media type 8 |
| Music | `com.desaysv.svaudioapp` | Desay OPEN_SOURCE, media type 2 |
| Camera | `com.desaysv.ivi.vds.rvc` | OEM AVM event with existing speed guard |
| Phone | `com.desaysv.bluetooth.phone` | Package launch |
| CarPlay | `com.desaysv.vehicle.carplayapp` | `action.start.carplay` |
| Android Auto | `com.google.android.projection.sink` | Existing local TCP ADB helper |

Default has no vehicle assignments and allows manual app assignment. Standard shortcut types retain theme icons even after manual assignment. Custom application shortcuts can use native icons. Switching a preset applies its bindings; old pre-preset package assignments are intentionally ignored.

Now Playing observes all active MediaSessions and their callbacks, including sessions without notifications. Automatic source selection prefers an actively playing music session over retained tuner state. Selecting Any Player or FM/AM explicitly overrides automatic source selection. OMODA 9 FM/AM opens its preset radio and does not offer an application picker.
