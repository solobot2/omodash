<div align="center">
  <img width="256" height="256" alt="Omodash OD monogram" src="branding/omodash-icon.svg" />
  <h1>Omodash</h1>
  <p><strong>An open-source, offline-first Android launcher built specifically for aftermarket car head units.</strong></p>

  [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
  [![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](http://makeapullrequest.com)
</div>

---

## Omodash releases and in-car updates

This fork publishes signed APK updates to `solobot2/omodash`. See [release and update instructions](docs/releases.md) for signing-key backup, local builds, GitHub deployment and one-time setup in the vehicle.

For one-time USB permission setup, see [USB setup instructions](docs/usb-setup.md).
For crash reports and ZIP diagnostics sent from the car, see [diagnostic instructions](docs/diagnostics.md).
For CarPlay / Android Auto integration and factory radio, see [projection instructions](docs/projection.md).
The application ID is `win.solobot.omodash`; see [branding and package identity](docs/branding.md)
for the icon sources and installation after the package rename.

## 📖 Table of Contents
- [Why Build Another Car Launcher?](#-why-build-another-car-launcher)
- [The Philosophy](#-the-philosophy-offline-first--oem-aesthetics)
- [Current Features](#-current-features)
- [Roadmap & Future Plans](#️-roadmap--future-plans)
- [Contributing](#-contributing-open-source-first)
- [License](#-license)

---

## 🛑 Why build another car launcher?

I built this project for three simple reasons:

1. **No Premium Paywalls:** I didn't want to pay someone for basic dashboard functionality.
2. **Community-Driven:** I wanted it to be open-source. The car modding community is incredible, and I wanted to create a foundation that others could actually build upon, fork, and improve.
3. **Clean Aesthetics:** Let's be honest—most Android launchers look like cheap video games, have zero functionality, or half the features are broken. This is built to look clean, professional, and integrated.

## 🧠 The Philosophy: Offline-First & OEM+ Aesthetics

Most modern head unit setups rely on wireless CarPlay or Android Auto for navigation and media. That means the head unit itself is offline 90% of the time.

I designed this launcher around that reality. It is built to be functional without a Wi-Fi connection while remaining highly customizable. Whether you are installing this in a 2020 Corolla Hybrid or a custom project car, the goal is for the UI to look like it actually belongs in your car's interior—a true OEM+ aesthetic.

---

## ✨ Current Features

This project is currently in active development, but the core foundation is highly customizable:

<img width="2400" height="896" alt="Screenshot_20260527-025436" src="https://github.com/user-attachments/assets/a1bc63f3-2d4e-4ac0-bd56-b5d181681658" />
<img width="2400" height="896" alt="Screenshot_20260527-212602" src="https://github.com/user-attachments/assets/cf319144-0a06-4bc6-ab83-855ef8514a9c" />
<img width="2400" height="896" alt="Screenshot_20260527-025446" src="https://github.com/user-attachments/assets/cc038c53-dcf5-4b4b-bd73-bfcd18bd82d2" />
<img width="2400" height="896" alt="Screenshot_20260527-025451" src="https://github.com/user-attachments/assets/cc038c53-dcf5-4b4b-bd73-bfcd18bd82d2" />

### 🧩 Modular Widget Grid
The home screen is a 6×2 grid with draggable widgets. Each widget defines its own minimum and maximum size. The size picker shows only allowed width/height pairs, with unavailable sizes disabled. Compass and round gauges use 1×1 or 2×2; the altimeter is fixed at 1×1. Media controls and Trip Tracker require at least two columns. Add and remove widgets from the built-in library; automatic shrinking also respects these rules. Existing layouts and display-style changes are normalized without overlaps.

### 🎛️ Instrument Panel Widgets
A growing library of purpose-built car widgets designed to look like they belong on a dash — not a phone:

* **Smart Music Player** — uses the factory source ID and source-specific metadata on OMODA 9, including Android Auto sessions that Android marks inactive. Other head units use Android media sessions. Repeated artwork is retained without restarting image loading; seeking is enabled only for supported sources.
* **AM/FM Radio** — instrument panel-style digital display that mirrors your head unit's **real tuner**. On szchoiceway-based units it talks to the MCU directly (seek, FM1/FM2/FM3/AM band switching, direct-tune frequency presets with memory). On every other unit it mirrors and controls your vendor radio app through its media session — assign the app once from the widget and get live frequency/station readout plus seek controls. No simulated stations or fake static.
* **Speedometer** — standalone GPS-based digital speed readout. Independent from the trip tracker so it can live anywhere on the grid.
* **Altimeter** — live elevation tracking pulled from the device GPS.
* **Trip Meter** — taxi-style rolling odometer display with trip distance and elapsed time. Includes a hidden **0–100 km/h timer** (tap the meter label to reveal it) that auto-starts from standstill and locks in your time at 100.
* **Head Unit Vitals** — real-time CPU load, memory pressure, and temperature readouts for monitoring your head unit's thermals on long drives.
* **Soundboard** — 6 fully assignable sound pads. Each pad can be set to a built-in synth type (HORN, BEEP, ALERT, KICK, SNARE, BASS, FART) or loaded with any custom audio file from device storage. Assignments persist across restarts.
* **Dynamic Weather** — shows live conditions, a loading/location status, or a clear unavailable message with Retry. The card remains visible without data, and failed requests retry automatically.
* **GPS Compass** — live bearing with calibrated heading display.

### 🗂️ App Library
Lists every installed app, including system-level apps and CarPlay / Android Auto receivers without ordinary launcher entries. App icons fill each tile, with the name overlaid along the bottom edge.

### 📌 Navigation and climate panels
The sidebar can sit on the left or right. Its upper routes are Navigation, Car Settings, Local Settings and the currently selected audio source, all resolved through the vehicle preset. The bottom destinations are fixed: Home, launcher Settings and Applications, separated from vehicle routes by a divider. The full-width lower panel contains climate controls with persistent local on/off and temperature state. FAN remembers its last speed; SYNC links temperatures. Vehicle commands are not connected yet. Sidebar width equals climate height, and both panels share vector icon and button dimensions at every UI/text scale.

### 🌗 Day/night theme
Fixed day and night palettes provide gradient surfaces, rounded widget shells, border highlights and separate navigation/climate selection styles. Morning / evening follows Android's `Configuration.uiMode`, exactly as the factory OMODA launcher does; Dark and Light are manual overrides. Theme colours are not editable. See [theme components and visual verification](docs/theme-foundation.md).

### 🛰️ GPS with Offline Calibration
Speed and distance calculations use a rewritten GPS math layer with improved filtering. A calibration offset option is available for devices whose GPS chips report inaccurate baselines — accessible from the trip meter settings for reliable offline use.

### 🎨 Deep Personalization
Wallpaper dim, font weight, text scale, UI scale and font family remain configurable. Panel chrome uses the same selected density and typography as the rest of the launcher. At large combined scales, temperature controls remain at the edges and central HVAC controls scroll horizontally.

### Android Auto
Open CarPlay and Open Android Auto are in the Now Playing three-dot menu and launch their preset receiver apps. The sidebar’s Now Playing route opens the actual selected OEM audio source. See [setup and compatibility](docs/projection.md).

### 🔔 First-Run Onboarding
A clean onboarding flow on first launch explains key permissions (location, notification listener, draw-over-apps) before requesting them, with direct links to the relevant system settings screens.

---

## 🗺️ Roadmap & Future Plans

The current priority is **stability and universal compatibility** — ensuring the launcher scales correctly across the wide range of aftermarket head unit resolutions and hardware specs.

Remaining targets:

- [ ] **Offline Weather via FM/RDS:** A highly experimental goal to pull local weather data directly from FM radio bands (RDS/TMC) using the car's physical antenna — bypassing Wi-Fi entirely.
- [ ] **Universal Theming Engine:** A standardized platform for the community to build, share, and install full visual themes.

---

## 🤝 Contributing (Open Source First)

This project is open-source because it takes a community to build something that works across hundreds of different head unit models. Whether you are a developer, a designer, or just someone testing it in your car, your help is welcome!

### How you can help:
1. **Test on your hardware:** Install the APK on your specific head unit, break things, and submit Bug Reports in the [Issues tab](../../issues).
2. **Feature Requests:** Have a cool idea? Open a discussion.
3. **Pull Requests:** See a bug you can fix or a feature you want to add? Fork the repo and submit a PR. *(Please check the issues tab first to see what is currently being worked on!)*

---

## Donate

This app is fully free and open-source, a donation isn't required but would be greatly appreciated to help support the constant updates and fixes planned based on your suggestions!

[![Donate with PayPal](https://raw.githubusercontent.com/stefan-niedermann/paypal-donate-button/master/paypal-donate-button.png)](https://paypal.me/dw2lam)
