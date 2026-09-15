# Aspectly — spike results

Verified on a real AOSP **Android 16 (API 36)** emulator, `google_apis` x86_64, booted
at **1248x1972 @ 420dpi** to match the Galaxy Z Fold 8 cover screen.

AOSP is not One UI. These results prove the *mechanisms exist and the syntax is right*.
Whether Samsung honors each one still needs the device — but see the wallpaper finding
below, which is strong evidence it does.

---

## Confirmed

**1. The cover screen is below the 600dp line.**

```
overrideConfig={... sw475dp w475dp h751dp 420dpi nrml port ...}
```

**475dp** smallest-width. Android 16's "ignore orientation / aspect-ratio /
resizability restrictions" behavior applies only at >= 600dp, so it does not reach the
cover screen. Scoping v1 to folded state sidesteps most platform churn.

**2. Letterbox positioning works, with a clean undo.**

| Command | Result |
|---|---|
| `wm set-letterbox-style --horizontalPositionMultiplier 1.0` | applied — app flush right, bar on left |
| `--defaultPositionForHorizontalReachability right` | `..._POSITION_RIGHT` |
| `--backgroundType wallpaper --wallpaperBlurRadius 60` | `LETTERBOX_BACKGROUND_WALLPAPER`, stored as 157px (dp x 2.625) |
| `wm reset-letterbox-style` | restored `0.5` / `SOLID_COLOR` |

AOSP defaults worth knowing: horizontal multiplier `0.5`, horizontal reachability
already `true`, letterbox aspect ratio `1.333`, user aspect-ratio settings `true`.

**3. Samsung is running this exact subsystem.** AOSP's default letterbox background is
`LETTERBOX_BACKGROUND_SOLID_COLOR` / `ff000000` — plain black. The Fold 8 shows
*blurred wallpaper*, which is `backgroundType=wallpaper` plus a blur radius. Samsung
configured AOSP's letterbox rather than replacing it.

**4. All the compat change IDs exist and every one is `overridable`.**

`OVERRIDE_MIN_ASPECT_RATIO` (174042980), `_LARGE` (180326787), `_MEDIUM` (180326845),
`_SMALL` (349045028), `_PORTRAIT_ONLY` (203647190), `FORCE_NON_RESIZE_APP` (181136395),
`FORCE_RESIZE_APP` (174042936), `OVERRIDE_ANY_ORIENTATION` (265464455),
`OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT` (265452344).

**5. The Android 16 behavior change has a per-app escape hatch.**

```
ChangeId(357141415; name=UNIVERSAL_RESIZABLE_BY_DEFAULT; enableSinceTargetSdk=36; overridable)
```

`am compat disable UNIVERSAL_RESIZABLE_BY_DEFAULT <pkg>` was accepted and recorded as
`packageOverrides={<pkg>=false}`. The platform shift I flagged as the headwind can be
opted out of per-app at shell privilege — at least on API 36.

**6. `am compat reset-all <pkg>` fully clears overrides.** Verified: no residue.

---

## The working recipe (verified visually)

Two non-obvious gates have to be cleared first. Neither is documented anywhere
obvious, and missing either produces a silent no-op — the commands all report success
and nothing happens.

**Gate 1 — `OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY` is enabled by default.** It
restricts min-aspect-ratio to fixed-portrait activities. Any app that isn't
orientation-locked is skipped entirely.

**Gate 2 — horizontal reachability overrides the position multiplier.** With
reachability on (the AOSP default), the *reachability position* governs and
`horizontalPositionMultiplier` is ignored. `get-letterbox-style` will happily report
your multiplier while the app stays centered on screen.

Full sequence that actually works:

```bash
PKG=com.facebook.katana

# make the app eligible for letterboxing at all
adb shell am compat disable UNIVERSAL_RESIZABLE_BY_DEFAULT $PKG
adb shell am compat disable OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY $PKG
adb shell am compat enable  OVERRIDE_MIN_ASPECT_RATIO $PKG
adb shell am compat enable  OVERRIDE_MIN_ASPECT_RATIO_LARGE $PKG

# put the whole bar on the left, app flush right, square corners
adb shell wm set-letterbox-style --isHorizontalReachabilityEnabled false \
                                 --horizontalPositionMultiplier 1.0 \
                                 --cornerRadius 0

# overrides apply at launch, not live
adb shell am force-stop $PKG
```

> **Do NOT add `FORCE_NON_RESIZE_APP`.** It is not needed — letterboxing works without
> it — and on the real Fold 8 it makes the app **close outright** when you unfold and
> refold. A non-resizable task cannot be re-hosted across the two physical displays, so
> it gets killed instead of moved. Verified: with the flag, Facebook died on every fold
> cycle; without it, letterboxing is identical and the app survives folding.
>
> Samsung ships its own curated `FORCE_NON_RESIZE_APP` list covering hundreds of
> packages (`com.facebook.lite` among them). Leave that list alone.

`--horizontalPositionMultiplier 0.0` mirrors it (bar on the right, app flush left), so
the handedness toggle is a single float.

