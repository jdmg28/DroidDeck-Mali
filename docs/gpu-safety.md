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

The property is only one of three witnesses, because on the device this was written for it cannot
be read at all. A new boot is therefore also fatal when **a session of ours was open** when the
uptime went backwards, or when **the last session ended badly** — abnormal, or stopped by the guard
itself — within ten minutes of the restart. The second of those is not a refinement: the guard ends
the session before the kernel reboots the phone, so a check that only looks for an open session sees
a tidy night and lets the next attempt through. It saw that happen on real hardware.

And the kernel is asked directly, last: `pstore` (`/sys/fs/pstore/console-ramoops-*`) holds the
previous boot's console tail, which is the panic itself. It is normally root-only; the panel says
which way it failed, since "no buffer" and "buffer we cannot read" want different answers.

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

Only a fence that never signals counts as a hard verdict **against the GPU**. Anything that
prevents the test being built — no `python3`, no suitable queue family, no host-visible memory, a
struct the ICD refuses — is reported as a **skip**, which says "the GPU was not tested".

Since 2026-10-10 the app treats a skip as a reason **not to launch** (it is labelled
`DD_GPU_PROBE_SKIPPED` in the log and the panel), which is a change from the original intent, and
it was made after the skip path was observed on a real device doing exactly what a skip cannot
distinguish: an ICD that refused the probe and an ICD that would have hung the phone look the same
from here. The cost is honest and it has been paid — a bug in the probe now locks the session out
(see below), and it is unlocked from the Security panel, not by bricking the device. The guard
errs that way on purpose: the reversible error is the cheap one.

The app owns the outer clock (60 s) as well as the guest's internal ones, and if the process is
still alive it asks proot to wind its tree down (SIGTERM, then SIGKILL) before recording the
timeout as an answer.

#### Two bugs the probe shipped with, and the test that now runs them

Worth writing down because both were invisible in exactly the way that matters.

1. It asked `vkGetInstanceProcAddr(NULL, "vkDestroyInstance")` and took the NULL as news about the
   driver. A loader only has to answer global commands with no instance, and `vkDestroyInstance`
   is instance-level: it was the probe asking the wrong question, and the answer was correct. The
   result was a skip on every launch — which, since the paragraph above, blocks the launch. Three
   attempts on a real phone, all stopped by a bug in the test, none by the GPU.
2. `VkPhysicalDeviceProperties` was declared without its leading `sType` and `pNext`, so every
   field was read 16 bytes low. The "device name" was the driver version. The check meant to prove
   the right ICD had answered was reading the wrong bytes and saying nothing was wrong.

Neither would have survived being run once, so it is run: `tools/test/mock_vulkan.c` is a fake
Vulkan loader (gcc, python3, nothing destined for the phone) that answers NULL for instance
commands until an instance exists, reports a Mali-G615 MC6, performs the fill for real, and can be
told what the fence says. `tools/test/run_probe_tests.sh` drives it through six cases and the
build workflow runs it **before** gradle, so a broken probe fails the build instead of the phone.

That harness also caught a third thing, about itself: the case "no loader can be opened" passed
here and failed on `ubuntu-24.04`, because the runner ships a system `libvulkan.so.1`. A test that
asserts what only some machines do is worse than no test. The loader is now selectable by name
(`DD_GPU_PROBE_LOADER`), which makes the case deterministic everywhere.

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
the rootfs or `/dev/mali0` four seconds later.

**What the abort buys, measured — and it is less than this section claimed until today.** The
reasoning was: catch the stall before the kernel's own 3 s fence timeout and there is no timeout to
dump, no dump, no panic. It is a clean theory and it is wrong. Two sessions, both aborted by the
watchdog with the frame budget at 2000 ms, both followed by a reboot anyway — 48 s later, and 66 s
later. Killing the compositor does not un-send what the GPU was already doing, and the fatal event
(`CS_BUS_FAULT`, then the kbase dump path that hits a `BUG()` at EL1) is already in flight long
before the first sample looks suspicious. The D-state samples that appear *before* the first frame
of a session say the same thing from the other side: the kernel path stalls while frames are still
coming out.

So the watchdog is a limiter and a witness, not a shield: it shortens how long a doomed session
keeps asking the GPU for things, and it records what it saw. What actually prevents the next reboot
is the latch (§1) and, now, not taking the faulting path at all — see "The one switch that can prove
which code is at fault".

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

## The Mali driver package

Everything above is the app. The other half of the question - "the driver is the problem" - is
`drivers/mali-g615/`, which is in the tree because the driver package is not only a driver.

