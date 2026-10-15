# Diagnóstico: pantalla negra al abrir Steam — Redmi Turbo 5 Max (POCO X8 Pro Max)

**Reporte original (Reddit):** en la versión china del Turbo 5 Max ("POCO X8 Pro Max"), al abrir
Steam solo queda una pantalla negra; nada más falló — la app abre, el runtime instala, no se
queda trabado en segundo plano.

**Conclusión corta:** el dispositivo del reporte **no es un Mali-G615**, y este fork estaba
cortado a medida del G615 en **dos puntos concretos**. La sesión muere antes de que `gamescope`
llegue a arrancar, y el usuario solo ve la pantalla de sesión (negra). El driver de upstream en
sí **sí soporta la GPU de ese teléfono**; los bloqueos eran del fork, no del driver.

---

## 1. Qué dispositivo es y qué GPU tiene

El **Redmi Turbo 5 Max** es la versión china del **POCO X8 Pro Max**:

| | |
|---|---|
| SoC | MediaTek **Dimensity 9500s** |
| GPU | **Immortalis-G925 MC12** (Turbo 5 Max) / MC11 (POCO X8 Pro Max) |
| Arquitectura | ARM Valhall **v13 (CSF)** — la misma familia CSF que el Mali-G615 (v11) del POCO X6 Pro |
| Android | 16 (API 36) |

## 2. Por qué el fork lo rechaza: los dos cerrojos "G615"

### Cerrojo A — el sondeo del guest (`droiddeck-mali-check`)

En cada arranque de sesión sobre Mali, `tools/linuxfs/overlay/usr/local/bin/droiddeck-session`
ejecuta `droiddeck-mali-check`, que corre `vulkaninfo --summary` con el driver importado y
exigía literalmente:

```bash
grep -Eq 'Mali-G615([[:space:]]|\(|$)'   # → DD_MALI_WRONG_GPU → exit 78
```

En el Turbo 5 Max, PanVK reporta el dispositivo como **`Mali-G925-Immortalis MC12`** (upstream
nombra por conteo de núcleos desde beta.18), así que el `grep` falla y la sesión **termina con
exit 78 a los pocos segundos**, antes de `gamescope`. Desde fuera eso es exactamente el
síntoma reportado: la pantalla de sesión (negra) y nada detrás.

### Cerrojo B — el adaptador ICD (`libvulkan_droiddeck_mali.so`)

En `src/droiddeck_mali_icd.c` (versión 0.1.0, dentro del zip del driver), la fachada que
`gamescope` necesita solo se aplicaba a un dispositivo llamado exactamente `Mali-G615`:

```c
static int target_properties(const VkPhysicalDeviceProperties *p) {
    const char *name = strstr(p->deviceName, "Mali-G615");
    if (p->vendorID != 0x13b5u || !name) return 0;
    ...
}
```

`gamescope` exige `VK_EXT_physical_device_drm` con nodo primario — la única cosa que este
adaptador fabrica (metadatos de `/dev/mali0`). En una G925 la función devolvía 0: el dispositivo
sí enumeraba (el adaptador reenvía todo), pero **sin la extensión DRM `gamescope` no puede
crear el dispositivo** → pantalla negra aunque se saltara el cerrojo A.

### Lo que NO falla (verificado en el código)

- `DeviceSupport.mali()` / `canTry()` (app): genéricos para cualquier Mali con Android ≥ 15 — por
  eso la app dejaba importar el driver y arrancar la sesión.
- `LinuxRuntime` (`/dev/mali0` → `/dev/dri/renderD*` + sysfs DRM falso): genérico.
- El compositor del lado Android (`waylandcomp`): usa el Vulkan del sistema; no depende del modelo.
- `droiddeck-session` / `steam-compatibility`: manejan `BL_GPU_FAMILY=mali` de forma genérica.

## 3. El driver de upstream sí soporta esa GPU

