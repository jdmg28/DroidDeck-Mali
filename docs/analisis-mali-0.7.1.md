# Mali en 0.7.1 frente al fork (base 0.5.0)

Fecha: 2026-11-15 · Rama `arena/cd8ab18a-droiddeck-mali`, HEAD `66d6f8c` (base oficial **0.5.0**,
80 commits propios) · Comparado con la etiqueta **0.7.1** (`cdff94e72`, 10-11-2026) de
[Droid-Deck/DroidDeck](https://github.com/Droid-Deck/DroidDeck).

Este documento responde a una sola pregunta: **qué hacemos con Mali al pasar a 0.7.1**, sabiendo
que el requisito duro del proyecto es que la ruta de GPU **no reinicie el teléfono**.

---

## 1. Resumen

Hay **cuatro** rutas Mali vivas hoy, no dos. Dos son nuestras (heredadas de R19–R25) y dos son
nuevas de upstream (0.7.0). No compiten por lo mismo: se diferencian en **quién ejecuta el driver
Vulkan** y, por tanto, en **qué puede tumbar el teléfono**.

| | Ruta | Quién ejecuta el driver | ¿El invitado ve `/dev/mali0`? | Riesgo de reinicio | Validación |
|---|---|---|---|---|---|
| **A** | **PanVK-kbase + fn64** (nuestra) | PanVK (Mesa, glibc) **dentro del invitado**, sobre `mali_kbase` | **Sí** (nodo vinculado; el compositor lo usa como render node) | Alto en teoría; mitigado por el parche fn64, `RebootGuard` y CEF en CPU | **G615 validada por nosotros** |
| **B** | **MaliBridge / vkbridge** (upstream) | Driver **biónico del teléfono**, en la app; el invitado solo habla por socket | No | **Bajo**: un fallo mata el servidor, no el kernel | Upstream, con self-test y retroceso automático |
| **C** | **Venus** (upstream) | Driver **biónico del teléfono** vía virglrenderer; socket | No | **Bajo** | Prototipo en Pixel 9 Pro (G715); upstream avisa de que no valida la integración actual |
| **D** | **Par PanVK de upstream** | Igual que A, con binario de `droiddeck-nonadreno-drivers` (**reconstruido el 25-10**, sí lleva fn64) | Sí (`BL_DRM_STANDIN=/dev/mali0` + `preload/drm.c`) | Como A, con el mismo parche | Sin validar en un G615 nuestro |

**La conclusión no es «elegir una», es ordenarlas.** El fork debe usar **C y B como camino por
defecto** (no tocan el kernel del teléfono) y quedarse con **A —con el parche fn64— como ruta
manual/experimental y como red de seguridad** cuando C y B no puedan arrancar. La ruta **D es la
única que no debemos ofrecer tal cual**: es nuestro mismo camino, sin el arreglo que evitó el
reinicio.

---

## 2. Qué hay hoy en esta rama (ruta A)

Piezas propias, todas fuera de upstream:

| Pieza | Dónde | Qué hace |
|---|---|---|
| Kit de driver universal | `tools/mali-driver/` | `libpanvk_backend.so` (PanVK-kbase, sin tocar) + `libvulkan_droiddeck_mali.so` (adaptador ICD, MIT) + `meta.json` |
| Adaptador ICD | `tools/mali-driver/src/droiddeck_mali_icd.c` | Solo metadatos: reenvía todo y **sintetiza `VK_EXT_physical_device_drm`** (`/dev/mali0` como primary/render), que gamescope exige. 0.2.0 cubre **todo Mali ARM** (vendor `0x13B5`), no solo el G615 |
| Parche fn64 | `driver-patches/0001-…patch` | `get_fb_descs()` guardaba la dirección de un helper en **32 bits**; en kbase es alta → fallo CSF `0x7d0002c1`, `DEVICE LOST` y, según el kernel, **reinicio** |
| Compilación propia | `.github/workflows/mali-panvk-fixed-driver.yml` | rc3 + fn64, perfil `g615-v11-csf`, publicada como `mali-panvk-latest` |
| Presentación de juego | `app/src/main/cpp/waylandcomp/src/vk_present.c` | Tres modos: **DRI3+AFBC**, **DRI3 sin AFBC**, **memoria compartida** |
| Escalera de seguridad | `session/RebootGuard.kt` | 3 reinicios seguidos con juego → baja el nivel (`SAFE_PRESENT` → `NO_SEMA` → `SMALL`); el usuario la limpia desde *Modo seguro* |
| Interruptor de GPU | `gpu/GpuSafety.kt` | `AUTO` (software en Mali) / `SOFTWARE` (lavapipe, sin nodo) / `MALI` (entrega el nodo) |
| Auto-instalación | `gpu/MaliAutoSetup.kt` | Primer arranque en Mali: descarga PanVK, lo instala y **pone `GpuSafety = MALI`** |
| Catálogo propio | `gpu/MaliReleases.kt` | Los paquetes salen de las releases de este repo, no de las listas Turnip |
| Política CEF | `SessionService.kt` + `droiddeck-session` | El navegador de Steam va en **CPU** salvo que el driver contenga `fn64`; nunca `-cef-force-gpu` por defecto |

Dato importante: **el kit nuestro no necesita la mitad Android del driver**. La pantalla va por el
loader del sistema (`TurnipDriver.chooseByDevice()` devuelve `null` en Mali) y el invitado usa solo
la mitad Linux. Por eso **no exige Android 15**, al contrario que el par kbase de upstream
(`MALI_KBASE_MIN_SDK = 35`).

---

## 3. Qué trae 0.7.1 (rutas B, C y D)

### 3.1 Venus (C) — `docs/development/mali-venus.md`, `tools/venus/`

Arquitectura: el invitado (glibc) **no puede cargar** el driver biónico del teléfono. Venus lo
puentea:

1. El invitado carga el ICD de Mesa `libvulkan_virtio.so` con `VN_DEBUG=vtest`.
2. Cada llamada Vulkan se serializa por un socket unix (`files/venus/vtest.sock`).
3. En el lado Android corre `virgl_test_server --venus --no-virgl`.
4. Por cliente, virglrenderer lanza `virgl_render_server`, que **repite las llamadas sobre el driver
   Vulkan del sistema** (el del teléfono).

Piezas que lo hacen funcionar (todas con parche propio de upstream):

- `tools/venus/mesa-venus-vtest.patch` (Mesa 26.2.4): sin `VK_EXT_physical_device_drm` bajo vtest;
  export de sync-fd para vallas y semáforos; **mutex por cola** en `vn_queue_submit` (dos hilos
  enviaban a la vez); **memoria cacheada** para el readback de WSI (de 400 MB/s a ~6 GB/s efectivos).
- `tools/venus/virglrenderer-android.patch` (1.3.0): Mali **importa** dma-buf pero **no exporta**;
  el servidor asigna el dma-buf (dma-heap `system-uncached` o `system`) y lo importa.
- `tools/venus/layer/` — capa de compatibilidad **para DXVK**: emula las 6 features que Mali no
  tiene (`fillModeNonSolid`, `multiViewport`, `robustBufferAccess2`, `shaderClipDistance`,
  `shaderCullDistance`, `textureCompressionBC` con decodificación en CPU).
- `tools/gamescope/patches/0927-…`: sube buffers `wl_shm` en cada commit (si no, parpadeo).
- `tools/linuxfs/preload/venus.c`: quita `LIBGL_KOPPER_DISABLE` para que Zink tenga GL.

Resultados declarados: prototipo 13 en Pixel 9 Pro (G715): **47–52 fps** en Silksong a 1280x720;
Steam Big Picture «limpio y vivo». Problemas abiertos: **la UI de Steam se bloquea 30 s** en
`GetVoiceMicrophoneVolume`, el render server **aborta** con «mutex destruido» cuando un contexto
muere tras un error, y **memoria**: 1–2 GB libres y BC decodificadas 4–8× más grandes.

Requisito que nos afecta: **el driver del teléfono debe reportar Vulkan ≥ 1.2**; si no, Venus se
salta (`VenusComponent.hostVulkanTooOld`).

### 3.2 MaliBridge (B) — `external/MaliBridge`, `docs/third-party/malibridge/`

Submódulo fijado en `48477fe` (v0.5.4), **sin modificar**. Su ICD corre en el runtime Linux y
reenvía por socket a un servidor en la app que usa **el driver Vulkan del propio teléfono**. Se usa
solo en GPU sin KGSL (no Adreno). Trae **self-test** (`droiddeck-vkbridge-smoke`) y, si falla
compartir memoria o el dibujado, **retrocede solo** al par PanVK o a composición por software
(`BL_STEAM_SOFT_COMPOSITING=1`, `BL_OPEN_DRIVER=1`, `BL_DRM_STANDIN=/dev/mali0`).

Licencia: GPL-3.0-or-later con términos adicionales en su `NOTICE`, compatible con nuestro
GPL-3.0; upstream ya lo distribuye así y el submódulo conserva sus avisos.

### 3.3 Pares PanVK (D) — `DriverPairs.kt`

`nad:panvk-mali-kbase` («PanVK kbase · Mali G57-G925»), `nad:panvk-mali-g615`,
`nad:panvk-mali-g710/g720/jm` y tres builds de faux123. Los binarios salen de
`The412Banner/droiddeck-nonadreno-drivers`, **publicados el 12-10-2026**: son de la misma familia
que el nuestro, **sin `driver-patches/0001`**.

Además, upstream añadió un **stand-in de nodos DRM** (`tools/linuxfs/preload/drm.c`: `GEM_CLOSE`,
handles, dma-buf) para que el compositor acepte `/dev/mali0` como render node, y knobs nuevos
(`BL_OPEN_DRIVER`, `BL_DRM_STANDIN`, `BL_STEAM_SOFT_COMPOSITING`, `GAMESCOPE_NO_YCBCR_SAMPLERS`).

---

## 4. El asunto fn64, con cuidado

Lo que sabemos:

- El fallo **existió y reinició el teléfono** (2026-10-14): dirección de 32 bits → prefetch a
  `0xfffef000` → excepción CSF `0xC1`. Nuestro parche lo corrige y está validado (120 gestos en
  Big Picture, UI a 0.45–0.7 GB).
- Upstream 0.7.1 no trae parche **en el árbol de la app**: `git grep fn64 0.7.1` no devuelve nada y
  no existe `driver-patches/`.
- Pero el binario que la app descarga **sí lo lleva**. El repositorio
  `The412Banner/droiddeck-nonadreno-drivers` publica un script
  `patches/panvk_csf_provoking_vertex_u64.py` (crédito a `ryanistr/DroidDeck-Mali`, es decir, este
  fork) y **reconstruye el mismo tag en el sitio**; el cuerpo del release `panvk-mali-kbase`,
  fechado **25-10-2026**, dice que la mitad Linux se compila desde `zenithblue @ 9be6dd6` (trabajo
  de RC5, `csf-v11` hasta el 222) «plus this repo's three fixes (sync-file fd leak,
  **provoking-vertex**, DRM node)».