**What the installed package actually is.** `DroidDeck-Mali-G615-driver-0.1.0.zip` holds two
binaries and their licences. The larger, `libpanvk_backend.so`, is upstream's
`libvulkan_panfrost-glibc-aarch64.so` from `panvk-kbase-v0.1.0-rc2` (2026-10-08) - byte-identical,
SHA-256 `0b8eaf709cd3…60c820`, which is the digest the release notes publish for that file. The
smaller one, `libvulkan_droiddeck_mali.so`, is 248 lines of ours: an ICD adapter whose source ships
inside the same zip under MIT.

**What that adapter does, and does not, touch.** It answers
`vkGetPhysicalDeviceDrmPropertiesEXT` with the render node compiled into it, so gamescope's nested
Vulkan backend can import the buffers the session presents, and it strips that extension from the
list handed to PanVK, which does not implement it. It wraps property queries on both the instance and
device dispatch tables. It is not in the present path: it has no opinion about
`vkQueuePresentKHR`, the swapchain, or WSI. So it is not where this fault lives, and editing it can
no more fix the hang than changing the exhaust tip fixes an engine.

**Why it is still the thing we changed.** It is a shim in the dispatch path of every call the session
makes, and it is ours - which makes it the only place an APK can reach that can answer the question
the frame timings cannot: did `vkQueueSubmit` return and its fence go unsignalled (the GPU hung), or
did `vkQueueSubmit` never return (the driver blocked)? Those two need opposite fixes, and only the
second one is recoverable by an app. Adapter 0.2.0 writes one line per call, `-> name` then
`<- name = result`, fsynced as it goes, into `droiddeck-driver-trace.log` in the session folder. If a
device dies mid-call, the call it died inside is the last line in the file. It is off by default: the
wrappers are not even handed out when tracing is off, so the dispatch table is the backend's own -
but it turns itself on while the launch latch is engaged, because in that state every session is an
experiment and an experiment with no result is the worst kind. The crash report now prints the file's
last line and reads it for you: ending on `->` means a call that never returned (the driver blocked);
ending on `<-` means every call came back and the GPU hung afterwards, which is the shape this
device's kernel dump belongs to.

**Getting a new driver.** There is nothing newer to import today - rc2 is the newest release and the
beta.12 that an earlier revision of these notes recommended is four days older. The packaging is
nevertheless automated, so the next one is a download rather than a rebuild of anything:

    UPSTREAM_ZIP=/path/to/PanVK-Kbase-…-EMULATOR.zip UPSTREAM_TAG=panvk-kbase-v0.1.0-rc3 \
    UPSTREAM_SHA256=<from its SHA256SUMS> bash drivers/mali-g615/build.sh out/

`mali-driver-package.yml` does that on a runner - cross-compiler from apt, the upstream zip verified
against the published digest, the AArch64/glibc checks the phone would run performed first, and the
result attached to the `mali-driver-latest` release. Components -> Controladores de GPU -> Importar
`.zip` takes it from there. What is *not* in the pipeline is a new backend: the renderer comes from
upstream, this tree wraps it, and any claim otherwise would be a promise nobody here can keep.

**What upstream says about RC2, read carefully.** The release notes state that the RC2 binary has not
been run on a device - the G615 numbers were measured on a build of the same patch - and ask for
PanProbe reports. Running PanProbe 1.2.5 on this phone is a cheap, bounded experiment that does not
touch Steam, the chroot or the guard, and a pass there against a hang here would localise the fault
to this guest's presentation path. Two things these notes previously claimed upstream had said are
retracted: the release does not describe a present-after-two-frames hang, and it does not say the
driver lacks support for this display server.

### The one switch that can prove which code is at fault

`Seguridad GPU -> Advertise a DRM node (driver facade)`. Off exports `DD_ADAPTER_NO_DRM=1` into the
guest, and the adapter then adds *nothing*: it does not advertise `VK_EXT_physical_device_drm`, does
not touch a client's `VkPhysicalDeviceDrmPropertiesEXT`, and does not strip the extension from
`vkCreateDevice` (a request for something no driver offered is the backend's to refuse, not ours to
hide). `drivers/mali-g615/test/run_adapter_tests.sh` asserts all three against the fake backend,
including that the strip is deliberately gone.

Why a toggle and not a code change: this is the only non-pass-through behaviour in the ~500 lines we
ship around upstream's binary, so it is the only candidate on our side of the line between "the
driver faults" and "our shim made the driver do something it was never tested doing". The adapter
answers that extension with `/dev/mali0`, a kbase char device — not a DRM render node — and a
compositor that trusts it goes on to treat it as one. Whether that has anything to do with this fault
is unknown; it is testable in one variable, and it is the only remaining test that can end with the
phone *not* rebooting. Expect a refused Steam launch when off. A refused launch is a session lost; a
bus fault is the phone.

