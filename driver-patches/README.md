# Driver patches applied on top of upstream PanVK-kbase for DroidDeck

These are the patches **this repository** applies on top of
[zenithblue-oss/panvk-kbase-android](https://github.com/zenithblue-oss/panvk-kbase-android)
`panvk-kbase-v0.1.0-rc3` when it builds its own Linux-runtime driver package
(`.github/workflows/mali-panvk-fixed-driver.yml`). They are not upstream.

## 0001 — provoking-vertex helper address truncated to 32 bits

`src/panfrost/vulkan/csf/panvk_vX_cmd_draw.c`, `get_fb_descs()`:

```c
uint32_t fn_addr =                     /* BUG: truncates a 64-bit GPU VA */
   dev->draw_ctx->fns_bo->addr.dev + fn_idx * fn_stride;
cs_move64_to(b, addr_reg, fn_addr);    /* the top half is already gone */
```

On `mali_kbase` GPU virtual addresses are SAME_VA and high (the helper
command stream lands around `0x5f_fffef000`). The truncated address makes the
firmware's command-stream prefetcher jump to `0xfffef000`, which faults:

```
kbase: CSF group N fatal error: status 0x7d0002c1 (exception 0xc1)
       sideband 0x00000000fffef000
Unhandled page fault at VA 0x00000000FFFEF000
exception type 0xC1: TRANSLATION_FAULT at level 1, READ
```

Every queue of that context stops; Zink reports `DEVICE LOST` and, because
nothing can be recycled any more, the client's resident set grows by gigabytes
within seconds. On phones whose kernel driver treats a CSF fault as fatal this
is the session — and sometimes the device — going down.

The path only runs when a render pass starts before the application has told
PanVK which provoking-vertex mode it wants. Chromium/CEF on Zink does exactly
that (hundreds of render passes per command buffer); simple Vulkan tests do not,
which is why the driver passed validation without it.

Fix: keep the address in 64 bits.

Source and analysis: [wonderkast02/panvk-g720-kbase-csf issue #9, correction
comment](https://github.com/wonderkast02/panvk-g720-kbase-csf/issues/9#issuecomment-5977983155)
(testing on Mali-G720: 120 gestures over 5 minutes in Steam Big Picture with no
fault, interface at 0.45–0.7 GB).