- El proyecto del driver (`zenithblue`) no tiene ese parche ni en `rc4` ni en `v26.3.1`; el arreglo
  vive en el empaquetado de DroidDeck.

**Corrección respecto a la primera versión de este documento**: no es cierto que el par de upstream
carezca del parche. Lo que sí es cierto es que **nuestro binario es más viejo** (rc3 del 11-10 +
nuestro parche) que el suyo (fuente del 23-10 + tres parches). La decisión cambia de signo: no hay
que evitar el par de upstream por fn64, hay que **adoptar su driver** (o reconstruir el nuestro
desde esa misma fuente) y quedarse con nuestros ajustes de app encima.

---

## 5. Colisiones reales al fusionar (85 ficheros compartidos)

De los 148 ficheros que el fork cambia sobre 0.5.0, **85 los ha tocado también upstream**. Los que
importan para Mali:

| Fichero | Lo nuestro | Lo suyo | Decisión propuesta |
|---|---|---|---|
| `session/SessionService.kt` | `BL_CEF_GPU/BACKEND/MAXFPS/RAWFPS`, `BL_GAME_PRESENT`, niveles de `RebootGuard` | `BL_VENUS`, `VenusComponent.guestEnv`, `vkbridge`, `BL_OPEN_DRIVER`, `BL_STEAM_SOFT_COMPOSITING` | **Sumar**: sus variables + las nuestras; el orden lo decide `GpuSafety`/`RebootGuard` |
| `gpu/TurnipDriver.java` | En Mali: pantalla por el **loader del sistema** | En Venus: `null`; en Mali Auto: sistema | Convergen casi solos; conservar la decisión nuestra y añadir la suya para Venus |
| `gpu/DriverPairs.kt` | (no lo tocábamos) | pares `nad:*` y `recommendedKey` | Adoptar tal cual y **desactivar el par kbase para G615** hasta verificar fn64 |
| `gpu/GpuInfo.kt`, `DeviceSupport.kt` | detección Mali nuestra | familias Mali nuevas | Quedarse con la suya (más completa) + nuestro `maliKbaseCovered` si hace falta |
| `waylandcomp/src/vk_present.c` | 3 modos de presentación + AFBC | extensiones según driver, stride 0, lista completa | **Fusionar a mano**: nuestros modos deben seguir existiendo en la ruta PanVK |
| `ui/GpuDriversPanel.kt`, `FrontEndSetup.kt` | panel fusionado + bloque Mali | opción «Mali (Venus)», «Mali bridge», avisos | Rehacer el bloque Mali con las tres rutas |
| `res/values*/strings.xml` (×2 idiomas) | ~113 cadenas propias | ~400 nuevas | Reaplicar nuestras cadenas; las de Mali se reescriben para 3 rutas |
| `tools/linuxfs/overlay/usr/local/bin/droiddeck-session` | CEF, presentación, dma-buf | Venus, vkbridge, glprobe, drm stand-in | Es el fichero más delicado del merge |
| `.github/workflows/*` | `mali-panvk-fixed-driver`, `software-mode-lavapipe` | `build-venus`, `build-gamescope`, `build.yml` | Se suman; ningún nombre se pisa |

