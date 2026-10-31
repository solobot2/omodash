# Day/night theme foundation

The fixed palettes in `DashboardPalette.kt` own the background, panel and card
gradients, text/icon colours, edges and highlights. Navigation selection is
emerald at night and blue by day; climate selection is warm gold at night and
blue by day. There are no editable accent/background/text colours.

`DashboardChrome.kt` provides `dashboardBackground`, `dashboardSurface`,
`NavButton`, `HvacButton`, `DashboardVerticalDivider` and
`DashboardHorizontalDivider`. Buttons accept composable content, so vectors,
bitmaps and icon/text combinations all use the same frame and state handling.
Active is selection; pressed is an independent modifier on either selection.
Pressing multiplies the content RGB channels by 1.5 and adds a soft, glyph-local
halo so near-white momentary icons still show a visible held state. The frame
does not change. Disabled uses the inactive frame, desaturates/dims the content and
has no clickable modifier or press interaction.

Dimensions use dp and text uses sp plus the selected font family. The full UI
scale applies to both panels. `ProvidePanelDimensions` shares one thickness:
sidebar width always equals climate height (78 dp before UI scaling). Both
panels use the same button/icon dimensions and height-based fitting, independent
of font scaling. All icon controls go directly through `NavButton` / `HvacButton`;
there is no separate climate-icon control. At narrow logical widths the central
climate controls scroll, leaving both temperature groups visible. Text is
measured against its actual font and bounds without increasing panel height.

`ClimateState` is the single source of selection state. `ClimateRepository`
atomically persists it in a separate DataStore, so it survives navigation,
theme/scale changes and process restarts. Each switch toggles independently;
FAN toggles on/off while keeping its last speed (off displays 0). Temperature
steps are whole degrees over a temporary local 16–30 °C range; the panel displays
only the number, without a unit suffix. SYNC copies the driver's
temperature when enabled and subsequently links both temperature controls.
These are local simulated states and never issue vehicle commands. A future
vehicle adapter can replace complete snapshots through the repository.

Widget settings use the existing in-window `WidgetOverlay`, with a short reveal
animation and no reserved content row. The three-dot controls overlay each card.
The Now Playing menu also owns its source selector and projection launches.
Widget internals remain a separate design pass.

## Factory behaviour

The saved native `com.desaysv.launcher.App.onConfigurationChanged` masks
`Configuration.uiMode` with `UI_MODE_NIGHT_MASK` (0x30) and applies day (0x10) or
night (0x20). Morning / evening uses that same Android configuration through
Compose. It does not calculate a second independent sunset time or require GPS.
Manual Day/Night remain local overrides.

Native navigation launches TurboDog's WelcomeActivity. Native Local settings
uses `com.desaysv.setting.ACTION_DEVICE_SETTING` and
`com.desaysv.setting.view.activity.SettingActivity`. Vehicle settings uses the
separate `com.desaysv.vehiclesetting` quick-control activity. These bindings are
kept in `VehiclePreset.kt`, not in the sidebar drawing code. The currently
selected OEM audio source is queried again when opening its PLAY_VIEW.

## Icons and visual workbench

All UI icons use Rounded. The named modern Material Symbols are local
VectorDrawable resources converted from Google's official Rounded/24 SVG
paths. Source and Apache license are in `third_party/material-symbols-rounded`;
the license also ships in the APK assets.

`ThemePreviewActivity` exists only in the debug source set. It shows the actual
components with Lorem ipsum or a state gallery. Intent extras select `theme`
(`day`, `night`, `auto`), `view` (`surfaces`, `states`, `launcher`), `uiScale`,
`textScale`, `font` and `sidebar`. Launcher view applies these settings only to
the debug package before opening the real screen. This is a visual workbench,
not a production settings screen.

Screenshots from the 1920×720 Android emulator are saved beside the project in
`design/theme-foundation-20261031` and `design/climate-controls-20261031`. Verification is visual; no tests were added
or run for this graphics change. The OEM app intents are checked against the
saved native implementation; vehicle launch behaviour still needs the car.
