# Fold-reload spike — Z Fold 8 / Facebook

**Question this answers:** can pinning an app's window geometry stop the feed from
reloading when you fold or rotate? If no, there is no product.

`PKG=com.facebook.katana` throughout. Commands assume adb from a PC. On-device via
Shizuku, drop the `adb shell` prefix.

---

## Scope finding: the cover screen is below the 600dp line

Android 16's "ignore orientation / aspect-ratio / resizability restrictions" behavior
applies only to displays with **smallestScreenWidthDp >= 600**.

The cover screen is 1248 x 1972 at roughly 424ppi. That puts it near 420-450dpi
density, so its width in dp lands around **416-475dp** — comfortably under the
threshold. Confirm on device:

```bash
adb shell wm size
adb shell wm density
adb shell dumpsys window displays | grep -iE "smallest|cur="
```

Fold and unfold while running the last one and compare.

**If this holds, it reframes the whole risk picture.** The Android 16/17 changes bite
on the *inner* screen and miss the cover screen entirely. Restriction-based mechanisms
keep working where the actual problem is. Scope v1 to the cover screen and most of the
platform churn stops mattering.

The broader direction is also friendlier than it first looks: Android 16/17 strips
control from *apps* while Android 14 deliberately added per-app aspect-ratio control
for *users*. The platform's position is that the user decides how apps are sized —
which is this product's thesis. Google just isn't shipping fine-grained enough
controls, and ships none at all that are fold-state aware.

---

## Test 0 — the free disqualifier (2 min, no tools)

Do this before touching adb.

1. Open Facebook, scroll to a distinctive post, note it.
2. Home button. Wait 30s. Reopen Facebook.
3. Also try: wait 5 min, reopen.

**If the feed resets without any fold or rotation**, Facebook is refetching on resume
or on a staleness timer. The hinge is irrelevant, no window trick can help, and the
idea is dead. Stop here.

**If the feed survives**, the reload is genuinely config-change-driven. Continue.

---

## Test 1 — rotation lock (control experiment only)

This is a diagnostic, not a proposed fix. Global rotation lock is not an acceptable
end state — auto-rotate stays on except when reading or lying down.

```bash
adb shell settings put system accelerometer_rotation 0
```

Rotate the unfolded device. Feed should be untouched, because no config change fires.

This validates the whole hypothesis cheaply. If locking rotation does *not* stop the
reload, then rotation isn't what's triggering it and the diagnosis is wrong.

Restore immediately:

```bash
adb shell settings put system accelerometer_rotation 1
```

**The shippable version of this is per-app rotation lock** — auto-rotate stays on
globally, Facebook alone is pinned to portrait. That category already exists on Play
(orientation-manager apps using an accessibility service, no root). If Test 1 passes,
install one, pin Facebook, and the rotation half of the problem is solved today with
no code. That also narrows the remaining build to the fold transition alone.

---

## Test 2 — instrument the reload

Two terminals.

**Terminal A — watch for relaunches:**

```bash
adb logcat -c && adb logcat -v time | grep --line-buffered -iE "relaunch|config changes|sizecompat|scheduleTransaction"
```

**Terminal B — snapshot window state before/after each fold:**

```bash
adb shell dumpsys activity activities | grep -iE "mResumedActivity|taskId|bounds|sizeCompat|compatScale"
```

Now fold and unfold with Facebook open. You are looking for `Relaunching` lines
attributable to the Facebook task, and for the reported `bounds` changing.

Record: does `bounds` change on fold? does a relaunch fire? does the feed reset?

---

## Test 3 — fixed minimum aspect ratio

First see which override flags this device actually has:

```bash
adb shell dumpsys platform_compat | grep -iE "aspect|orientation|resiz"
```

Then apply. `OVERRIDE_MIN_ASPECT_RATIO` is the gate; the `_LARGE` flag supplies the
16:9 value. Both are needed.

```bash
adb shell am compat enable OVERRIDE_MIN_ASPECT_RATIO com.facebook.katana
adb shell am compat enable OVERRIDE_MIN_ASPECT_RATIO_LARGE com.facebook.katana
adb shell am force-stop com.facebook.katana
```

Relaunch Facebook (overrides apply at launch, not live). Repeat Test 2.