**Measured result:** on the 1248x1972 cover geometry, 16:9 produced a window
**1109px** wide — exactly 1972 / 1.778 as predicted — leaving 139px of bar, ~70px per
side when centered, or all 139px on one side when positioned. Screenshots in `shots/`.

**Also disproved:** the 600dp threshold does *not* gate min-aspect-ratio overrides.
Tested at both 475dp and 624dp (via `wm density`) with identical results. The 600dp
line matters for Android 16's restriction-stripping, not for the override tools.

---

## The persistence asymmetry — drives the architecture

> **Corrected after testing on the real Fold 8.** The AOSP result below is real but
> does NOT generalize to One UI. See "Reboot behavior on One UI" immediately after.

Rebooted the emulator with both kinds of setting applied:

| Setting | Survives reboot? |
|---|---|
| `am compat` overrides (aspect ratio, orientation, resizability) | **Yes** — all three still recorded |
| `wm set-letterbox-style` (bar position, background) | **No** — reverted to `0.5` |

### Reboot behavior on One UI — the real answer

Rebooted the actual Fold 8 with the full recipe applied:

| Flag | Carries a Samsung list? | Survived reboot? |
|---|---|---|
| `OVERRIDE_MIN_ASPECT_RATIO` | no | **yes** |
| `OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY` | no | **yes** |
| `UNIVERSAL_RESIZABLE_BY_DEFAULT` | no | **yes** |
| `OVERRIDE_MIN_ASPECT_RATIO_LARGE` | **yes, hundreds of packages** | **NO — wiped** |
| `wm set-letterbox-style` | n/a | **no** |

`areBoundsLetterboxed` came back **false** after reboot. The crop fix was gone.

**Mechanism:** Samsung appears to repopulate the override maps for change IDs it
curates its own package list for, rebuilding them at boot and clobbering user entries.
Every flag that survived is one Samsung does not maintain a list for.

**No workaround exists.** `_MEDIUM` (3:2 = 1.5) and `_SMALL` are both clean of Samsung
lists and would likely persist — but the cover screen is already 1.58, and a minimum
below the display's own ratio letterboxes nothing. Only `_LARGE` yields 16:9, and
`_LARGE` is the one that gets wiped.

**Consequence:** the app must reapply on every boot. Reproduced twice.

### But reboot is also a free, automatic undo — design around this

The flag that actually changes behavior (`_LARGE`) is the one Samsung wipes. The flags
that persist do nothing on their own without a value flag — measured directly:
`areBoundsLetterboxed=false` after restart with all three still recorded.

So **restarting the phone returns it to stock**, with no app, no Shizuku, and no adb
required. A user who uninstalls Aspectly, or can't get Shizuku running again, or just
panics, is one restart from clean. Say so in onboarding — it is literally true, and it
is the strongest safety guarantee a tool in this category can make. It also removes
the uninstall hazard entirely.

### Wireless debugging survives reboot — the re-arm is short

Tested on the device: enabled Wireless debugging, cold booted, then checked at 0m
uptime.

| Check | Result |
|---|---|
| `adb_wifi_enabled` | **1** — toggle persisted |
| `adb_enabled` | 1 — USB debugging persisted too |
| `init.svc.adbd` | **running** |
| listening socket | **uid 2000, port 0xA9A9 (43433), state LISTEN, all interfaces** |

Not just the flag — adbd is genuinely listening over the network after a cold boot. So
the user does **not** have to re-enable wireless debugging after every restart, which
was the worst-case assumption.

Expected re-arm flow, roughly four taps: open Shizuku -> Start -> open Aspectly ->
Restore.

**Still unverified:** whether Shizuku's pairing persists across reboot and whether its
Start button works without re-pairing. Shizuku is not yet installed on the test device.
That is the last unknown in this chain and it determines whether re-arm is ~4 taps or
closer to ~8.

Three consequences:

- **The reset button is critical, not cosmetic.** Compat overrides persist across
  reboots, so a bad setting sticks forever. If a user uninstalls Aspectly with
  overrides active, there is no in-app way back. Prominent global reset, and explain
  this on first run.
- **Bar positioning needs re-applying at every boot.** And since it needs shell
  privilege, that means Shizuku must be alive — but Shizuku dies on reboot and needs
  manual restart. So the feature you like most is the one with the worst persistence
  story. Worth solving deliberately rather than discovering late.
- **Letterbox style is global, not per-app.** There is no package argument. Per-app
  positioning is not available through this API; only per-app *aspect ratio* is.

---

## Confirmed on the real device

Galaxy Z Fold 8, **SM-F971U**, **Android 17 / SDK 37**, over USB adb.

**One UI honors the letterbox API for both reads and writes.** `get-letterbox-style`
returns the full structure; `--horizontalPositionMultiplier 1.0` read back as `1.0`.
Samsung's defaults differ from AOSP in ways that help: **reachability is already off**
and **corner radius is already 0**, so both emulator gates are absent here.