Lo que **no** colisiona y se conserva entero: `driver-patches/`, `tools/mali-driver/`,
`tools/software-driver/`, `RebootGuard.kt`, `GpuSafety.kt`, `MaliAutoSetup.kt`, `MaliReleases.kt`,
`MaliProfile.java`, `DriverArchivePolicy.java`, y los 9 tests propios.

---

## 6. Arquitectura objetivo

```
                    ┌─ ¿GPU del teléfono ≥ Vulkan 1.2 y APK con Venus? ──►  VENUS (C)
                    │                                                      invitado ⇄ socket ⇄ driver del teléfono
  Sesión en Mali ───┼─ ¿MaliBridge usable y self-test OK? ─────────────►  MALIBRIDGE (B)
                    │                                                      igual, con retroceso automático
                    ├─ Elección manual "PanVK experimental" o C/B fallan ►  PANVK + fn64 (A)
                    │                                                      nodo /dev/mali0 en el invitado + RebootGuard
                    └─ GpuSafety = Software / tras reinicios ───────────►  LAVAPIPE (sin nodo)
```

Reglas:

1. **Ningún camino nuevo entrega `/dev/mali0` al invitado por defecto.** El nodo solo se entrega en
   A y solo si el usuario lo elige (o si C y B no arrancan).