## Software mode: no GPU in the guest at all

A fourth profile, next to Off/Safe/Strict, whose purpose is not to soften the driver but to remove it
from the picture: the guest draws with Mesa's **lavapipe** (llvmpipe behind Vulkan), so Steam, FEX,
Proton and a game can be validated on ARM64 with the Mali hardware doing nothing. The settings page
says why it is slow; nobody should read 3-second frames as a driver bug.

What it changes, and what it was verified against:

- **The guest is not given a GPU, and is not given a fake one.** `LinuxRuntime.binds()` skips
  `bindGpuNode` (which fabricates `/dev/dri/renderD128`, `/sys/dev/char`, `/sys/class/drm`) and
  `bindAdrenoStats` entirely, and shadows the two real nodes instead: `/dev/mali0` and `/dev/kgsl-3d0`
  are bind-mounted over `<rootfs>/etc/droiddeck/empty`. Is that viable? Yes - the same mechanism already
  ships for `/sys/fs/selinux`, and it works inside a wholesale `/dev` bind, which is the reason the node
  is reachable today at all (not `bindGpuNode` - `/dev` is bound as a directory). One caveat is worth
  stating plainly: the shadow hides the *name*, not the device. Anything holding an already-open fd, or
  reading the host `/dev` listing, still sees the node; what it cannot do is *open* it.
- **The shadow is an empty regular file, never `/dev/null`.** The adapter's `mali_node()` gate is
  `S_ISCHR(st.st_rdev)`, and `/dev/null` *is* a chardev - shadowing with it would leave the probe
  passing with a nonsense DRM node, which is worse than no shadow at all.
- **The pre-flight probe is skipped, and says so.** `Probe.run` is not run: a software session has no GPU
  to measure, and a "skipped" verdict would otherwise be read by `required = probe.skipped &&
  probeRequired` as a reason to refuse. The session writes a `DD_GPU_PROBE_SKIPPED` result, notes it in
  the guard report, and logs "skipped by design".