**The recipe works on Facebook.** `areBoundsLetterboxed=true` on the cover screen,
window ~1111x1972 against the predicted 1109 — and 0.5634 vs 9:16's 0.5625, so the
Reels crop is gone. Screenshot: `shots/fb-cover.png`.

**Test 0 passed.** Backgrounding Facebook for 30s and reopening does *not* reset the
feed, so the reload really was configuration-driven.

**Test 3.5 — two separate physical displays, and this kills the fold-state idea.**

| | displayId | resolution | uniqueId / port |
|---|---|---|---|
| Cover | **0** | 1248x1972 | `local:4630947123231501204` / 148 |
| Inner | **1** | 2448x1848 | `local:4630947004648141459` / 147 |

Folding moves the task *between displays*, which fires a configuration change no
matter what size the window is. Geometry pinning cannot suppress it. Test 4 is moot.

**Samsung already uses compat overrides at scale.** `FORCE_NON_RESIZE_APP` carries a
Samsung-curated list of hundreds of packages. This is shipping infrastructure to them,
not an obscure corner of the API.

---

## Samsung Auto Blocker — affects distribution more than runtime

**Enabled by default on One UI 6.1.1 and later**, so assume it is on for every target
user unless they deliberately turned it off.

**Confirmed on the test device:** it silently turned USB debugging back off mid-session.
Windows still enumerated the phone as a composite USB device, but no ADB interface was
exposed and `adb devices` returned empty. Not a cable or driver fault — Auto Blocker
doing what it advertises.

Two consequences:

**1. Runtime is probably fine.** Auto Blocker blocks commands over *USB cable*.
Wireless debugging survived a cold boot with `adbd` listening on the network while Auto
Blocker was active, so loopback ADB — which is what Aspectly uses — appears unaffected.
Still worth an explicit test rather than relying on that inference.

**2. Distribution is not fine, and this inverts the earlier plan.** Auto Blocker blocks
app installs from anywhere except Play Store and Galaxy Store. **F-Droid and GitHub
Releases are blocked by default on exactly the devices Aspectly targets.** A user would
have to disable a security feature to install a tool that then asks for shell access —
a bad first impression and a worse security narrative.

Revised channel priority for a Samsung-only utility:

| Channel | Role |
|---|---|
| Google Play | **Primary** — reaches users without touching Auto Blocker |
| Galaxy Store | **Primary** — Samsung-native, same exemption |
| F-Droid | Secondary — build-from-source verification, reaches the minority with Auto Blocker off |
| GitHub Releases | Tertiary — blocked by default, but needed for transparency |

F-Droid still earns its place: it independently builds from source, which is the whole
verification story. It just cannot be the path most users take.

---

## Shelf life — the one thing outside your control

```
ChangeId(447301631; name=DISABLE_OPT_OUT_UNIVERSAL_RESIZABLE_BY_DEFAULT; enableSinceTargetSdk=37)
```

Note what is absent: every other change ID ends in `overridable`. **This one does
not.** It removes the ability to opt out of universal-resizable, and it cannot itself
be overridden.

It fires on `targetSdk=37`. Facebook currently targets **36**, so the recipe works
today. When Facebook (or any target app) bumps to 37, forced aspect ratio stops working
for it and there is no shell-level workaround.

### Measured on the test device (2026-09-15)

219 third-party apps, by `targetSdk`:

| targetSdk | Apps | Status |
|---|---|---|
| <= 35 | 80 | controllable |
| 36 | 102 | controllable — **this is the runway** |
| 37 | 37 | **already locked** |

**17% are uncontrollable today.** The "Not supported" badge is not a rare edge case —
users meet it on the first scroll, which is why it has to explain itself rather than
just grey out a switch.

Counting *all* packages gives 49.5%, but that is skewed by Samsung and Google system
apps which all target latest. The third-party figure is the honest one.

**Projection:** Play policy forces annual target bumps, so most of the 102 apps on 36
move to 37 within roughly a year — putting the device around **63% locked**. Useful
now, meaningfully degraded within a year, largely gone in two. Plan the product's
lifetime around that rather than hoping.

---

## Still open

1. Does One UI expose "App aspect ratios" in Settings for Facebook at all? (User could
   not find the menu; never confirmed whether it is absent or was just folded.)
2. Do compat overrides persist across reboot on One UI as they do on AOSP? (Assume yes
   until measured — it drives whether a boot receiver is needed.)
3. Does `wm set-letterbox-style` survive reboot on One UI? (On AOSP it does not.)

---

## Reproducing the emulator

```powershell
cd "C:\Users\thorn\Coding Projects\Android\Aspectly"
$env:ANDROID_SDK_ROOT="$PWD\sdk"; $env:ANDROID_AVD_HOME="$PWD\avd"
.\sdk\emulator\emulator.exe -avd cover -no-window -no-audio -no-boot-anim -no-metrics -gpu swiftshader_indirect
```

AVDs: `cover` (1248x1972 @420dpi, Fold 8 cover geometry) and `fold`
(7.6" Fold-in with outer display, for hinge/posture testing).