Secondary thing to check here: does this also fix the Reels top-crop on the cover
screen? That's the original problem and it may be solved independently of the reload.

---

## Test 3.5 — one display or two? (run before Test 4)

This can kill Test 4 in thirty seconds, so do it first.

```bash
adb shell dumpsys display | grep -iE "mDisplayId|uniqueId|state="
```

Run folded, then unfolded, and diff.

- **Same display id, changing size** → the task stays put and only the configuration
  resizes. Pinning geometry could genuinely suppress the config change. Test 4 is
  worth running.
- **Different display ids** → folding *moves the task between displays*, which fires a
  configuration change no matter what size the window is. Geometry pinning cannot help
  and Test 4 is pointless. The fold half of the product dies here; the cover-screen
  aspect-ratio half still stands on its own.

---

## Test 4 — force non-resizable / size-compat

Size-compat mode scales a fixed-size window rather than resizing it, so in principle
the app never sees a new configuration. Get the task id from Test 2's dumpsys output,
then:

```bash
adb shell am task resizeable <TASK_ID> 0
```

Fold. If the window is letterboxed and *scaled* rather than re-laid-out, and no
relaunch fires, that is the mechanism the product would be built on.

**Verify syntax first** — `am task resizeable` argument order varies across versions
and Samsung has been known to diverge. Run `adb shell am task` bare to see usage.

---

## Test 4.5 — letterbox positioning (run before Test 5; may obsolete it)

Put the whole bar on one side and pin the app flush to the opposite edge, instead of
splitting the bar into two thin strips.

This is already happening on this device — in the unfolded screenshots Facebook sits
flush **right** with the wallpaper filling the left. So the system can do it. The
question is whether that positioning is controllable.

Flags below are verified against AOSP `WindowManagerShellCommand.java`, not guessed.

```bash
# read current state first
adb shell wm get-letterbox-style

# 0.0 = flush left, 0.5 = centered, 1.0 = flush right
adb shell wm set-letterbox-style --horizontalPositionMultiplier 1.0

# more direct: named position
adb shell wm set-letterbox-style --defaultPositionForHorizontalReachability right

# double-tap the bar to shift the app left/center/right
adb shell wm set-letterbox-style --isHorizontalReachabilityEnabled true
adb shell wm set-letterbox-style --persistentPositionForHorizontalReachability right

# undo
adb shell wm reset-letterbox-style
```

Full verified flag set for `set-letterbox-style`:

| Flag | Values |
|---|---|
| `--aspectRatio` | float |
| `--minAspectRatioForUnresizable` | float |
| `--cornerRadius` | int px |
| `--backgroundType` | `solid_color`, `app_color_background`, `app_color_background_floating`, `wallpaper` |
| `--backgroundColor` | `#RRGGBB` or named |
| `--backgroundColorResource` | e.g. `@android:color/system_accent2_50` |
| `--wallpaperBlurRadius` | int dp |
| `--wallpaperDarkScrimAlpha` | float 0.0-1.0 |
| `--horizontalPositionMultiplier` | float 0.0-1.0 |
| `--verticalPositionMultiplier` | float 0.0-1.0 |
| `--isHorizontalReachabilityEnabled` | true/1/false/0 |
| `--isVerticalReachabilityEnabled` | true/1/false/0 |
| `--defaultPositionForHorizontalReachability` | `left`, `center`, `right` |
| `--defaultPositionForVerticalReachability` | `top`, `center`, `bottom` |
| `--persistentPositionForHorizontalReachability` | `left`, `center`, `right` |
| `--persistentPositionForVerticalReachability` | `top`, `center`, `bottom` |
| `--isEducationEnabled` | true/1/false/0 |

Also relevant, display-scoped:

```bash
adb shell wm get-ignore-orientation-request -d 0
adb shell wm set-ignore-orientation-request -d 0 true
```

