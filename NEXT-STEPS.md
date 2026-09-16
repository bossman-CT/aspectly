# Where this is, and what to do next

Last updated 2026-09-15.

## What works, proven on hardware

Galaxy Z Fold 8 (SM-F971U, Android 17 / SDK 37).

- **Per-app 16:9** kills the Reels crop on the 1248x1972 cover screen
- **Bar positioning** — left / centre / right, square corners
- **In-app ADB** — pairs itself, no Shizuku, no root, no PC
- **Port discovery** — finds adbd on a rotating port in ~1.5s by scanning loopback
- **Self-repair** — turns debugging back on after a reboot and restores unattended,
  *even with Auto Blocker fighting it.* Verified: Auto Blocker disabled debugging three
  times, the retry loop won on the fourth attempt, and Facebook's override was live
  with zero user interaction.

## The open bug — start here

**Symptom:** `Connect and apply` reports success connecting, then the first shell
command never completes.

```
21:27:36  AUTOCONNECT success on 45923 in 53ms
21:27:36  RUN  am compat disable UNIVERSAL_RESIZABLE_BY_DEFAULT com.facebook.katana
21:28:06  RESTORE attempt 2          <- 15s timeout fired, worker retried
```

So `connect()` returns true quickly, and then `openStream("shell:...")` hangs. It is
not the connection failing to establish — it is failing to be *usable*.

**What is already ruled out:**

- Not the session lock. That was a separate bug (fixed) where a background restore and
  a user tap tore down each other's connection.
- Not a missing timeout. 15s per command, 8s per connect probe, 45s on the lock are all
  in place now, so nothing hangs forever — the UI correctly reports "Another restore is
  still running" instead of freezing.
- Not adbd being absent. It was advertising and reachable from a PC at the same moment.

**What to try first:**

1. **Read the log.** `AdbShell` now wraps its failure logging in `NonCancellable` —
   before that, `withTimeout` cancelled the coroutine *and* the suspending log write, so
   failures vanished silently. The log should finally name the exception.
   ```
   adb shell run-as io.github.bossmanct.aspectly cat files/activity.log
   ```
2. **Suspect a stale connection.** `connect()` may return true against a socket that is
   already half-dead — plausible after Auto Blocker has been toggling adbd repeatedly.
   Worth verifying the connection with something trivial (`echo ok`) immediately after
   connecting, and reconnecting if it fails.
3. **Get a clean baseline.** The device had debugging toggled perhaps a dozen times in
   one session. Reboot with Auto Blocker off, confirm the happy path still works, then
   reintroduce Auto Blocker.

## Docs that are now wrong

Written before self-repair worked, and they overstate the limitation:

- `CONSENT-COPY.md` section 3b says *"Why we are not fixing this. We cannot."* — false.
  It also promises users thirty seconds of manual re-arming after every restart, which
  is no longer true.
- `ADB-NOTES.md` item 11 says reliability is capped by a toggle we do not control.
  Half-true now: self-repair beats it, but only once the grant is in place.
- `README.md` does not mention self-repair at all.

## Still untouched

- `shots/` is gitignored. The seven emulator screenshots are impersonal and could be
  published; 08 and 09 show a real Facebook feed and need a look first.
- Instagram accepts every override and letterboxes nothing. It already handles 10:16
  correctly, so it needs no help — but *why* the override no-ops is unexplained, and
  the manifests are identical to Facebook's.
- 17% of installed third-party apps already target SDK 37 and can never be reshaped.
  Expect roughly 63% within a year as Play forces target bumps.
