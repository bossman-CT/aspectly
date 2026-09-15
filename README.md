# Aspectly

Control how apps are shaped on Samsung foldables.

On the Galaxy Z Fold 8, the cover screen is **1248x1972 (10:16)**. Short-form video is
**9:16**. The screen is wider than the video, so apps that fill-and-crop chop the top
and bottom off every Reel. Aspectly forces an app into a 16:9 window so the whole frame
fits, and lets you park the resulting black bar on whichever side suits your grip.

Measured on a real Fold 8: a 16:9 window comes out **1111x1972** — a ratio of 0.5634
against 9:16's 0.5625. The crop is gone.

## Why does this need ADB access?

Short answer: **Android gives no other way to do it.** Changing how a *different* app
is windowed is a privileged operation. There is no public API, no permission you can
request, and no user-facing setting that covers it — Samsung's own "App aspect ratios"
menu does not offer the control for the apps that need it most.

So Aspectly connects to the device's own ADB daemon over **loopback (127.0.0.1)**. That
is a high level of access and you should treat it as such. Here is exactly what it
means.

**What Aspectly does with it** — five commands, and no others:

```
am compat disable UNIVERSAL_RESIZABLE_BY_DEFAULT        <package>
am compat disable OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY <package>
am compat enable  OVERRIDE_MIN_ASPECT_RATIO             <package>
am compat enable  OVERRIDE_MIN_ASPECT_RATIO_LARGE       <package>
wm set-letterbox-style --horizontalPositionMultiplier ... --cornerRadius 0
```

They are built in [`AspectlyCommands.kt`](app/src/main/java/io/github/bossmanct/aspectly/adb/AspectlyCommands.kt),
package names are regex-validated against installed packages, and there is no code path
that runs a string from anywhere else.

**What it never does:**

- Run a command you did not trigger
- Accept a command from another app — `MainActivity` is the only exported component and
  it takes no input. An exported surface that accepted commands would turn any bug here
  into a device-wide privilege escalation
- Send anything off your device. There is no analytics SDK, no crash reporter, no
  telemetry. Traffic goes to `127.0.0.1` and nowhere else

**The key:** pairing generates an RSA key that is, in effect, shell access to your
phone. It is encrypted at rest with a hardware-backed AES key in the Android Keystore,
and `allowBackup` is disabled so it cannot leave in a cloud backup.

**You can revoke it any time:** Settings → Developer options → Wireless debugging → off.
Aspectly stops working immediately.

**And you can check all of this**, which is the point of the repo being public. There is
also an in-app activity log recording every command Aspectly has run, with timestamps —
open source proves what the app *can* do, the log proves what it *did*.

## How it works

Aspectly does the ADB pairing itself, so there is no Shizuku dependency and setup is one
pairing and then nothing. It discovers adbd by scanning loopback, because Android
randomises the wireless debugging port on every boot — see
[ADB-NOTES.md](ADB-NOTES.md) for why mDNS is no longer an option and eleven other things
that were not documented anywhere.

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
