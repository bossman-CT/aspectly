# In-app ADB — implementation notes

Aspectly connects to the device's own `adbd` over loopback and runs a fixed set of
shell commands. No Shizuku, no root, no PC.

**Verified working** on Galaxy Z Fold 8 (SM-F971U, Android 17 / SDK 37): paired,
connected, and `getprop ro.product.model` returned `SM-F971U` through a stream the app
opened itself.

Every item below cost real debugging time. None of it is documented anywhere obvious.

---

## 1. Android Keystore keys do not work for ADB's TLS

**Symptom:**

```
Failure in SSL Library, usually a protocol error
RSA routines:OPENSSL_internal: internal error (external/conscrypt/.../native_crypto.cc:690)
```

**Cause:** the first design put the RSA identity in the Android Keystore —
hardware-backed and non-exportable, which is exactly what you want for a key that *is*
shell access. But a Keystore `PrivateKey` is only a handle to material inside the
secure element, and libadb's TLS runs through its own bundled Conscrypt, which has no
path to that hardware. It cannot perform the RSA operation.

**Resolution:** generate the keypair normally, and protect it at rest with an AES-GCM
key that *is* hardware-backed. The RSA key exists in app memory while connected; on
disk it is unreadable without the secure element. Weaker than the original plan,
considerably stronger than a key in a file.

## 2. Platform Conscrypt reflection is broken on Android 17

**Symptom:**

```
NoSuchMethodException: com.android.org.conscrypt.Conscrypt.exportKeyingMaterial
  [class javax.net.ssl.SSLSocket, class java.lang.String, class [B, int]
```

**Cause:** ADB pairing binds the SPAKE2 exchange to the TLS channel using exported
keying material (RFC 5705), so the handshake cannot be relayed. libadb reaches that
function by reflecting into the platform's hidden Conscrypt, and the signature no
longer matches on Android 17.

**Resolution:** add `org.conscrypt:conscrypt-android`. This is the library's intended
path, not a workaround — `SslUtils.getSslContext()` probes for
`org.conscrypt.OpenSSLProvider` and uses it when present, falling back to the broken
platform-reflection path only when it is absent.

## 3. BouncyCastle variant collision

**Symptom:** hundreds of `Duplicate class org.bouncycastle.*` errors at dex time.

**Cause:** libadb-android pulls in `bcprov-jdk15to18`. Adding `bcprov-jdk18on` brings
the same classes built for a different JDK target.

**Resolution:** do not declare `bcprov` at all — take libadb's transitive copy — and
add only `bcpkix-jdk15to18` at the **matching version** for the certificate builder.

## 4. mDNS discovery is no longer silent

**Symptom:** tapping Pair opened a system "choose a device to connect" screen, then
nothing. logcat named it:
`com.google.android.connectivity.resources/...NsdPickerActivity`.

**Cause:** `NsdManager.discoverServices()` now routes through a user-facing device
picker. Silent local-network scanning is not available to apps the way it is to
desktop `adb`.

**Resolved — see item 6.** No system property exposes the port either
(`service.adb.tls.port` does not exist; `settings global` has only `adb_enabled` and
`adb_wifi_enabled`), so discovery had to be solved another way.

## 6. Port discovery: scan loopback, cheapest filter first

**Solution:** `AdbPortScanner` finds adbd on 127.0.0.1 in **~1.5s with no user
interaction, no permission, and no packet leaving the device.**

Loopback is not "the local network", so local network permission does not apply and no
picker appears. It also keeps the promise the app makes about never touching the
network.

Three stages, cheapest first:

| Stage | Purpose | Cost |
|---|---|---|
| Non-blocking NIO sweep | Which ports accept TCP | ~600 in flight per batch on one thread |
| TLS probe | Which of those speak TLS | ~400ms cap each, runs on a handful |
| Full ADB handshake | Which one is actually adbd | Only on survivors — measured: 1 |

**The mistake worth remembering.** The first two attempts optimised *concurrency* —
bigger semaphores, then NIO — and neither helped, because concurrency was never the
bottleneck. The activity log had the answer all along:

```
12:36:07  SCAN probing 45109
12:37:32  SCAN probing 34311
```

**85 seconds on one wrong port.** A full ADB connect against a non-adbd port opens a
TLS socket and waits for a handshake that never arrives, with no timeout. The sweep
itself was always fine — it finds all 7 open loopback ports in a few hundred ms.

