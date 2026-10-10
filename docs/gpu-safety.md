# GPU safety guard

The Mali-G615 experiment reached the point where a Steam session did not just fail — it took the
phone with it. Android recorded the next boot as `kernel_panic`, and the trace named the Mali
kernel module:

```
CS_FATAL.EXCEPTION_TYPE: 0x48 (CS_BUS_FAULT)
FENCE_SIGNAL timeout(3000 ms)
Unexpected kernel BRK exception at EL1
kbasep_csf_sync_kcpu_dump_locked [...] [mali_kbase_mt6897_r44]
Last boot reason: kernel_panic
```

This document is about what the app can and cannot do about that, and what it now does.

## What an APK cannot do

It is worth being blunt about this first, because everything below follows from it:

- **It cannot repair `mali_kbase`.** That is a kernel module. An app cannot patch it, cannot
  replace it, and cannot load a different one.
- **It cannot disable a watchdog it does not own**, lower SELinux, or raise the fence timeout.
- **It cannot read the kernel log.** `/proc/kmsg`, `/proc/last_kmsg` and DropBox are all closed to
  an unprivileged app on a retail phone. The trace above came from `adb`, not from the app.
- **It is not running when it happens.** By the time Android is back up, the only thing left is a
  boot reason and whatever was written to disk before the reset.

So the guard is not a fix, and nothing here claims the panic cannot happen again. What it does is
make the panic much harder to repeat, and make every attempt leave evidence.

## What the guard does

### 1. Breaks the loop — the part that matters most

`BootReason` reads `sys.boot.reason` (with vendor fallbacks) at every app start. It only counts a
boot once: `SystemClock.elapsedRealtime()` is stored on each start, and a new boot is detected by
the uptime going *backwards*, so opening the app ten times after one crash counts one crash.

A boot recorded as a panic, a watchdog bite or a thermal shutdown:

- raises the crash counter,
- **engages the latch**, and
- refuses every launch until the user reads the dialog and taps *I understand — re-arm*.

An automatic start (Setup → "open straight into Steam") is refused outright, with no dialog: opening
the app must never be the thing that puts the device back into the loop.

The latch relaxes on evidence, not on a timer: three sessions in a row that end *normally* lift the
lock on their own. A session the guard itself stopped resets the count to zero.

This is the genuinely dangerous scenario it removes: a phone that panics shortly after every boot,
before anyone can reach Settings.

### 2. Reduces the load before it is applied — `SafeProfile`

| | OFF | SAFE | STRICT |
|---|---|---|---|
| Output | as set | 720p ceiling | 576p ceiling |
| Frame cap | as set | 30 fps ceiling | 24 fps ceiling |
| HDR10 | as set | off | off |
| Frame generation | as set | off | off |
| Upscaler / effects | as set | as set | off |
| gamescope realtime queues | as set | off | off |
| Mesa shader cache | on | on | off |
| Steam interface | Zink (GPU) | Zink (GPU) | **llvmpipe (CPU)** |
| Second screen | as set | as set | off |

`SAFE` is the default on a Mali device. `STRICT` is what a locked latch forces.

The caps are applied in `SessionState` before both halves read it — the activity for the compositor
and `PerfMode`, the service for gamescope's `-W/-H/-r` — so the two agree on one size.

`STRICT`'s llvmpipe switch is a diagnostic as much as a restriction. A device that survives on
`STRICT` but dies on `SAFE` has told you the fault is in the Vulkan path and not in the client's
own interface.

### 3. Tests the GPU before Steam does — `droiddeck-gpu-probe`

A proot invocation, run after the session folder is open and before the guest command line is
built. Three stages, each with its own clock:

1. **Enumeration** — `vulkaninfo --summary` under `timeout`. A hang here (exit 124/137) is reported
   as `DD_GPU_PROBE_SUBMIT_TIMEOUT`, because enumeration that does not finish is the same silence
   that preceded the panic.
