# Aspectly

Control how apps are shaped on Samsung foldables.

On the Galaxy Z Fold 8, the cover screen is **1248x1972 (10:16)**. Short-form video is
**9:16**. The screen is wider than the video, so apps that fill-and-crop chop the top
and bottom off every Reel. Aspectly forces an app into a 16:9 window so the whole frame
fits, and lets you park the resulting black bar on whichever side suits your grip.

Measured on a real Fold 8: a 16:9 window comes out **1111x1972** — a ratio of 0.5634
against 9:16's 0.5625. The crop is gone.

## How it works

Android only lets privileged processes change how other apps are windowed. Aspectly
connects to the device's own ADB daemon over loopback and runs a **fixed, hardcoded set
of commands** — four compat overrides that set an app's minimum aspect ratio, and one
that controls letterbox position.

There is no Shizuku dependency. Aspectly does the ADB pairing itself, so setup is one
pairing and then nothing.

## Restarting your phone undoes everything

Samsung repopulates the `OVERRIDE_MIN_ASPECT_RATIO_LARGE` override map at boot,
clobbering user entries. Verified twice on hardware. The flags that *do* survive have
no effect without it.

This is a feature, not a bug: **if anything ever goes wrong, restart the phone.**
Nothing Aspectly does survives a reboot on its own — no app, no ADB, no uninstall
required. Aspectly reconnects and reapplies your settings automatically afterwards.

## Transparency

- **No analytics. Nothing is transmitted anywhere, ever.**
- A local activity log records every shell command Aspectly runs, with timestamps.
  Readable in-app, exportable, clearable. Open source proves what the app *can* do; the
  log proves what it *did*.
- The command set is hardcoded and auditable. Nothing exported accepts a command from
  another app.

## Known limits

**Fold-state preservation is impossible.** The Fold 8 exposes two physical displays
(id 0 = cover 1248x1972, id 1 = inner 2448x1848). Folding moves the task between them,
which fires a configuration change no matter what. Apps that reload on config change
will keep doing so, and no external tool can prevent it.

**This will stop working for some apps over time.** `DISABLE_OPT_OUT_UNIVERSAL_RESIZABLE_BY_DEFAULT`
(change id 447301631) is not overridable and fires at `targetSdk=37`. Once an app
targets Android 17, its shape can no longer be changed by anything. Aspectly detects
this and tells you plainly rather than failing silently. It is not fixable.

## Building

Requires JDK 17+ and the Android SDK.

```
./gradlew assembleDebug
```

## License

GPL-3.0. Chosen deliberately: anyone distributing a modified Aspectly must publish
their source too. For a tool that holds shell access to people's phones, "you can
always read what's running" should survive forks.