Adding a cheap discriminator *before* the expensive one cut a 10s+ scan to 1.5s and
reduced expensive probes from 7 to 1. Measure where the time goes before optimising the
part that looks slow.

The last successful port is cached and tried first, so a warm reconnect costs one
handshake. The cache misses after a reboot — which is exactly when the scan earns its
keep.

## 5. The pairing dialog regenerates everything each time it opens

Port *and* code are both new on every open, and the pairing service disappears when the
dialog closes. Any flow that makes the user leave that screen is chasing values that no
longer exist.

**Resolution:** split screen — pairing dialog on one side, Aspectly on the other.
Confirmed working on the Fold's inner display. Onboarding has to be designed for this
rather than letting users discover it.

---

## 7. Reset the connection manager before *every* probe

A failed `connect()` leaves `AbsAdbConnectionManager` holding a dead socket, and every
later connect on that instance fails — **including against the correct port**.

Resetting once at the start of a scan is not enough. The first wrong candidate poisons
all the rest:

```
13:38:13  SCAN probing 45717   <- stale cached port, fails
13:38:15  SCAN probing 37241   <- fails because the manager is now dirty
13:38:19  SCAN probing 40431   <- same
                               (adbd was on 46291 and would also have failed)
```

This hid itself during development: a warm scan finds adbd on an early candidate and
looks fine. Only a reboot produces the case where a wrong port comes first — because
the cached port is always stale by then.

**Reset immediately before each probe, not once per scan.**

## 8. Reconnecting does not restore the bar position

`wm set-letterbox-style` is **global**, not per-app, and Samsung resets it at every
boot. Reapplying the per-app aspect ratio alone gives letterboxing at the default
*centre* rather than the configured side.

Symptom: after a reboot the app is correctly letterboxed but the bar sits on both
sides. Toggling an app off and on "fixed" it only because that re-ran the bar command
as a side effect.

**Reapply the bar position on every successful connect.** It is one command and does
not force-stop anything.

## 9. Restore cannot run until first unlock

Before the device is unlocked, credential-encrypted storage is not mounted and
`BOOT_COMPLETED` is not delivered to apps that are not direct-boot aware. Confirmed
directly — `run-as` could not even stat the app's data directory on a booted but locked
phone.

The honest promise is **"restores shortly after you unlock"**, not "restores on boot".

## 10. Boot restore needs WorkManager, not a receiver

A `BroadcastReceiver` gets roughly ten seconds and one attempt. On a real reboot that
is far too early:

```
13:18:49  BOOT restore starting
13:19:01  AUTOCONNECT failed: No adbd found on loopback
```

Nothing was wrong with the restore — it asked before adbd existed, and never asked
again. `RestoreWorker` instead waits on a `NetworkType.CONNECTED` constraint, retries
with linear backoff, and survives the process dying between attempts.

## 11. Wireless debugging is unreliable, and that caps the product

The toggle switched itself off **five times** during one session, sometimes within
minutes. Every hard-to-diagnose failure today had adbd's absence as a contributing
factor.

Two consequences for the real product:

- The app must show an honest persistent state — *"settings not applied, waiting for
  wireless debugging"* — rather than failing silently.
- Onboarding must say the toggle has to stay on, **including that it silently depends
  on USB debugging being enabled** (see item 12).

## 12. Wireless debugging silently requires USB debugging

On this device, turning USB debugging off makes the Wireless debugging screen report
`IP address: Unavailable (Not allowed)` and greys out pairing — with no explanation
connecting the two. Turning USB debugging back on restores it immediately, cable
irrelevant.

Nobody would guess this. It belongs in onboarding and in the error state.

---

## Current shape

| Piece | File |
|---|---|
| Key + certificate, encrypted at rest | `adb/AdbKeyManager.kt` |
| Connection manager (loopback) | `adb/AspectlyAdbManager.kt` |
| Pair / connect orchestration | `adb/AdbConnection.kt` |
| Command execution | `adb/AdbShell.kt` |
| The complete allowlist | `adb/AspectlyCommands.kt` |
| On-device audit log | `log/ActivityLog.kt` |
| mDNS (unused — see item 4) | `adb/AdbDiscovery.kt` |