2. `RebootGuard` se mantiene y **vigila también el servidor de Venus/Bridge** (si el servidor muere
   con juego en pantalla, cuenta como fallo y baja el nivel).
3. La política CEF (navegador en CPU) **se queda** mientras la UI no esté probada en la ruta activa;
   con Venus se puede reevaluar, porque el driver corre fuera del kernel del invitado.
4. `MaliAutoSetup` deja de poner `GpuSafety = MALI` a ciegas: instala el driver y deja la ruta en
   «Venus si se puede; si no, PanVK», que es la decisión que este documento propone.

---

## 7. Plan de merge en tres tandas

**Tanda 1 — mecánica (sin decisiones).** Árbol de 0.7.1 + nuestros 148 ficheros; compilar; tests.
Criterio de salida: APK que instala sobre el actual y **1650+ tests verdes** (los de 0.5.0 más los
nuevos). Es donde aparecerán los conflictos de `SessionService`, `strings` y `vk_present.c`.

**Tanda 2 — rutas Mali.** Integrar `VenusComponent`, MaliBridge, pares `nad:*`, `drm.c`, knobs
nuevos; reconciliar `GpuSafety`, `RebootGuard`, `MaliAutoSetup` y la política CEF. Criterio: en el
X6 Pro, **sesión Steam con Venus sin reinicio**, con la UI de Steam en CPU, y la ruta PanVK+fn64
todavía seleccionable y funcionando.

