# Aspectly — consent and disclosure copy

Draft user-facing text. Principle: say the true thing plainly, including the parts
that make the app look less capable. Every claim below is one we have actually
verified on hardware — do not add claims we have not tested.

---

## 1. First run — what this is

> Aspectly changes the shape apps are given on your screen.
>
> On the Fold 8's cover screen, 9:16 videos get their tops and bottoms cropped,
> because the screen is 10:16 — wider than the video. Aspectly can force an app into a
> 16:9 window so the whole video fits. It can also move the resulting black bar to one
> side, so the app sits closer to your thumb.
>
> It can't do either of these on its own. Android only lets privileged processes change
> how other apps are windowed. The next screen explains exactly how Aspectly gets that
> privilege, and what it means for you.

---

## 2. Before pairing — the important one

> **What you're about to grant**
>
> To change how other apps are windowed, Aspectly connects to Android Debug Bridge
> (ADB) — the same service developers use from a computer. Once paired, **Aspectly has
> shell-level access to this phone.**
>
> That is a high level of access. It is the same access your computer has when you plug
> the phone in and tap "Allow USB debugging."
>
> **What Aspectly does with it:**
> - Runs four specific commands that set an app's minimum aspect ratio
> - Runs one command that controls where letterbox bars appear
> - Reads your installed apps and which Android version each one targets
>
> **What Aspectly never does:**
> - Run a command you didn't trigger
> - Accept commands from other apps
> - Send anything off your device
>
> The complete list of commands is in the source code. Aspectly is open source
> specifically so you can check this rather than trust us. **[View source]**
>
> **You can revoke this at any time:** Settings → Developer options → Wireless
> debugging → off. Aspectly stops working immediately.

---

## 3. The undo guarantee — state this early and often

> **Restarting your phone undoes everything.**
>
> Android clears the setting Aspectly depends on every time your phone restarts. So if
> anything goes wrong — Aspectly misbehaves, you uninstall it, an app looks broken —
> restart the phone. Nothing Aspectly changes survives a reboot on its own.
>
> It's also why Aspectly reconnects and reapplies your settings after each restart.

Verified twice on the Fold 8: `OVERRIDE_MIN_ASPECT_RATIO_LARGE` is wiped at boot and
`areBoundsLetterboxed` returns `false`. This claim is true and is the strongest one we
have. Lead with it.

---

## 4. Per-app toggle

> Changes how **[App]** is displayed on the cover screen. It doesn't modify the app,
> and it doesn't touch your account or your data.
>
> **[App] will close and reopen when you change this.**

The second line matters — overrides apply at launch, not live, so the app gets
force-stopped. Users should not be surprised by it.

---

## 5. Unsupported app — honest expiry

> **Not supported — [App]**
>
> [App] targets Android 17, and Android no longer allows any app to change its shape —
> including this one. This isn't something Aspectly can fix or work around.
>
> Android's own aspect ratio setting may still help: **[Open settings]**

This will become more common over time as apps raise their target SDK. Do not soften
it or imply a future version will fix it. It won't.

---

## 6. Failure states — never leave someone stranded

> **Couldn't reconnect after restart**
>
> Aspectly reapplies your settings automatically when your phone restarts, but it
> couldn't this time. Your apps are running at their normal shape — nothing is broken.
>
> **[Try again]**  **[Show me how to re-pair]**

> **Wireless debugging is off**
>
> Aspectly needs it on to work. Turning it off is also how you revoke Aspectly's
> access, so if you turned it off on purpose, you're all set — nothing more to do.

---

## 7. Activity log — transparency as a feature, not a promise

No analytics. Nothing is transmitted anywhere, ever. Instead, invert it: **the user
watches the app, rather than the developer watching the user.**

Aspectly keeps a local, on-device log of every privileged action it takes:

- Every shell command executed, verbatim, with a timestamp
- Connection events — paired, connected, reconnect succeeded or failed
- Every restore attempt after reboot, and its result
- Every per-app config change

Visible in-app, exportable to a file, clearable by the user. It never leaves the
device unless the user deliberately exports and shares it.

**Why this matters more than it sounds.** Open source proves what the app *can* do, but
only to people who read Kotlin. The log proves what it *actually did*, to anyone. It
turns "Aspectly only runs these five commands" from a claim into something a user
verifies in ten seconds by scrolling a list.

It also replaces the need for a separate diagnostic-report feature — a user filing a
GitHub issue just exports the log.

Log commands and state only. Never log app content, account details, or anything the
user didn't ask Aspectly to touch.

> **Activity log**
>
> Every command Aspectly has run on this phone, in order. Nothing here has been sent
> anywhere — it's stored on this device only, and you can clear it at any time.
>
> **[Export]**  **[Clear]**

---

## License

**GPL-3.0** is the recommendation, and the reason is user protection rather than
ideology: anyone distributing a modified Aspectly must also publish their source. For
a tool that holds shell access to people's phones, "you can always read what's running"
should survive forks. A permissive license lets someone ship a closed-source build
under a similar name with the same privileges and no way for users to check it.

Apache-2.0 is the reasonable alternative if wide reuse matters more (it is also what
Shizuku uses, and it carries an explicit patent grant).

---

## Rules for all future copy

1. **Never claim something we haven't measured.** Every guarantee here traces to a
   verified test.
2. **Lead with the limitation, not the feature**, wherever a user could be surprised
   later.
3. **No dark patterns around the permission.** No "Skip for now" that silently
   half-works, no burying the access explanation behind a "Learn more".
4. **Tell people how to revoke access** on the same screen where you ask for it.
5. **When something can't be fixed, say so.** Don't imply a future update will.