**Evidence Samsung is on this code path:** the letterbox areas in the unfolded
screenshots show *blurred wallpaper*, not black. That is AOSP's `--backgroundType
wallpaper` combined with `--wallpaperBlurRadius`. Plain black bars would have told us
nothing, but blurred wallpaper is a fingerprint of the stock letterbox implementation
— so these commands have a good chance of working rather than silently no-op'ing.

**Why this is the important test.** If the app is flush against the edge the user's
thumb is already on, there is nothing to forward — the bar is inert dead space on the
far side, and the entire touch-injection problem in Test 5 dissolves. A "which side?"
toggle is a few lines of code. Event injection is the riskiest thing in this document.

Two caveats to confirm:

- `set-letterbox-style` is **global**, not per-app. Combined with per-app aspect ratio
  that may still be enough, but it is not fine-grained.
- Samsung may implement its own letterbox and ignore the AOSP flags entirely. If the
  commands run but nothing moves, that is the answer.

---

## Test 5 — can the black bars forward a scroll? (only if 4.5 fails)

Touches in the letterbox belong to WindowManager, not the app. Forwarding them means
capturing on an overlay and **injecting synthetic events** into the app's window.
There is no pass-through API.

Cheapest possible probe — does injection reach Facebook at all?

```bash
adb shell input swipe 600 1500 600 500 300
```

Run that with the feed open. Then try a fast one (`... 80`) and watch whether it
flings with momentum or just jumps.

What to judge:

- Does it scroll at all, or does Facebook ignore injected events?
- Does a fast swipe produce a natural fling, or a dead stop?
- Latency — `input` spawns a process per call and is far too slow for real scrolling.
  The real implementation binds `IInputManager` through Shizuku and calls
  `injectInputEvent` directly. If even the slow version feels wrong, the fast version
  will only fix the lag, not the physics.

The hard part is not injection, it is fidelity: reproducing a continuous touch stream
with correct timestamps, pressure and velocity so flings feel native. Get this wrong
and the feature meant to protect the experience is what degrades it.

**Alternative path:** an AccessibilityService can `dispatchGesture()` with no Shizuku
and no root — but Play policy on accessibility services used for non-accessibility
purposes is strict enough to be a genuine distribution risk. Check policy before
choosing this route, not after.

**Question whether it's needed at all:** forcing 16:9 on the 1248px cover screen
leaves ~69px of bar per side, roughly 4mm. A thumb lands in the content area anyway.
The bars are only large on the inner screen, where there is already plenty of room.
Consider cutting this feature and scoping to the cover screen.

---

## Cleanup

```bash
adb shell am compat reset-all com.facebook.katana
adb shell settings put system accelerometer_rotation 1
adb shell am force-stop com.facebook.katana
```

Reboot afterwards to be certain nothing persisted.

---

## Decision matrix

| Result | Meaning |
|---|---|
| Test 0 resets the feed | Dead. FB refetches on resume; window geometry is irrelevant. |
| Relaunch suppressed, feed survives fold | **Viable.** This is the product. |
| Relaunch suppressed, feed still resets | Dead from outside. FB refetches for its own reasons; needs Xposed. |
| Relaunch always fires regardless | Config change unavoidable at shell privilege. Dead without root. |
| Works, but only while folded/unfolded in one direction | Partial. Worth a second look, narrower pitch. |

---

## Design note: the escape hatch is a v0 feature, not a nice-to-have

Every app needs a per-app **"reset — let Android handle it"** and a global **"reset
everything"**. Technically trivial:

```bash
adb shell am compat reset-all com.facebook.katana
adb shell wm reset-letterbox-style
```

It matters far more than its cost:

- It is the trust feature. Users will only experiment with window geometry if getting
  back to stock is one obvious tap.
- It is the hedge against platform churn. When a future Android changes how overrides
  behave, "reset and let Android decide" is always a valid, correct state.
- It is the support answer. Most bug reports become "tap reset" instead of a
  teardown.

**Uninstall hazard to design around:** if someone removes the app while overrides are
active, the overrides persist and there is no in-app way back. Options: prompt on
first run to explain, keep a one-tap global reset prominent, and document the manual
adb commands.

Related, worth confirming in the spike: if `am compat` overrides genuinely do not
survive a reboot, that annoyance doubles as a **safety property** — the blast radius
of any bad setting is one restart, and no user can permanently wedge their phone. That
changes how aggressive the defaults can afford to be.

---

## Note on platform direction

Android 16 already ignores app-declared orientation/aspect-ratio/resizability
restrictions on displays ≥600dp, and the opt-out property goes away when apps target
Android 17. Tests 3 and 4 lean on restriction mechanisms that Google is actively
dismantling. User-initiated overrides are the sanctioned path and should survive, but
confirm which category you land in before building anything on top of this.