- **Driver choice has no fallback.** In software mode the session resolves the ICD through
  `LinuxVulkanDriver.driverForSession`, which returns the installed software driver, and the guest script
  exports its manifest with `VK_DRIVER_FILES` - which *replaces* the rootfs's own ICD enumeration, so
  PanVK's manifest is never read. If the driver or its library is unreadable, `droiddeck-session` refuses
  with `DD_SOFTWARE_DRIVER_MISSING` and exit 78; the fallback that the normal path keeps (ignore a bad
  import, use the runtime's own driver) is deliberately not present here, because falling back is how a
  session that promised no GPU ends up opening one. Both Mali gates in that script carry
  `&& [ "${BL_SOFTWARE_VULKAN:-}" != 1 ]`, so the hardware path cannot be re-armed by accident.
- **Auto cannot pick a hardware driver on Mali.** On this device "no driver selected" is not neutral: the
  runtime's built-in ICD is PanVK, so an Auto that chose nothing would be an Auto that chose the GPU.
  `DriverMenus.autoSoftwareOnMali` selects the imported software driver - and with none imported, it says
  what to import and leaves the setting alone. The Mali/Turnip driver is used only when the user imports
  or picks it explicitly. `TurnipDriver.chooseByDevice` already returns null on Mali ("use the Android
  system Vulkan compositor"), verified rather than assumed, and Adreno behaviour is untouched: every
  line above is gated on the software profile, not on the family.
- **The glibc question is split in two, on purpose.** CI measures what Mesa's `libvulkan_lvp.so` and its
  `libLLVM` actually reference and records it as the package's `minGlibc`; `droiddeck-session` compares
  that against `getconf GNU_LIBC_VERSION` inside the guest, where the real rootfs is known. Too old:
  refused, `DD_SOFTWARE_GLIBC_OLD`, before anything is exported. Unknown: one line in the log and
  continue - refusing on a missing `getconf` would punish images that are fine. CI does not judge the
  rootfs because it cannot see it.
  What the current package says, measured from Debian trixie's own build (Mesa 25.0.7-2+deb13u1, LLVM
  1:19.1.7-3+b1): **`minGlibc 2.38`**, with `libLLVM.so.19.1` at 123,242,120 bytes and
  `libvulkan_lvp.so` at 10,451,865 - 40,605,954 bytes of zip. So a rootfs on Ubuntu 24.04 (2.39) or
  Debian 13 (2.41) is fine; **Debian 12 (2.36) is not**, and will be refused at the start of the session
  rather than half-loading a driver.
- **The companion is found by `RUNPATH`, and the mode makes that authoritative.** Nothing on the guest
  side sets `LD_LIBRARY_PATH` for drivers - every write of it in the app is the host-side proot library
  path - so the `$ORIGIN` written at packaging time is what locates `libLLVM` at all. glibc searches
  `LD_LIBRARY_PATH` *before* `DT_RUNPATH` (and `ld.so.cache` after it), so software mode prepends the
  driver's own directory rather than hoping the caller's path is empty. The ceilings moved with it:
  `MAX_LIBRARY_BYTES` 96 → 192 MiB and `MAX_ARCHIVE_BYTES` 128 → 256 MiB, because Debian's libLLVM
  exceeded the old per-library limit by 22 MB and the whole package fitted the old total by 522 KB. In
  exchange the importer now refuses before reading anything when the device cannot show room for it
  (`requiredFreeSpace`, four times the archive plus 32 MiB, capped at the ceiling it is guarding).

Honest limits of the mode:

- **The app itself still presents through the GPU.** The *guest* never opens the node; Android's own
  compositor path is the one DroidDeck's window is drawn on, and on Mali `TurnipDriver` resolves to the
  system driver for that. Software mode removes the driver from the session's drawing, not Vulkan from
  the phone.
- It needs the driver package. `lavapipe-package.yml` builds it (Mesa's ICD + its libLLVM + the
  importer's `meta.json`, with `DT_RUNPATH=$ORIGIN` so the driver finds its companion after the importer
  renames and relocates it) and refuses to publish one whose shape or sizes the importer would reject.
- llvmpipe is not a way to play. It is a way to find out whether the stack above the driver is sound.

## Settings

Setup gains a fifth tab, **Safety**, on a Mali device (or once the guard has been turned on
anywhere). Everything in it is reversible, and the defaults are the conservative ones:

| Setting | Default | What turning it off costs |
|---|---|---|
| GPU guard | on (Mali) | no watching, no probing, no latch |
| Restrictions | Safe | the session runs at full size with every extra path on |
| Software (lavapipe) | off | with it on, the guest gets no GPU at all - see the section above |
| Refuse to start after a crash | on | one tap instead of a block after a reboot |
| Test the GPU first | on | ~2–20 s added to every launch (inert in Software mode: there is no GPU to test) |
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
- **The watchdog cannot outrun a `CS_FATAL`.** This is measured now, not argued: on 2026-10-10 at
  `10:15:28.6` a session presented its last frame; the watchdog saw the budget go (1126 ms, 1642,
  2154), said `no frame for 2154ms (budget 2000ms)`, and aborted at `10:15:31.2` — **300 ms before
  the kernel's own 3 s fence timer would have fired**, with the process tree swept. The phone
  restarted anyway, 48 s later. Nothing was wrong with the timing; the assumption was. The bus
  fault comes *first* and the fence timeout is downstream of it, so by the moment frames stop the
  kernel is already committed and no amount of userspace speed is in the race. The watchdog is a
  net for soft stalls, heat and wedges. It is not, and cannot be, a way to survive this driver.
- **What that leaves, and what was broken.** If the app cannot stop the reboot, the only thing it
  can still do is refuse the *next* attempt — which is the latch's whole job, and on that same boot
  the latch did not engage. The log says why: `session ended: abnormal` at 10:15:31, and at the
  next boot `no boot reason and no interrupted session`. The guard had already cleaned up its own
  abort, so nothing was "open" when the phone came back, and a check that only looks for a session
  still running at boot finds no victim. It now reads how the session *ended* too: an abnormal end
  or a guard abort followed within ten minutes by a restart is a fatal boot, and a guard abort is
  written at the moment it is decided, because the teardown is exactly when the kernel may take the
  phone. The cost of a false lock is one tap; the cost of a false clear is the device.
- **The boot reason is not readable on this device — and that is no longer the end of it.** Some
  vendors report `hw_reset`, MediaTek here reports nothing at all: `sys.boot.reason` is refused,
  `getprop` has no key, `getLastShutdownReason` does not exist at this SDK. The guard now asks the
  kernel directly through `pstore` (`/sys/fs/pstore/console-ramoops-0`), which holds the previous
  boot's console tail and *is* the panic line. It is normally root-only, so this is not assumed to
  work; what is guaranteed is which answer you get, because the panel distinguishes "no pstore
  buffer present" from "the buffer exists and this app cannot read it". Only the second has a fix.
  An unreadable reason is never treated as "it was fine".

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