[`panvk-kbase-android`](https://github.com/zenithblue-oss/panvk-kbase-android) publica **un ICD
universal v9–v13**: v9 JM (G57/G77/G78), v10 (G610/G710), v11 (G615/G715), v12 (G620/G720) y
**v13 — Immortalis-G925 pasó 37/37 de PanProbe en una Galaxy Tab S11 Ultra (kernel 6.6.102,
CSF 1.30)** — prácticamente la misma GPU del Turbo 5 Max. El cuello de botella nunca fue el
driver: eran los dos cerrojos de nombre de este fork.

## 4. Qué se corrigió en este repositorio

| Archivo | Cambio |
|---|---|
| `tools/linuxfs/overlay/usr/local/bin/droiddeck-mali-check` | Acepta cualquier `Mali-G*`/`Immortalis-G*` que reporte el driver; falla solo si no hay ninguna Mali. Sigue avisando cuando el modelo no es el G615 validado. |
| `tools/mali-driver/src/droiddeck_mali_icd.c` (nuevo; era solo el zip) | Adaptador **0.2.0 universal**: la fachada DRM aplica a toda GPU ARM Mali (vendor `0x13B5`), no solo `Mali-G615`. Verificado con pruebas de coincidencia (G615/G925/G720/G57 ✓, llvmpipe/Adreno ✗) y compilación cruzada AArch64 glibc. |
| `tools/mali-driver/build-adapter.sh`, `make-universal-zip.sh` (nuevos) | Reconstruir el adaptador y reempaquetar cualquier zip del driver con el adaptador universal (actualiza `meta.json` y sus hashes). |
| `app/src/main/res/values*/mali.xml` | Mensajes de UI: "Mali/Immortalis (CSF)", validado en G615. |

**Artefacto listo para probar** (generado, sin subir a Git):
`DroidDeck-Mali-CSF-universal-0.2.0.zip` — el kit del repo (backend RC2) con el adaptador
universal. Para no-G615 conviene reempaquetar sobre un kit con backend **RC3** upstream
(v9–v13): el script `make-universal-zip.sh` acepta cualquier zip base.

## 5. Cómo confirmar el diagnóstico desde el teléfono del reportante

1. La pantalla de fin de sesión muestra el log: `Android/data/com.droiddeck.launcher.mali.g615/files/sessions/<id>/session.log`.
2. En él aparecería `DD_MALI_WRONG_GPU: this experiment targets the Mali-G615...` (cerrojo A)
   o, si se saltó, el fallo de dispositivo de `gamescope` (cerrojo B).
3. La tarjeta de dispositivo de la app (Información) habría mostrado la GPU real
   (`Mali-G925-Immortalis`), no `Mali-G615`.

## 6. Qué sigue siendo experimental en ese teléfono (aunque compile el fix)

| Riesgo | Nota |
|---|---|
| UAPI kbase del kernel del Dimensity 9500s | Upstream ya corre en kernel 6.6 con CSF 1.30 (Tab S11 Ultra) y soporta layouts CSF/16K pages (beta.17+). Probable, pero sin probar en el 9500s. |
| Presentación de `gamescope` | El binding `/dev/mali0`→DRM está validado solo en el X6 Pro. |
| Vulkan del sistema (presentación Android) | El compositor usa el driver Mali de HyperOS; debería funcionar en cualquier Mali moderno, sin validar. |
| Rendimiento/memoria | Los ajustes DXVK del fork se calibraron con 8–12 GB; el Turbo 5 Max trae 12–16 GB (favorable). |

## 6bis. Segunda ronda: negro también con lavapipe → el presentador del lado app

El reportante probó **ambos** drivers (PanVK y lavapipe) y en ambos hay pantalla negra. Eso
descarta el driver del guest como causa única: en los dos modos, el componente que dibuja en la
pantalla es el mismo — el **presentador del lado Android** (`waylandcomp`), que renderiza con el
**Vulkan del sistema** (el driver Mali del ROM). Es el único componente GPU que la app posee y
que es común a todos los modos.

**Bug de la app confirmado en el código:** cuando ese presentador no puede inicializarse, el
compositor seguía sirviendo Wayland con el guest corriendo detrás
(`compositor.c`: "renderer unavailable; clients will connect but nothing is drawn") y el
swapchain reintentaba cada 0.5 s — todo **en silencio**. El usuario ve negro para siempre, sin
un solo aviso. Exactamente el reporte del Turbo 5 Max, y habría pasado igual en cualquier otro
dispositivo cuyo Vulkan del sistema no sirva.

### Corregido en este commit

| Cambio | Qué hace |
|---|---|
| `vk_present.c` / `vk_present.h` / `waylandcomp_jni.c` | Cada punto de rendición del presentador (loader, instancia, enumeración, cola, `vkCreateDevice`, superficie, formatos, swapchain) registra ahora su razón en un buffer de fallo (`vkp_fault`), lo loguea como línea **`DD_PRESENT_FAULT:`** y lo limpia cuando el swapchain por fin se construye. |
| `SessionActivity` (watchdog de presentación) | Si en 60 s no hay primer cuadro **y** el compositor reporta fallo, la sesión termina sola y la pantalla de fin muestra el motivo exacto en claro. Un arranque lento sin fallo del compositor no se toca. |
| `sessionEndHint` | Lee el fallo (vía JNI o la línea `DD_PRESENT_FAULT:` de `wayland.log`) y lo muestra como pista del fin de sesión, incluso tras morir el proceso. |
| `DeviceReport` (`device.txt`) | Nuevas filas: **tamaño de página del kernel** (`Os.conf(_SC_PAGESIZE)`, clave para los SoC 2026 con páginas de 16 KB), visibilidad de `/dev/mali0`, y el **dispositivo/error del Vulkan del sistema** — justo lo que hay que saber para diagnosticar un negro. |
| `proot/build.sh`, `build.gradle`, `build.yml` | Blindaje 16 KB: `max-page-size=16384` en proot (ejecutado por la app), `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` y `zipalign -P 16`. El rootfs del guest usa glibc, que lee el tamaño de página en tiempo de ejecución. |

### Qué buscar en el siguiente reporte del Turbo 5 Max

1. Con este APK, un negro que antes duraba para siempre ahora **termina solo** con el motivo en
   pantalla (p. ej. "the system Vulkan driver exposed no devices to this app").
2. `device.txt` dirá: página del kernel (4/16 KB), qué dispositivo enumera el Vulkan del sistema
   en el proceso de la app, y si `/dev/mali0` es visible.
3. Si el fallo apunta al presentador del sistema (blob G925/HyperOS), el camino es un presentador
   alternativo (SwiftShader/lavapipe del lado app) o pedir el fix al blob vía hiperOS; si apunta
   a 16 KB (libs no cargan), este build ya lo resuelve.

## 6ter. Ronda 3: POCO X7 Pro (G720 MC8) — tienda a 3-15 fps

**Reporte:** X7 Pro 12 GB abre Steam, pero la tienda va a ~3-15 fps.

**Causa encontrada en la configuración del WSI del driver** (no es el hardware):

1. `PANVK_KBASE_DRI3=0` en `SessionService` forzaba al WSI de panvk a **copias CPU/SHM con
   `supports_modifiers=false`** (ver `patches/kbase-common/006-device-queue-wsi-kbase.patch`
   de upstream): cada frame era un buffer **LINEAR sin AFBC** cruzando memoria compartida. En
   Arm, AFBC es la mitad del ancho de banda; sin él la GPU y la térmica se ahogan.
   → Ahora `PANVK_KBASE_DRI3=1` (dma-buf + AFBC cuando el kernel lo permite), con override en
   `Download/droiddeck-mali-dri3` (`0`/`raw`/`termux`) para depurar.
2. `GAMESCOPE_FORCE_GENERAL_QUEUE=1` se aplicaba a **todos** los SoC. En panvk/kbase los queues
   los gestiona el driver; fijar el general serializa gráficos y presentación.
   → Ahora solo se aplica fuera de Mali.
3. La tienda por defecto se dibuja en CPU (`-cef-disable-gpu`) desde la era RC2, cuando CEF en
   panvk reinició un teléfono. Con RC3 + dma-buf/AFBC, **"Steam interface on the GPU"** (en
   Rendimiento) es la palanca que lleva la tienda a 45-60 fps; el hint de la app ya lo dice.
4. El log de sesión imprime ahora `== mali wsi: PANVK_KBASE_DRI3=...` para leer de un vistazo
   qué modo quedó activo.

Además: el X7 Pro debe usar el paquete **RC3 universal** (la app ya lo ofrece en Descargas);
los zips RC2/G615 de la era beta18 no fueron validados en v12.

## 6quater. Ronda 6 (14-10-2026): X6 Pro con 1f4abb3 — reinicio al poner la interfaz en GPU y juegos clavados en el logo

Dos sesiones del mismo tester, mismo build, mismo driver (PanVK-kbase RC3 universal 0.2.0,
POCO X6 Pro duchamp / MT6897 / Mali-G615 MC6 / kernel 6.1.138 / Android 16 / 7.1 GB RAM):

| | `2026-10-14-04-steam` | `DroidDeck-2026-10-14-05-steam` |
|---|---|---|
| "Steam interface on the GPU" | **Apagado** (CEF dibujado por CPU) | **Encendido** (`-cef-use-gl=angle -cef-use-angle=vulkan -cef-force-gpu` en la línea `== steam args:`) |
| Tienda | Abrió y se navegó ~5 min | Abrió y **el teléfono murió ~10 s después** |
| Proceso GPU de CEF | No existe | steamwebhelper 524 MB churneando dma-bufs de kbase |
| Memoria de la tienda | 218 MB estable | **456 → 1112 MB en 15 s**; disponible 1947 → 1211 MB |
| Fin de sesión | Salida limpia pedida por la app | `ended-without-teardown.txt`: el proceso murió sin teardown, sin crash.log y sin una sola línea de error — el equipo se fue antes de poder escribir nada |

Conclusiones:

- **El WSI dma-buf de la ronda 5 funciona**: la sesión con la UI en CPU usó
  `PANVK_KBASE_DRI3=1` (`kbase_kmod_bo_alloc_dmabuf` por todo el log), la tienda abrió y se
  usó sin problema. El reinicio no es del WSI.
- **La muerte viene de la ruta ANGLE/Vulkan del propio CEF sobre kbase.** Con el toggle
  encendido: el proceso GPU de steamwebhelper creó swapchains por la capa gamescope WSI y
  estuvo asignando dma-bufs de kbase a ritmo alto hasta el último instante logueado; a los
  ~35 s de arrancar la sesión el teléfono se apagó. Sin error de kbase/panvk en el log, sin
  crash buffer: muerte instantánea a nivel kernel (patrón idéntico al incidente RC2). A eso
  se suma que la tienda en GPU ya se estaba comiendo la RAM (+900 MB frente al modo CPU en un
  equipo de 7.1 GB), así que esa vía no se extrañará: **en Mali el ajuste ya no genera
  banderas ANGLE** (no-op con línea en el log) y el texto del ajuste lo explica.
- **Los juegos clavados en el logo van por el mismo conducto.** El juego presentaba por la
  capa `VK_LAYER_FROG_gamescope_wsi` (bypass de X11), que **nunca lee `PANVK_KBASE_DRI3`** —
  por eso la ronda 5 arregló la tienda y no los juegos. La entrega juego→gamescope de esa capa
  (dma-buf + fences de sync explícito sobre kbase) se atasca tras el primer fotograma: en el
  juego (bajo ritmo) el síntoma es el cuadro congelado con audio y entrada vivos; en CEF
  (compositing continuo a ritmo alto) el síntoma fue el reinicio del equipo. Mismo conducto,
  dos intensidades. Fix: en Mali el juego presenta por el DRI3 de X11 de panvk — el mismo
  transporte que ya usa la tienda — bypassando la capa (`DISABLE_GAMESCOPE_WSI=1`;
  `Download/droiddeck-mali-gamescope-wsi=1` la devuelve para experimentos).
- **La RAM del X7 Pro (>80 %) explicada.** En el X6 Pro el juego subió de 302→904 MB en 92 s
  (cargando assets y compilando shaders, CPU al 99 %) con Xwayland en 320 MB, ~160 buffers de
  4.6 MB churnados por el present (los handles se reciclan; no es una fuga que crezca sin fin)
  y 1.2 GB de cache del sistema cayendo a 770 MB. En un teléfono de 8 GB eso pinta >80 %: es
  carga del juego + buffers gráficos, no una pérdida de memoria del fork.
- **El "juego lento" de este tester tenía un límite de 30 puesto a mano.** La sesión 04 arrancó
  con `fps=30` (el limitador de FPS de la app) y DXVK leyó "Reported refresh rate: 30.0 Hz".
  El límite a 30 significa una pantalla de 30 Hz para los juegos; para 45-60 fps hay que subirlo
  a 60 o dejarlo en Off (usa el refresco del panel). El script ahora avisa cuando el límite
  está por debajo de 45.

## 6quinquies. Ronda 7 (14/15-10-2026): los juegos ya corren — ahora fps y RAM

Build 89b6ba9 en el X6 Pro. Sesiones `2026-10-14-06-steam` (Craft. Sell. Goblin. Repeat +
Clicker Heroes) y `2026-10-14-07-steam` (Unturned). Lo ganado:

- **El bypass de la capa gamescope WSI funcionó**: los tres juegos (Craft Sell, Clicker
  Heroes, Unturned) pasaron del logo y corridieron — la línea nueva `== mali games: gamescope
  WSI layer bypassed` está en ambos logs.
- **Craft. Sell. Goblin. Repeat. se jugó** (interfaz en CPU). Sus capturas muestran el HUD
  nuevo trabajando (Ram %, Device, Fps).

Lo que los logs señalan ahora:

- **Los 28 fps eran el limitador, no el juego.** Ambas sesiones arrancaron con `fps=30` y
  DXVK leyó "Reported refresh rate: 29.7 Hz": con el limitador de la app en 30, el juego ve
  una pantalla de 30 Hz y no puede pasar de ahí. Para el objetivo de >60 fps: límite en 60 u
  Off. El script ya avisa cuando el límite queda por debajo de 45.
- **El cuello real es CPU**: 64-96 % sostenido en un 2D (FEX traduciendo + Wine + DXVK sobre
  panvk). El preajuste FEX estaba en COMPATIBILITY; PERFORMANCE/PERFORMANCE_TSO (el default
  del fork) recorta traducción más estricta. Palancas nuevas en código:
  `PANVK_KBASE_GPU_SEMAPHORE_WAITS=1` por defecto en Mali (esperas dentro del anillo de
  envío, menos vueltas CPU↔GPU; `Download/droiddeck-mali-semawaits=0` la apaga) y
  `dxgi/d3d9.maxFrameLatency=1` en DXVK (un cuadro en vuelo: menos latencia y jitter donde
  la cola profunda no ayuda).
- **Clicker Heroes murió de RAM de verdad**: creció a **3.1 GB residentes** (disponible
  1687 → 479 MB) y Android mató la sesión entera — sin crash y sin error, la firma del
  low-memory killer. Unturned (1.2 GB, CPU 91 %) corrió la misma suerte mientras cargaba.
  En 7.1 GB con el stack base (~1 GB entre Xwayland, cliente, explorer.exe y gamescope) un
  juego que pide >2.5 GB está al filo. El monitor ahora lo dice en vivo: `== game memory: …`
  cuando un juego pasa de 2.5 GB residentes.
- Anotado como trabajo futuro: `droiddeck-esync` reporta "1 wanted" — el Proton en uso
  (DroidDeck-Proton 11-7.5) no tiene pack esync y los juegos caen a "wineserver sync only";
  fabricar ese pack sería otra reducción de overhead por llamada de sincronización.

## 6sexies. Ronda 8 (madrugada 15-10-2026): "que exprimen la GPU" — paquete GPU-first

Sesión `DroidDeck-2026-10-15-01-steam` (Craft Sell solo, límite de FPS ya en Off, FEX
EXTREME_TSO): el display llegó a 119.7 Hz (el límite de 30 ya no estorba) y aun así el juego
se queda en 24-26 fps con la CPU al 78-95 %. La verdad incómoda que midió esta sesión: **la
tubería está atada al CPU** (FEX traduciendo el juego + Wine + DXVK llamando a Vulkan), no a
la GPU — un 2D así no llena un G615. Sobre eso, cuatro cambios de la ronda:

- **Driver subido de RC3 a RC3-lowmem** (upstream 13-10, 173 parches sobre Mesa
  5a07217f034b). Es RC3 más csf-v11/185: BC1 RGB y BC7 pasan de 12/16 bpp a **8 bpp** — la
  misma velocidad con **25-33 % menos memoria de texturas**, validado por upstream en un
  POCO X6 Pro igual al del tester (NFS a 45 fps, sin artefactos). Es el ataque directo a lo
  que mató la R7 (Clicker Heroes en 3.1 GB). Escapes: `PANVK_BC_AFRC=0` (decode exacto) o
  16/24/32 (fuerza una tasa). El zip universal de `mali-panvk-latest` se reempaqueta solo.
- **fsync primero cuando no hay nada elegido.** Los teléfonos del tester corrieron R7/R8 con
  "Wine sync: wineserver" (los tres switches apagados) — la vía más lenta que existe. En
  hardware Mali, si no hay ninguna elección, la sesión manda `BL_FSYNC_FIRST=1`: Proton
  sincroniza con su fsync propio (binarios stock, sin pack), y droiddeck-esync cae con
  elegancia si el kernel o el Proton no lo traen. Una elección explícita del usuario sigue
  ganando.
- **maxFrameLatency vuelve a 3.** El 1 de la R7 fue un error mío de lectura: con la tubería
  atada al CPU, una cola de un cuadro serializa CPU contra GPU y cuesta fotogramas (la cola
  profunda existe justo para solaparlos). Queda el knob `BL_DXVK_MAX_FRAME_LATENCY=1` para
  quien quiera latencia.
- **DXVK tira su state cache al morir**: "Cache: 0 shaders" en las sesiones 06 y 08 — el
  juego muere matado (salida de Steam con el juego vivo) y el cache binario no se escribió.
  La caché de shaders del driver (Mesa, en disco dentro del rootfs) sí persiste; el costo
  "pantalla de Load a 4 fps" solo se paga entero la primera vez por sesión de instalación.

Frente abierto (lo próximo si el fsync/lowmem no alcanza): fabricar el pack esync que le
falta a DroidDeck-Proton 11-7.5 ("1 wanted" — los otros tres Proton lo tienen), y medir
EXTREME_TSO contra PERFORMANCE_TSO en un juego real.

## 6septies. Ronda 9 (15-10-2026): reinicio al SALIR del juego con rc3-lowmem, y respuestas de arquitectura

Sesión `DroidDeck-2026-10-15-02-steam` (primer arranque con rc3-lowmem; Craft Sell). Lo que
dice el zip:

- **Carga de texturas lenta al principio**: esperado — cambiar de driver vacía el namespace
  de la caché de shaders de Mesa (todo se recompila una vez) y el state cache de DXVK se
  pierde cuando el juego muere matado. A partir de la segunda sesión con el mismo driver la
  carga debe caer. Subida además: `dxvk.numCompilerThreads` 2 → 4
  (`BL_DXVK_COMPILER_THREADS` lo cambia).
- **Reinicio al salir del juego hacia la tienda.** Evidencia dura: la sesión terminó sin
  teardown, sin una línea de fallo de kbase/panvk, sin OOM (1.2 GB libres en el último
  heartbeat) y el `events.jsonl` termina en bytes NUL — el kernel murió a mitad de escritura.
  Correlación incómoda: **cuatro sesiones con rc3 no tuvieron ningún reinicio en salida; la
  primera con rc3-lowmem sí** (salida del juego = teardown del swapchain X11 DRI3 con el
  compositor todavía sosteniendo dma-bufs del mismo BO). Parche 185 toca el almacenamiento de
  texturas, no el WSI, así que también puede ser mala suerte; mientras tanto:
  - Escape hatch: `Download/droiddeck-mali-dri3` con `0` (present por SHM: evita la ruta
    dma-buf del teardown a costa de parte de la velocidad de la tienda).
  - Si el reinicio en salida se repite con lowmem, hay que reportarlo a upstream
    (zenithblue-oss/panvk-kbase-android) con este zip — la evidencia ya está completa aquí.
- **La ráfaga de 98 "Compositor released us but we were not acquired" es nueva de esta
  sesión y NO es la causa**: es un error de contabilidad del backend anidado de gamescope
  (solo enmarca en su par acquire/release lo que él mismo presenta); devolver el buffer de un
  frame que el compositor decidió no mostrar es Wayland legal, y nuestro compositor devuelve
  en el acto los frames que salta (limitador, oclusión, cambios de foco). Parche propio
  `0113-wayland-backend-unpaired-buffer-release` lo baja a debug: sin el ruido, el log
  vuelve a ser legible.
- **¿Se dibujan los juegos con la CPU como la tienda? No.** Los juegos renderizan en la GPU
  desde siempre en este stack (D3D11→DXVK→Vulkan→panvk; el HUD de frames lo mide la GPU
  compositando). Lo único dibujado por CPU es la interfaz de Steam (CEF) cuando el toggle
  está apagado — y ese toggle permanece bloqueado en Mali porque su ruta ANGLE/Vulkan
  reinicia teléfonos (§6quater). El límite de rendimiento que queda es la **traducción CPU**
  (FEX) más las llamadas, no el dibujo; en 3D la GPU asume el peso grueso y por eso Brawlhalla
  corre a 60 fps en el otro teléfono del grupo.
- **"No hay licencias" al darle Jugar a juegos gratis que no están en la biblioteca**: el
  cliente ARM64 no completa la adquisición de licencia F2P desde la app. Sin PC hay camino:
  en el navegador del teléfono, entrar a la tienda web de Steam (store.steampowered.com),
  iniciar sesión y darle a "Jugar" / "Añadir a la biblioteca" en el juego gratis — la licencia
  queda en la cuenta y el juego aparece en DroidDeck para instalarse. (El aviso de "no puedes
  jugar online" de Brawlhalla en modo invitado/anticheat es del juego, no del stack.)

## 6decies. Ronda 10: importar juegos que no están en tiendas

La sección Juegos (ajustes del modo Steam, pestaña **Juegos**) ya traía "carpeta de juegos":
carpetas con un juego de Windows cada una, escaneadas, con su .exe elegible y arte, inyectadas
en el cliente Steam como accesos directos bajo Proton+FEX. Lo nuevo de esta ronda cierra el
camino sin PC:

- **"Importar juego (.zip)"**: un zip de juego descargado en el teléfono se extrae solo a la
  carpeta de juegos (o a `Download/DroidDeck-Games`, creada y elegida sola en la primera
  importación), con protección zip-slip (una entrada que escape de la carpeta rechaza el
  archivo y no deja restos) y limpieza de basura de Mac (`__MACOSX`, `._*`). Al terminar el
  juego aparece en Steam listo; si arranca el .exe equivocado se elige abajo (el escáner ya
  ordena candidatos y descarta instaladores).
- **Lo que "configura todo" significa aquí**: no hay nada más que tocar — la sesión monta las
  carpetas en `/root/Games`, el cliente las recibe por `BL_ADDED_GAMES`, y Proton 11-7.5 con
  FEX (el preajuste que elijas) ejecuta el .exe igual que un juego de tienda. Guardados y
  prefijo quedan bajo el appid del acceso directo, estable por ruta.
- **La palanca de rendimiento futura que esto abre**: el entorno por juego
  (`~/.config/droiddeck/game-environment.json`, claves `shared` y por appid) deja afinar
  DXVK/FEX/Wine *por juego* — el knob exacto para exprimir cada juego importado una vez que
  la base de fps esté donde queremos.

## 6septies-decies. Ronda 11: botón "Jugar Epic Games" (el launcher nuevo de Epic para Linux)

Epic lanzó el 14-10-2026 el preview de su launcher rehecho con **soporte Linux nativo por
primera vez**: `EpicGamesLauncher-x86_64.AppImage` (~205 MB, Tauri/WebKitGTK, x86_64,
launcher.epicgames.com). DroidDeck lo integra sin Steam de por medio:

- **Botón "Jugar Epic Games"** en la pestaña Juegos, al lado de "Play Steam". Si aún no hay
  launcher instalado, el primer toque abre el selector de archivos para el AppImage (se
  descarga una vez desde el navegador del teléfono, en launcher.epicgames.com → Linux). Queda
  instalado en `/opt/appimages/EpicGamesLauncher.AppImage` — la casa de los emuladores — y a
  partir de ahí el botón lo lanza directo.
- **El camino es el probado**: modo `run` de la sesión (el mismo de los emuladores) — pantalla
  completa en su propio gamescope con GPU (swapchain Vulkan por X11 DRI3, el transporte que
  este fork ya estabilizó), FEX detectando el binario x86_64 y traduciéndolo, extracción del
  AppImage sin FUSE (`APPIMAGE_EXTRACT_AND_RUN=1`). "No requiere Steam" es literal: la sesión
  `run` no arranca el cliente Steam en ningún momento.
- Riesgo declarado: el launcher es x86_64 (bajo FEX) y usa el WebKitGTK **del sistema** — si a
  la primera arranque el log dice `libwebkit2gtk` faltante, el siguiente ladrillo es llevar
  esa librería x86_64 al rootfs de FEX (el error queda en session.log, línea `error while
  loading shared libraries`). Los juegos **nativos Linux** de la biblioteca Epic corren al
  momento; los **Windows** (la mayoría del catálogo) quedan para la segunda fase: Epic instala
  en su carpeta, y si esa carpeta es una de Juegos, el pipeline existente (AddedGames →
  acceso directo Steam → Proton 11-7.5) los corre hoy mismo — el mismo puente que ya usa la
  importación de zips.
- Estado de importación con su fila de progreso en la pestaña ("importing/Importando"), toast
  de resultado, y nombre fijo para que una actualización del AppImage reemplace al anterior.

## 6undecies. Ronda 11 fase 2: el launcher de Epic muere al arrancar, y su apartado propio

**El reporte (session.log 15-10-2026 20:18, modo `run`, el AppImage de Epic):** la sesión entera
levanta bien (gamescope, Xwayland, driver panvk, FEX preparado con el runtime sniper) y el
programa muere antes de ejecutar una línea del launcher:

```
E Failed to remap /proc/pid/cmdline data (prctl failed: result -1, errno 1)   ← cosmético (proot)
sbrk() failure while processing tunables                                      ← ×2
[gamescope] launch: Primary child shut down!
```

**Lectura:** ese `sbrk() failure while processing tunables` lo escribe la **glibc del invitado**
mientras arranca (el cargador de su AppImage, una glibc más nueva que la del runtime sniper).
No es falta de RAM ni del WebKitGTK previsto como riesgo: es el **precarga de thunks de FEX**
— librerías x86 construidas contra **glibc 2.31** que FEX inyecta vía `LD_AUDIT`/`LD_PRELOAD`
para acelerar syscalls — chocando con el cargador nuevo del launcher. Dos procesos mueren igual
(FEXServer y el invitado), firma clásica de un desacuerdo de versiones en el arranque temprano.

**Arreglo:** el launcher de Epic corre **sin thunks** (traducción de syscalls pura de FEX; más
lento por syscall, irrelevante para un launcher):

- `SessionService` (modo `run`) añade `BL_FEX_NO_THUNKS=1` al entorno **solo** cuando el
  programa es el AppImage de Epic; escotilla de escape: fichero
  `Download/droiddeck-fex-thunks` para volver a precargar thunks.
- `droiddeck-fex` (`guest_environment`) honra esa señal y no configura `FEX_THUNK*`; su línea
  de reporte pasa sola a "thunks off". Los emuladores y juegos siguen con thunks: nada cambia
  para lo que ya funcionaba.
- Test nuevo en `tools/tests/test_droiddeck_fex.py` (34 OK): con la señal no hay
  `FEX_THUNKCONFIG`; sin ella, sí.

**Apartado dedicado de Epic (el mockup del usuario):** entrada **"Epic" en el raíl, arriba de
"Steam"** (icono cohete), y su propia página — `EpicHome`, gemela de la de Steam pero sin Steam:
la marca DroidDeck centrada, el botón grande "Jugar Epic Games" (primer toque: elige el
AppImage; después: arranca directo en modo `run`), engranaje que abre los ajustes del modo de
ejecución, el estado ("preparado" o qué falta) y la tarjeta **Reanudar** cuando hay sesión viva.

**Y lo que la página cuenta sola:** si el launcher vuelve a morir, la página muestra la línea
fatal de la última sesión de Epic en su propio idioma ("La última ejecución del launcher murió:
…"), leída de `session.log` con lectura acotada (primera línea = sesión de Epic, patrones
fatales, ruido cosmético descartado, 4 000 líneas máximo). Sin zips a ciegas: el fallo se lee en
la pantalla.

## 7. Plan de prueba sugerido (en el Turbo 5 Max, cuando el reportante colabore)

1. APK de este fork + **nuevo zip universal** (importar sin descomprimir) + modo Mali experimental.
2. Si sigue en negro: pedir `session.log` y buscar `DD_MALI_*`, `DD_MALI_GPU_REPORTED:` y la
   línea de `gamescope`.
3. Mientras tanto, el **modo Software (lavapipe)** debería arrancar Steam por CPU en ese
   teléfono tal cual está hoy — útil para separar "falla del driver GPU" de "falla del stack".