**Tanda 3 — limpieza.** Unificar `MaliReleases` con `DriverPairs` (o dejar claro cuál manda),
reescribir textos (una frase por opción, aviso con ⚠️), actualizar `docs/diagnostico-…` y este
documento.

Estimación honesta: la ronda 0.5.0 necesitó **10 builds de CI** para estabilizarse con 148 ficheros
y ~1.900 commits de distancia. Aquí la distancia es de **2.047 commits** y hay **dos arquitecturas
Mali** que reconciliar: contar **15–25 builds** (cada uno ~20–25 min) y varias rondas de revisión.
Nada de esto se puede validar en el sandbox: no hay Kotlin/NDK ni teléfono.

---

## 8. Lo que hay que saber del teléfono antes de decidir

1. **Versión de Vulkan del driver del sistema** (`device.txt` / *Informe del dispositivo*). Si es
   < 1.2, Venus no arranca y el orden cambia: MaliBridge primero.
2. Si HyperOS ha actualizado el driver Mali desde la última sesión (versión `rXXpY`).
3. Un `session.log` de la build 450147: confirma que la ruta que usas hoy sigue siendo la A y que
   `RebootGuard` está en nivel 0.

---

## 9. Riesgos y preguntas abiertas

- **Venus**: bloqueo de 30 s en la UI de Steam, aborto del render server con mutex destruido,
  memoria (1–2 GB libres, BC 4–8×). Son problemas **de upstream**, no nuestros, pero nos los
  comeríamos igual.
- **MaliBridge**: depende del driver del teléfono; su self-test decide, pero no hay datos en un
  G615.
- **PanVK (A)**: sigue siendo el único camino **validado en tu teléfono**, y el único que puede
  tumbar el kernel si el driver falla. Por eso no puede ser el camino por defecto.
- **Licencias**: MaliBridge es GPL-3.0-or-later con `NOTICE`; se distribuye como submódulo, sin
  modificar, como ya hace upstream.
- **Sin verificar**: si la fuente del 23-10 (`9be6dd6`) todavía necesita el parche fn64 o si el
  arreglo ya está arriba (Mesa main usa `uint64_t`). El script del empaquetado es idempotente y lo
  dice, así que se puede comprobar en un build sin adivinar.
- **Driver más nuevo del lado oficial**: el suyo (25-10) trae heaps de tiler y pools más pequeños,
  arreglos de superficie/damage en X11 y un cuelgue de `vkd3d`; el nuestro (rc3 + parche) no.