2. **Submit** — a Python/ctypes `vkCmdFillBuffer` of 4 KB straight against the session's ICD, then
   a fence wait (2 s, inside the kernel's own 3 s fence timeout).
3. **Readback** — the bytes come back as they were written. A fence that is signalled without the
   work having been done cannot pass.

Only a fence that never signals counts as a hard failure. Anything that prevents the test being
built — no `python3`, no suitable queue family, no host-visible memory, a struct the ICD refuses —
is a **skip**, never a verdict against the GPU. A bug in this script must never be the thing that
locks someone out of their own phone.

The app owns the outer clock (60 s) as well as the guest's internal ones, and if the process is
still alive it asks proot to wind its tree down (SIGTERM, then SIGKILL) before recording the
timeout as an answer.

### 4. Takes the session down first — `GpuWatchdog`

Two samples a second, while a session runs:

- **Frames.** `WaylandCompositor.recentFrameIntervalMs()`. gamescope presents continuously, so a
  gap of seconds is not a heavy scene, it is a stopped session. One sample over budget warns; two
  stop the session.
- **Wedged processes.** Three or more processes of this app in uninterruptible sleep (`D` in
  `/proc`) across three consecutive samples — a fence that will never be signalled parks every
  thread waiting on it there. About 1.5 s to act, which is inside the kernel's own 3 s timeout.
- **Temperature.** Battery (°C, the one thermal number an app may read on a stock phone; thermal
  zones where the kernel lets us). Over the ceiling warns, and stops the session if it stays there.

An abort ends the session, writes everything to `gpu-guard.log`, and sweeps anything still holding
the rootfs or `/dev/mali0` four seconds later — a killed Steam client is a restart, a kernel that
finds the wedge itself is a reboot.

Both signals are skipped while the screen is off: a sleeping phone presents nothing by design.

### 5. Records everything

- `Download/DroidDeck/<session>/gpu-guard.log` — the boot reason, the profile, the probe verdict,
  periodic samples, and every action the guard took.
- `Download/DroidDeck/<session>/gpu-probe.log` — the probe's full output.
- `device.txt` — a **GPU safety guard** section: crash count, lock state, the last probe.
- Setup → **Safety** → *Copy* — all of it on the clipboard, ready to paste into a bug report.

Every line is forced to the storage with an `fsync` as it is written, and the samples are logged
once a second (every 500 ms once anything is out of the ordinary) rather than every four. That is
not tidiness, it is the difference between a record and no record: a plain `File.appendText` puts
the bytes in the kernel's page cache, and a kernel panic takes the page cache with it. The first
crash this was tested against showed exactly that — samples every four seconds up to 22:33:06,
then nothing, and the reboot recorded at 22:34:23 with a log ending a minute and a half earlier.
It looked like the watchdog had stopped sampling. It had not: it had been sampling until the
device died, and the panic had eaten the evidence.

## Settings

Setup gains a fifth tab, **Safety**, on a Mali device (or once the guard has been turned on
anywhere). Everything in it is reversible, and the defaults are the conservative ones:

| Setting | Default | What turning it off costs |
|---|---|---|
| GPU guard | on (Mali) | no watching, no probing, no latch |
| Restrictions | Safe | the session runs at full size with every extra path on |
| Refuse to start after a crash | on | one tap instead of a block after a reboot |
| Test the GPU first | on | ~2–20 s added to every launch |
| Watch for a stalled GPU | on | the session is never stopped for you |
| No frame for | 5 s | — |
| Temperature ceiling | 44 °C | the phone decides for itself |
| Interface on the CPU | off | only under Strict |

## Honest limits

- **The probe can itself trigger the fault.** It is a much smaller target than Steam, it is
  time-boxed, and it is logged — but it does push work through the same driver. If it hangs, the
  kernel may still take the device down. That is the calculated trade: better a bounded, logged,
  two-second workload discovering it than the client discovering it half a minute in with the user
  mid-game.
- **The watchdog cannot act before a `CS_FATAL` it did not see coming.** Once the GPU has raised a
  fatal exception, the kernel's dump path is what panics, and nothing in userspace is consulted.
  The watchdog is a net for soft stalls, heat and wedges, not a guarantee.
- **The latch depends on the boot reason being readable and truthful.** Some vendors report
  `hw_reset` or nothing at all. An unreadable reason is logged and left alone — the guard never
  treats "I don't know" as "it was fine".

## Where it lives

```
app/src/main/java/com/droiddeck/launcher/gpu/guard/
    BootReason.kt       why the phone last started, and how that is classified
    GpuSafetyPrefs.kt   the settings and the latch's memory
    SafeProfile.kt      what Off / Safe / Strict change
    GpuGuard.kt         the latch, the launch decision, the session's lifetime
    GpuWatchdog.kt      frames, wedged processes, temperature
    Probe.kt            runs the guest probe, owns the wall clock
    GpuGuardLog.kt      the record
tools/linuxfs/overlay/usr/local/bin/droiddeck-gpu-probe   the guest side of the probe
app/src/main/java/com/droiddeck/launcher/ui/GpuSafetyPage.kt   the page and the lock dialog
```
