# Audit: the experimental Mali-G615 driver kit

**Asked:** is the code in this repo's Mali branch safe? **Answer: no — not to run as a matter of
course, and not on the only phone that has 5 GB of Steam on it.** Not because the part you can read
is reckless: it is small and does less than its reputation suggests. It is unsafe because of what
it makes possible, who wrote the part you cannot read, and what its own evidence does not cover.

What follows is what I read, and what I am inferring. The two are marked.

## What is in the kit

From `DroidDeck-Mali-G615-driver-0.1.0.zip` in the repo root, and its `meta.json`:

| file | what it is |
|---|---|
| `libvulkan_droiddeck_mali.so` (67 KB) | the adapter. Source included: `src/droiddeck_mali_icd.c`, 249 lines, MIT. |
| `libpanvk_backend.so` (21.5 MB) | the renderer. **No source.** `NOTICE.txt`: "original binary is included unchanged", panvk-kbase v0.1.0-rc2, Mesa 26.3-devel at commit `5a07217f`. |
| `meta.json` | `minGlibc 2.38`, `vulkanApiVersion 1.4.363`, `gpu "Mali-G615 MC6"`, `kernelInterface "mali_kbase CSF"`, `status "experimental-not-device-validated"`. |

## Read: what the adapter does

The adapter is an ICD shim. It `dlopen`s `libpanvk_backend.so` from its own directory and forwards
everything to it. It wraps exactly three entry points:

- **`vkGetPhysicalDeviceProperties2`** (`src/droiddeck_mali_icd.c:110`): only when the device is
  ARM (`vendorID 0x13B5`), its name begins `Mali-G615`, **and** `stat("/dev/mali0")` is a character
  device, it finds a `VkPhysicalDeviceDrmPropertiesEXT` in the `pNext` chain and fills in
  `hasPrimary = hasRender = VK_TRUE` plus the **real** major/minor of `/dev/mali0`.
- **`vkEnumerateDeviceExtensionProperties`** (`:130`): appends `VK_EXT_physical_device_drm`.
- **`vkCreateDevice`** (`:166`): strips that extension back out before the backend sees it, because
  the backend does not implement it.

**It never opens the GPU.** `mali_node()` (`:78`) is a `stat()`. There is no `open`, no `ioctl`, no
submission, and no fabricated GPU feature — the comment at the top of the file is accurate about
that much. It lies about exactly one thing: *this physical device has a DRM render node, and here
are its device numbers*. That is the lie the app's own patch then makes true at the filesystem
level.

## Read: what the app does around it — this is the part that matters

`app/src/main/java/com/droiddeck/launcher/runtime/LinuxRuntime.java`:

- `bindGpuNode()` (line 330) stats `/dev/kgsl-3d0`; on this phone that fails, so it falls back to
  `/dev/mali0` (line 337).
- line 376: `bind(cmd, gpuDevice + ":/dev/dri/" + node)` — **`/dev/mali0` is handed to the guest as
  `/dev/dri/renderD<minor>`**, in every session, on a Mali device, whether or not a Mali driver is
  selected. Before this branch's change it was unconditional.
- lines 344–374 build a fake `/sys/dev/char/<major>:<minor>/device/drm/...` tree and (when the real
  one is not listable) a fake `/sys/class/drm`, so libdrm and MangoHud believe it.

So the guest is given the kbase device under a DRM name, and anything that opens
`/dev/dri/renderD<minor>` is issuing ioctls straight at `mali_kbase`. Whether a DRM ioctl number can
collide with a kbase ioctl number I have **not** verified: they use different type/magic values, so
the likely outcome is `-ENOTTY`, but "likely" is not a guarantee against a kernel driver's
unlocked_ioctl dispatch.

The rendering itself is `libpanvk_backend.so` talking kbase UAPI 1.21 (CSF) on `/dev/mali0`. That is
the code that drove the GPU on the session that froze the phone, and it is the 21 MB with no source.

`tools/linuxfs/overlay/usr/local/bin/droiddeck-mali-check` is a GPU probe that runs at every session
start on a Mali device (`droiddeck-session:118`): it `dlopen`s the ICD with `RTLD_NOW` inside a
Python process in the guest, negotiates the loader interface, and runs `vulkaninfo --summary` if it
is installed. That is GPU work before gamescope has done any. This branch skips it in software mode.

## Read: what the kit's own evidence does not cover

`upstream-provenance/VALIDATION.json` reports eight Android-shell gates — enumeration, deviceCreate,
compute10, offscreen, AHB, vulkanSurface, swapchain300, coldSecondLaunch — all `pass`, on a "Poco X6
Pro". Every one of them carries `binarySha256: 725b13c7…`.

**The backend shipped in this zip is `0b8eaf70…`.** The only gates whose evidence carries the
shipped binary are the three glibc ones: enumeration, compute10, offscreen. The WSI, swapchain and
AHB evidence — the parts that matter for presenting a frame — is for a *different build*. It is also
self-reported by the kit's author, and it records "2 intermittent DeviceLost" during
transform-feedback CTS and leaves it as a note.

`SOURCE.json` lists the backend's ancestry as third-party forks (`wonderkast02/panvk-g720-kbase-csf`,
`LukeValen/panvk-mali-g52`, `Noysz/panvk-g99-jm`), not upstream Mesa. That is an aggregation of
other people's experiments, and there is no build you can repeat here.

## Hypothesis, not verified: what froze the phone

The session reached a gamescope swapchain and ~15 dma-buf imports, then the device hung and
rebooted with nothing in Android's crash logs. That is the signature of a hang inside the kernel
driver or the firmware — an app-side watchdog cannot unwind it, which is why the GPU Guard's own
progress note reads "the watchdog did not save the phone: 2 of 2 aborts were followed by a
[reboot]". I cannot confirm the cause: there is no `last_kmsg`, no tombstone and no ramdump. What I
can say is that the part of the stack that was doing GPU work at that moment is the unauditable
binary, and that nothing in the kit's evidence contradicts this reading.

## Conclusion

Keep it switched off, which is what this branch's software mode does by default: no `/dev/mali0` in
the guest, no `/dev/dri`, no probe, no Mali driver unless you pick one by name.

If it is ever worth another attempt, the minimum would be: build the backend from source you can
read, in CI, reproducibly; do not hand the guest a DRM node at all (PanVK opens `/dev/mali0`
itself); get the phone to preserve a `last_kmsg` so a hang leaves evidence; and keep software as the
default. Not on the phone with the 5 GB on it.
