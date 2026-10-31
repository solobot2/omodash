# Vehicle presets

`app/src/main/java/win/solobot/omodash/data/VehiclePreset.kt` is the catalog of vehicle bindings and launch protocols. The current selection is returned by `VehiclePresets.current()` and persisted as `vehicle_preset` by SettingsRepository. Startup restores that selection before constructing settings defaults. No selected preset means Default. Onboarding and Settings use the same selector.

OMODA 9 HW110:

| Type | Package | Opening |
|---|---|---|
| Navigation | `com.astrob.turbodog` / `.online` | Native `com.astrob.turbodog.WelcomeActivity` |
| Car Settings | `com.desaysv.vehiclesetting` | `ui.activity.SettingActivity`, `ACTION_QUICK_CONTROL_SETTING`, section 8 |
| Local Settings | `com.desaysv.setting` | `view.activity.SettingActivity`, `ACTION_DEVICE_SETTING` |
| Now Playing | Current OEM source | OPEN_SOURCE for the source queried at click time, PLAY_VIEW 3 |
| Radio | `com.desaysv.svaudioapp` | Desay OPEN_SOURCE, media type 8 |
| Music | `com.desaysv.svaudioapp` | Desay OPEN_SOURCE, media type 2 |
| Camera | `com.desaysv.ivi.vds.rvc` | OEM AVM event with existing speed guard |
| Phone | `com.desaysv.bluetooth.phone` | Package launch |
| CarPlay | `com.desaysv.vehicle.carplayapp` | `action.start.carplay` |
| Android Auto | `com.google.android.projection.sink` | Native receiver launch |

Default supports manual assignment of the three upper vehicle routes in Settings. Its generic navigation and local-settings intents use Android's map-app category and system Settings action. Home, launcher Settings and Applications are internal destinations and cannot be reassigned. Onboarding and Settings select the same preset catalog.

The OMODA 9 Now Playing pipeline reads CURRENT_SOURCE_TYPE and the source-specific OEM metadata, including projection sessions omitted by Android's active-session list. Radio is never inferred from track text. The Now Playing menu contains source selection, Open CarPlay, Open Android Auto and widget resizing. Both projection actions use the preset. The source-open command uses the same media PLAY_VIEW as the native card.
