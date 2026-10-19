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

## CANCELADO (16-10-2026): todo el apartado de Epic

Por decisión del usuario se **elimina por completo** la integración del launcher de Epic de las
rondas 11 y 11-fase-2: el botón en Juegos, la sección propia en el raíl, el diagnóstico en
página y el camino sin thunks. El proyecto se centra **por completo en Steam**. (Queda intacta
la sección de Tiendas con su backend de Epic/GOG/Amazon, que es otra cosa.) Lo aprendido que
sobrevive: el guion de sesión decide los AppImage con droiddeck-fex en modo auto, un objetivo
ausente muere con líneas legibles, y droiddeck-fex honra BL_FEX_NO_THUNKS si algún día se
necesita.

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


## 6duodecies. Ronda 12: el cuello de botella al jugar — dónde está y cómo lo medimos

El techo medido en la ronda 8 sigue siendo el válido: **CPU 78-95 % con 24-26 fps** — la
tubería está atada al CPU (FEX traduciendo el juego + Wine + DXVK llamando a Vulkan). Lo que
esta ronda encontró al auditar la pila de sincronización (la parte de CPU que multiplica
round-trips por cuadro):

- **La pila ya está en su mejor punto por defecto.** El fork corre **droiddeck-fsync** (sync
  en-proceso vía `libblsession`, `futex_waitv` respondido en espacio de usuario porque el
  sandbox lo bloquea) como **primera opción en Mali**: los juegos no deberían estar en sync por
  wineserver. El pack esync (eventfd + shm compartida, el escalón siguiente) se publica por
  token de wine en `Droid-Deck/DroidDeck-Components` — el índice se actualizó el 15-10 21:50
  UTC, horas después del "1 wanted" del reporte; los packs de GE-Proton11-5/6/7 y de Valve
  Experimental/11.0 existen, y GE 11-8/11-9 aparecen como *fallidos* en el índice.
- **Lo que faltaba era evidencia, y la ronda la añade a `device.txt`:**
  1. **"Sync in use"** — al cerrar la sesión se adjunta al reporte el backend que wine dijo en
     su stderr (`fsync: up and running.` = Proton stock en-proceso; `droiddeck-fsync:` = el
     nuestro; `droiddeck-esync:` = pack; nada = wineserver). La línea "Wine sync" de arriba dice
     lo *pedido*; esta dice lo *usado* (el entorno de un juego puede cambiarlo).
  2. **Estado del pack por Proton instalado** — cada herramienta con su estado y su línea de
     versión de wine: el próximo zip responde "qué token necesita pack" sin otra vuelta.
- **Protocolo de prueba (el teléfono pone los números):**
  1. Juega 10 min con el preset FEX **PERFORMANCE_TSO** (el de fábrica) y anota los fps del HUD.
  2. Repite 10 min con **EXTREME_TSO** (la medición pendiente desde la ronda 8: la sesión de
     Craft Sell a 24-26 fps corría EXTREME_TSO; si PERFORMANCE_TSO da igual o más, ahí queda).
  3. Manda el zip: con "Sync in use" sabremos si el juego corrió fsync en-proceso o si cayó a
     wineserver (y si cayó, el token del pack que hay que publicar está en el mismo reporte).


**Segunda muerte (21:21, zip `…-21-epicgameslauncher-appima`):** ya con `BL_FEX_NO_THUNKS` en
el arranque, el launcher murió **antes de FEX**: `droiddeck-session: line 485: …AppImage: No
such file or directory`. Tres fallos apilados, los tres arreglados:

1. **La señal viajaba como argumento, no como variable.** La lista del invitado es un
   `env -i …` — lo añadido tras el guion de sesión es un argumento del programa. El
   `BL_FEX_NO_THUNKS=1` se añadía después del programa: `droiddeck-fex` jamás lo vio. Ahora se
   añade entre las variables (antes del guion), con la condición sobre `SessionState.program`.
2. **El paquete era nuevo y FEX no existía aún.** Esta sesión no fue del APK experimental Mali
   sino del `software-mode-latest` (paquete recién instalado, Steam nunca corrió en él): sin
   FEX-Emu ni runtime sniper. Antes de gastar una sesión, la página de Epic lo dice —
   "Este programa está hecho para PC x86_64 y se ejecuta con FEX, que aún no está listo. Abre
   Steam una vez…" (el aviso ya existía para apps de usuario; ahora también en Epic, vía
   `LinuxFex.ready()`).
3. **El guion ejecutaba el AppImage a pelo si su sonda `od` fallaba.** La detección x86 del
   modo `run` usa `od` (2 bytes en el offset 18); si no da 3/62, el AppImage se ejecuta
   directamente y el kernel ARM responde ENOENT (igual que un intérprete ELF faltante) — el
   mensaje más criptico posible. Ahora los AppImage los decide `droiddeck-fex` (`--mode auto`,
   su propia lectura ELF; ARM nativo si lo es, y el mensaje "x86 Linux programs need FEX … open
   Steam once" si no está instalado), y un objetivo ausente o vacío muere con líneas legibles
   ("does not exist in the guest", "is empty") que la página de Epic reconoce.

## Primer informe del zip importado (16-10-2026): Dark Souls 1 — el crash lo trae la copia

Sesión `DroidDeck-2026-10-16-06-steam` (X6 Pro, paquete Mali): la importación R10 funcionó de
punta a punta — 3 accesos directos escritos, mapeados a `droiddeck-proton-arm64`, prefijo nuevo
y **DARKSOULS.exe corriendo como i386 bajo wow64+FEX con D3D9 vivo** (`DARKSOULS_d3d9.log`:
swapchain 1280x720, 4 imágenes, 119.7 Hz, 8 hilos de compilación, caché creada, **cero
errores**). A los ~25 s murió con `Unhandled page fault ... in lgoboost (+0x53535)`:
**`lgoBoost.dll`**, un inyector/optimizador que trae la copia (en `gamedata-2\gamedata\Hi\`),
consultó WMI (`wbemprox`) y se cayó leyendo NULL — no es el juego ni el port. Además quedó
constancia de que **el pack esync de Valve Experimental ya está en uso** ("1 pack(s) in use,
0 wanted") — el techo de la ronda 12 resuelto para este Proton. Acción del usuario: borrar o
renombrar `lgoBoost.dll` (y lanzar DARKSOULS.exe directamente), y re-apuntar el acceso directo
al exe real si el elegido era un lanzador.

## Segundo informe (16-10-2026, tras quitar lgoBoost): DS1 arranca, pero vídeos/menús corruptos y partida en negro

**Confirmado por el usuario**: sin `lgoBoost.dll` el juego abre ("ahora sí ya puedo jugar").
Sesión `DroidDeck-2026-10-16-10-steam` + captura (X6 Pro, G615): ahora el problema es gráfico —

- **Menús y pantallas de carga**: barras de color de TV + estática, con el texto del juego
  ("In the Age of Ancients,") dibujado correcto encima → la presentación va bien; lo que se
  corrompe es **la subida de texturas dinámicas/vídeo** (los FMV de DS1 y sus fondos de menú
  son texturas que se re-suben cada frame en D3D9).
- **En partida**: pantalla **completamente negra**, solo se ven los iconos del overlay táctil;
  HUD Fps: 18, Ram: 82%. El overlay es app-side y funciona → el negro no es del presentador.
- Logs limpios: `DARKSOULS_d3d9.log` sano (swapchain 1280x720 A8R8G8B8 ventana, "Auto Depth
  Stencil: false" con formato D24S8, 1440 shaders de caché encontrados, GPL soportado,
  memory type mask 0x7), sin errores en session.log. **No es Proton: apunta al camino de
  textura/profundidad del driver panvk en G615.**

Sospechosos, por orden:

1. **AFRC sobre texturas BC/dinámicas** del driver rc3-lowmem (comprime texturas a tasa fija
   Arm ~8bpp; una discrepancia ahí produce exactamente ruido/barras en texturas re-subidas).
   **Palanca nueva**: `Download/droiddeck-mali-afrc` con `0` (la sesión exporta
   `PANVK_BC_AFRC=0` y el driver guarda esas texturas exactas, a coste de RAM), o por juego en
   los ajustes de inicio (opción `PANVK_BC_AFRC` = 0/16/24/32). La línea
   `mali: PANVK_BC_AFRC=…` queda en logcat al arrancar.
2. **D24S8 como profundidad en panvk/G615** (encaja con 3D negro + overlay vivo); el juego pide
   D24S8 con "Auto Depth Stencil: false" — si el punto 1 no arregla, siguiente prueba: que el
   driver tome D24S8 como D32S8 interno.
3. **WSI dma-buf/AFBC**: poco probable ya (el OSD y el texto salen bien); queda el A/B
   `PANVK_KBASE_DRI3=0` para la corrupción de vídeos si el punto 1 no basta.

## Tercer informe (17-10-2026 por la mañana): la degradación BC es DE DISEÑO en rc3-lowmem, y DS1 ahora va con decode exacto por defecto

El zip "log juego externos 2" trajo dos sesiones: **Mecha con el Shipping.exe** (ver su sección)
y **DS1 por la mañana** (6 min, 08:50-08:56 local, captura 08:55: en partida el HUD, las barras
de vida y los menús del juego SÍ se dibujan; el mundo 3D sigue negro). Esa sesión salió **sin
recolectar** (sin `artifacts_collected`, sin `.complete`, sin sección "Sync in use") y **sin
rastro de `PANVK_BC_AFRC`** — `apply_config` aplicaba la configuración en silencio, así que no
se podía distinguir "no la activó" de "la activó y no se ve".

Hecho clave aprendido de upstream (notas de rc3-lowmem): la degradación BC **es intencional** —
"BC textures are decoded into an AFRC shadow image... look slightly worse", BC1 RGB y BC7 a 8 bpp
con error máx. 38-61 LSB en bloques aleatorios; upstream solo probó NFS (pocas texturas BC) y NO
hay release más nuevo que rc3-lowmem (checks de hoy). DS1 está lleno de BC → candidato perfecto.

**Cambios de este commit (APK nuevo):**

- **Dark Souls ya no necesita tocar nada**: nuevo motor-fix en `droiddeck-game-env` que detecta
  `DARKSOULS.exe` y exporta `PANVK_BC_AFRC=0` (decode exacto, coste RAM previo a lowmem; la
  opción de la página del juego puede sobreescribirlo). Probado: la sesión imprime
  `droiddeck-game-env: Dark Souls: exact BC decode...` + `droiddeck-game-env: env: PANVK_BC_AFRC=0`.
- **Observabilidad**: `droiddeck-game-env` imprime ahora UNA línea `env: NOMBRE=valor, ...` con
  todo lo que la configuración y los fixes cambiaron (antes era invisible) — la próxima duda
  "¿estaba activo X?" se lee en session.log. Y `droiddeck-session` hace eco del switch global
  (`== mali bc: PANVK_BC_AFRC=...`) cuando el archivo `Download/droiddeck-mali-afrc` está.
- Si con decode exacto DS1 **sigue** con barras/negro: AFRC queda descartado como causa y el
  siguiente sospechoso es el camino D24S8 (punto 2 de la lista anterior).

## Cuarto informe (17-10-2026 mediodía): PANVK_BC_AFRC=0 REINICIÓ el X6 Pro — auto-fix retirado; la vía es el paquete RC3 "exact BC"; y el Mecha era OnlineFix

Sesión `2026-10-17-02-steam` (zip subido al mediodía): la línea de observabilidad nueva probó su
valor — `droiddeck-game-env: env: ... PANVK_BC_AFRC=0 ...` quedó registrado, así que el auto-fix
SÍ se aplicó. DS1 arrancó (foco 10:12:56), heartbeats sanos (2.2 GB disponibles — **no era
RAM**), y a los ~90 s el log se corta a mitad de escritura; la sesión terminó "without teardown"
y se recuperó al reiniciar la app a las 10:49 → **el dispositivo se reinició solo**, corriendo
DS1 con decode exacto. En las 3 sesiones previas con el AFRC por defecto nunca pasó.

Conclusiones:

- **El camino "exact decode" del build rc3-lowmem es inestable en el G615** (kernel/kbase), no
  solo lento. El auto-fix `PANVK_BC_AFRC=0` para DS1 quedó **retirado** del `droiddeck-game-env`
  (queda el comentario con el motivo, la opción manual de la página del juego sigue para
  experimentos, y el texto de la opción ahora avisa del reinicio).
- **La alternativa correcta es otro binario**: el paquete nuevo
  **`DroidDeck-Mali-CSF-PanVK-kbase-rc3-exactbc.zip`** (RC3 sin el parche lowmem: BC1 RGB a 12 bpp
  y BC7 a 16 bpp **exactos, sin AFRC en absoluto** — el camino original que upstream validó
  38/38 PanProbe en el MISMO POCO X6 Pro). El workflow `mali-panvk-driver` empaqueta y publica
  ahora las DOS variantes en `mali-panvk-latest`, y la pestaña de drivers del APK ofrece las dos
  filas. Cuesta RAM extra (lo que ahorra lowmem), pero DS1 es un juego de 2007 y sobra.
  **Instrucción al usuario: instalar el paquete RC3 exact-BC y re-probar DS1.**
- Sospechoso D24S8 sigue en pie si con RC3-exactbc el mundo sigue en negro (las barras de los
  vídeos deberían desaparecer por BC exacto; el negro de partida es otra pata).

**Mecha Chameleon — resuelto el misterio del diálogo**: la captura del usuario lo dice:
**"Failed to get OnlineFix interface."** — OnlineFix (online-fix.me) es el **crack multijugador
que trae la copia pirata** del juego. No es "falta de componentes" de DroidDeck: es el propio
crack el que no inicializa bajo Wine (y explica también el MessageBox original del launcher).
No se ayuda a hacer funcionar el crack; el juego cuesta 5,99 € en Steam, funciona por Proton
(verificado "Playable" en Steam Deck), y por esa vía (Steam real) es como está soportado.

## Mecha Chameleon (16-10-2026, sesión 09-steam): no inicia, "falta de componentes" — RESUELTO

appid local 15188656168751857664 → `MECCHA CHAMELEON/MECCHA CHAMELEON/PenguinHotel.exe`, prefijo
`compatdata/3536384592`, esync vivo. A ~25 s: MessageBox Win32 (`#32770` + `Button`, con stubs
uiautomation/oleacc) y salida limpia por `AppPolicyGetProcessTerminationMethod`. Sin ningún
`err:module` (WINEDEBUG lleva `+err`, un import estático faltante se habría registrado).

**Causa raíz**: el juego es **Unreal Engine 5.6 + Epic Online Services** (Steam App ID 4704690,
lemorion_1224/Haganeiro) y `PenguinHotel.exe` es el **launcher de UE5**, no el juego. Ese
launcher comprueba el runtime y saca el diálogo **"Microsoft Visual C++ 2015-2022
Redistributable (x64) is required to run this program"** (= el "falta de componentes" que vio
el usuario) — y lo saca **incluso con el redist instalado**: bajo Wine/CrossOver su chequeo
falla siempre. Verificado por terceros en CrossOver (allthings.how, jun-2026): saltándose el
launcher el juego corre entero.

**Fix**: apuntar el juego directamente a la build shipping de UE5:
`MECCHA CHAMELEON/MECCHA CHAMELEON/PenguinHotel/Binaries/Win64/PenguinHotel-Win64-Shipping.exe`
(en DroidDeck: página del juego → cambiar el ejecutable / re-importar apuntando al
`*-Shipping.exe`). Alternativa si se quisiera el launcher: instalar el VC++ 2015-2022 x64 en el
prefijo (componente de Windows del juego) — pero el chequeo puede seguir fallando igual, así
que el `-Shipping.exe` es el camino fiable.

**Resultado del intento del usuario (zip "2", sesión 2026-10-16-11, APK nuevo confirmado por el
nuevo "Sync in use"): el Shipping.exe ya NO es el chequeo del launcher — el juego arranca de
verdad** (vcruntime `__telemetry_main_invoke_trigger` en 3 módulos: CRT de UE5 vivo; el runtime
VC++ está bien) pero a ~56 s saca OTRO MessageBox `#32770`+`Button` y sale limpio
(`__telemetry_main_return` = descarga ordenada). Sin un solo `err:` mientras corría. Candidatos:
**Epic Online Services** (el juego lo exige para matchmaking; su SDK saca MessageBox propio al
fallar) o el diálogo de UE5 "Out of video memory" (RHI sin espacio con heap al 12%). Siguiente
paso: **texto/captura del diálogo**; red activa en la sesión (su network.txt: WiFi validado ✓).

## 7. Plan de prueba sugerido (en el Turbo 5 Max, cuando el reportante colabore)

1. APK de este fork + **nuevo zip universal** (importar sin descomprimir) + modo Mali experimental.
2. Si sigue en negro: pedir `session.log` y buscar `DD_MALI_*`, `DD_MALI_GPU_REPORTED:` y la
   línea de `gamescope`.
3. Mientras tanto, el **modo Software (lavapipe)** debería arrancar Steam por CPU en ese
   teléfono tal cual está hoy — útil para separar "falla del driver GPU" de "falla del stack".

## Quinta ronda (17-10-2026 tarde): MiSide corre; falta teclado/ratón en pantalla → TouchKeys; DS1 sigue negro (falta confirmar el driver)

**Buena**: MiSide (Unity) arranca y juega al 35-40 fps con texturas y menús perfectos en el
mismo X6 Pro (capturas 15:16-15:17) — el stack panvk+kbase sostiene un juego moderno. La
corrupción BC no aparece aquí (pocas BC o el motor evita el camino afectado).

**Pedidos del usuario**: controles estilo Winlator para juegos solo-teclado/ratón. Hecho en
este commit — **TouchKeys** (`input/TouchKeys.kt`), capa propia del fork junto al mando de
Steam: botones que aprietan teclas de PC reales (por wl_keyboard, el canal del teclado PC),
botones de clic izquierdo/derecho (por el puntero de escena) y un **touchpad** relativo
(`WaylandCompositor.sendPointerDelta`, con click al toque). Se añaden desde el cajón →
Controlador → "Teclado y ratón en pantalla"; el modo edición arrastra, pellizca el tamaño y
borra (chip ✕), ✓ guarda. Guardado global como JSON en las preferencias "touch_keys", con
tests Robolectric (TouchKeysStoreTest).

**DS1**: el usuario dice "sigue igual, negro en partida" pero **sin zip** no hay manera de
saber si corrió con el paquete RC3 exact-BC o con el lowmem. El reporte de sesión ya imprime
`Linux driver: <nombre> <versión>` (de meta.json), así que el próximo zip lo dice solo.
Si confirma RC3-exactbc y sigue negro → siguiente sospechoso D24S8 vía el adaptador ICD.

## Sexta ronda (17-10-2026 noche): DS1 con RC3-exactbc estable pero negro en partida → BC descartado; sospechoso restante: profundidad (D24S8)

Sesión `2026-10-17-16-steam` (zip subido de noche): el device.txt prueba que el usuario instaló
el paquete nuevo — `Linux driver: DroidDeck Mali CSF PanVK-kbase v0.1.0-rc3 exact BC`. DARKSOULS.exe
llegó a **1.1 GB RSS** con heartbeats sanos (2.2→1.4 GB disponibles, cpu 64-79%) durante varios
minutos y **NO hubo reinicio** (el juego se cerró y la sesión quedó "without teardown" por la
salida de la app). **Con BC exacto el mundo sigue negro en partida** → la causa del negro NO es
AFRC/BC (esa era la de las barras de los menús/vídeos). El usuario no dejó captura de DS1: falta
saber si los menús/vídeos se arreglaron con RC3-exactbc (para separar BC-render de profundidad).

### Séptima ronda — el log de d3d9 condena D24S8 (minado del zip 16-steam)

`steam/DARKSOULS_d3d9.log` (DXVK v3.1.1-27-g25ca63f, Mali-G615 MC6, panvk 26.2.99):

- `D3D9: VK_FORMAT_D16_UNORM_S8_UINT -> VK_FORMAT_D24_UNORM_S8_UINT` — a panvk le falló el check
  de D16S8 (Mali no tiene D16S8 empaquetado) y el fallback elegido fue **D24S8**, o sea que panvk
  anuncia D24S8 con DEPTH_STENCIL_ATTACHMENT + SAMPLED_IMAGE. Toda la profundidad de DS1 va por
  D24S8 (el propio juego pasa `Auto Depth Stencil: false` con `D3D9Format::D24S8` y crea la
  superficie a mano).
- El presenter está sano (1280x720, BG8A8, PRESENT_IMMEDIATE, 4 imágenes, 119.7 Hz) — otra vez,
  el negro no es del swapchain.
- En `docs/panprobe-dxvk-coverage.md` del repositorio del driver, los autores dejan escrito que
  **D24/D16/D32S8 solo tienen cobertura QUERY (propiedades de formato), sin validación de
  ejecución ni stencil** en este stack. Menús bien + mundo negro en DS1 (que lee la profundidad
  como textura para la niebla/DoF) es exactamente la firma de un D24S8 roto al ejecutar.

El fallback ya existe en DXVK (stock, commit 25ca63f, `d3d9_format.cpp:550-554`): si D24S8 no se
anuncia soportado, D24S8→**D32_SFLOAT_S8_UINT** (nativo de Mali) y D16S8→D32S8. Y hay opción de
config **sin recompilar nada**: `d3d9.useD32forD24 = True` (d3d9_format.cpp:498).

**Prueba A/B (hoy, sin APK ni driver nuevo):** página del juego DS1 → Entorno → DXVK_CONFIG →
`d3d9.useD32forD24 = True` → relanzar. Si el mundo se dibuja, D24S8 de panvk queda condenado y
se hornea en el driver (tabla de formatos sin D24S8 → todos los D3D9 van por D32S8; el camino
DXGI ya cae solo a D32S8 cuando el formato no se anuncia). PROTON_USE_WINED3D queda como segunda
opción (en este runtime no hay GL, puede no arrancar).

**Resultado del A/B (reportado por el usuario, con captura):** con
`d3d9.useD32forD24 = True` el mundo sigue negro y **baja a menos de 8 fps** (los menús siguen
bien). D32S8 es el doble de ancho de banda y no arregla el negro → el D24S8 de panvk no es la
causa única; **se recomienda al usuario quitar esa línea** (o dejarla vacía). La pantalla de
objetos de DS1 sí se dibuja (captura: cajas de ítems con el athamu) — el composite del mundo 3D
es lo que queda negro.

Siguiente generación de palancas — los switches del propio driver (`PANVK_DEBUG`, documentados
en RUN-PC-GAMES-ON-MALI.md del repo del driver), ahora exposición como opción **PanVK debug
flags** del editor de entorno (Múltiple, nada aplicado hasta elegir):

1. `no_crc` — sin checksums CRC de render targets (el sospechoso clásico de un RT que se lee
   negro en Mali; barato de probar).
2. `wsi_no_afbc` — sin AFRC/AFBC en el swapchain (el composite final).
3. `no_bc_emul` — sin decodificación BC por compute (las texturas del mundo son BC; los menús
   usan UI sin BC — encaja con "menús sí, mundo no").
4. `sync` — submissions síncronos (si fuera una carrera de barriers).

Probar de a uno, relanzando el juego cada vez; si alguno ilumina el mundo, queda identificado el
sub-sistema y se hornea como default en el paquete del driver.

## El mando táctil gana perfiles de HUD (estilo Winlator)

Tras el rechazo del apartado separado (b3ac670) y del lienzo único con extras (8711021), el
usuario pidió lo que Winlator tiene: **perfiles de HUD nombrados**. Hecho así en `ControllerPrefs`
(layouts) + `OnScreenControls` + `ControllerCustoms`:

- **Disposición** (Configuración → Mando) es un selector: "**Por defecto**" (el mando ajustado a
  la pantalla de la app) más los perfiles que cree el usuario. "Nueva disposición" crea un perfil
  **vacío** (lienzo en blanco); "Editar" abre el editor sobre el activo; "Renombrar"/"Quitar"
  gestionan la lista (quitar borra el perfil con sus controles, confirmado).
- Dentro del editor, "**Añadir**" ofrece **todo**: joystick izquierdo/derecho, **cruceta** (las
  4 flechas que se arrastran juntas), A/B/X/Y, LB/RB/LT/RT, L3/R3, Vista/Menú, Steam/QAM,
  **cualquier tecla de PC** (grilla), clics izq/der del ratón, **joystick WASD** y touchpad
  relativo. Cada control tiene **Forma** (círculo → redondeado → cuadrado) y **Quitar** en
  "Gestionar". Se arrastra como cualquier botón.
- **Las posiciones viven dentro del perfil** (x/y normalizados), así que cambiar de perfil
  cambia el HUD completo — uno por juego, se elige en Disposición antes de lanzar (Winlator lo
  ata al contenedor; aquí v1 es global, pendiente atarlo por juego si el usuario lo pide).
- KIND_GAMEPAD_BTN (6) = pieza del mando virtual (target: "a"..."ls","rs","up"...). Los sticks
  escritos por perfiles no tocan ejes salvo los del propio mando; los adaptativos solo ocultan
  los del mando base (los del usuario siempre se ven). Migración: los extras de R18
  ("customControls") entran como un perfil "Añadidos".

## Sesión 2026-10-18-13-steam — por qué no abren la copia de DS1 y AC Liberation HD

Zip minado (el último de los dos subidos; el 12-steam se ignora). El driver sigue siendo
RC3-exactbc (DD_MALI_ICD_LOAD_OK), BC emulation on.

**DS1 copia de PC (`DARK SOULS Prepare To Die E`, no-Steam):** el juego ARRANCA (menús, 800x450,
dxvk-submit/queue/cs vivos) y **revienta a los ~4 min** con page fault (0x302F3061). La causa está
justo antes del crash:

```
vkd3d_shader_verror: E5017: Aborting due to not yet implemented feature:
  Writing fx_2_0 sampler objects initializers is not implemented.
vkd3d_shader_verror: E5017: Aborting due to not yet implemented feature: Write pass assignments.
```

Wine no encontró `d3dx9_43.dll` nativa y usó la suya (builtin, compilada con vkd3d-shader HLSL),
que **todavía no implementa los efectos fx_2_0** que DS1 usa (DoF/bloom de Dark Souls). Sin el
efecto, el juego sigue con un puntero nulo y muere. La copia de Steam no sufre esto porque Steam
instala el redistributable de DirectX (d3dx9_43) — la copia cruda de PC no lo trae.

**Arreglo para el usuario:** copiar `d3dx9_43.dll` (la de 32 bits, de `C:\Windows\SysWOW64` de la
PC) junto a `DARKSOULS.exe`, y añadir en Entorno del juego `WINEDLLOVERRIDES =
d3dx9_43=native,builtin`. Vía alternativa: instalar el DirectX redist de junio de 2010 dentro del
prefix. Pendiente de nosotros: que el seeder de redists (droiddeck-seed-redists) ofrezca d3dx9
desde los paquetes del runtime si existe en ellos.

**AC Liberation HD (`ac3lhd_32.exe`):** ni arranca:

```
err:virtual:map_image_into_view failed to set 60000020 protection on binkw32.dll section BINKY16,
  noexec filesystem?
err:module:loader_init "uplay_r1_loader.dll" failed to initialize, aborting (c0000005)
```

El juego vive en `Download/DroidDeck-Games` (bind de almacenamiento compartido, **noexec**):
Wine no puede mapear la sección ejecutable de `binkw32.dll`, y el cargador de Uplay aborta.
Hollow Knight corre porque no mapea DLLs ejecutables nuevas desde su carpeta en caliente.
**Arreglo corto para el usuario:** mover/copiar la carpeta del juego adentro del árbol del runtime
(almacenamiento privado de la app, que sí es ejecutable), igual que las tiendas instalan en
`root/Games/Stores`. **Arreglo nuestro (propuesto, próxima ronda):** una opción "copiar al
almacenamiento interno" en la importación/agregar juego (GameImport hoy copia siempre a
Download/DroidDeck-Games). Nota aparte: AC Liberation HD exige Uplay; aun con exec arreglado el
DRM puede pedir más.

**La cámara de DS1 (touchpad lento):** añadido el **joystick de cámara** (KIND_MOUSE_STICK): stick
que panea el puntero cada 16 ms mientras se mantiene inclinado (a mayor tamaño, más velocidad; el
touchpad queda para apuntar/UI, el stick para la cámara de juegos 3D).

## R22 (19-10-2026): la interfaz de Steam en la GPU otra vez — con la causa del reinicio identificada

La R21 dejó el interruptor **apagado en Mali a propósito** porque el 14-10 el equipo se reinició
segundos después de que CEF pidiera Vulkan. Esta ronda encontró el porqué y prepara la vuelta,
con dos piezas: un driver compilado aquí con el arreglo y un camino gráfico distinto.

### 1. Lo que se encontró (upstream: wonderkast02/panvk-g720-kbase-csf, issue #9)

El mismo stack (DroidDeck, Steam bajo gamescope anidado, PanVK-kbase, proot) en un Mali-G720
documentó con dmesg lo que a nosotros nos faltaba: en el driver hay un **helper del "provoking
vertex" al que se le pasa su dirección de GPU recortada a 32 bits**:

```c
uint32_t fn_addr =                      /* se pierde la mitad alta */
   dev->draw_ctx->fns_bo->addr.dev + fn_idx * fn_stride;
cs_move64_to(b, addr_reg, fn_addr);
```

En kbase las direcciones virtuales son SAME_VA y altas (`0x5f_fffef000`), así que el prefetch del
command stream salta a `0xfffef000` y falla:

```
kbase: CSF group N fatal error: status 0x7d0002c1 (exception 0xc1)
       sideband 0x00000000fffef000
Unhandled page fault at VA 0x00000000FFFEF000
exception type 0xC1: TRANSLATION_FAULT at level 1, READ
```

Se paran **todas** las colas de ese contexto; Zink reporta `DEVICE LOST` y, al no poder reciclar
nada, el proceso crece varios GB. Ese camino solo se toma cuando un render pass empieza antes de
que la app declare el modo de provoking vertex: **Chromium/CEF sobre Zink lo hace cientos de veces
por command buffer**; los tests simples (vkcube) no. Con el arreglo (dirección en 64 bits), el
tester aguantó 5 minutos de Big Picture con 120 gestos, sin fallo y con el proceso de CEF en
0.45–0.7 GB. El equipo de arriba lo vio como **reinicio del teléfono**, no como crash de la app:
un fallo del CSF que el kernel no puede contener.

### 2. Lo que se construyó

- **Driver `r7 (fn64)`**: rc3 de zenithblue + el arreglo de una línea, compilado **aquí** en un
  runner ARM nativo (`.github/workflows/mali-panvk-fixed-driver.yml`, arm64 + meson + LLVM 18 para
  el front end CLC que panfrost usa en `libpan`), envuelto en nuestro adaptador ICD y publicado en
  el release `mali-panvk-latest` como
  `DroidDeck-Mali-CSF-PanVK-kbase-r7-fn64.zip` (+ `.sha256`). El resto de los assets no se toca.
- **La app exige ese driver**: con el interruptor encendido en Mali, `BL_CEF_GPU` solo viaja como
  `1` si el driver de runtime seleccionado se llama o versiona `fn64`
  (`SessionPrefs.steamUiGpuAllowed`); con el rc3 importado el navegador se queda en la CPU y el log
  dice qué driver se rechazó.
- **Camino gráfico distinto en Mali**: el navegador va **ANGLE/OpenGL → Zink → PanVK** con Kopper,
  DRI3 de X11 y **sin la capa gamescope WSI** (las tres ya estaban puestas para Mali). No se usa
  ANGLE/Vulkan en Mali: es lo que reinició el X6 Pro el 14-10, antes del arreglo y con la capa WSI
  en medio.
- **Vuelta atrás automática**: al arrancar la app, `SessionArtifacts.finishAbandoned` mira el
  `cef-gpu.txt` que deja cada sesión; si la última sesión abandonada (reinicio incluido) corrió con
  la interfaz en la GPU, apaga el interruptor y anota `session.ui_gpu_rollback`. Un experimento no
  se puede quedar pegado.

### 3. Cómo se prueba (X6 Pro)

1. **Controladores de GPU → Avanzado → Controlador de runtime → Importar**:
   `DroidDeck-Mali-CSF-PanVK-kbase-r7-fn64.zip` (sin descomprimir). Queda seleccionado; si no,
   elegirlo en la lista.
2. **Primero el driver**: abrir un juego ya probado (Craft Sell, Karlson) 2–3 minutos. Si el driver
   no dibuja, volver al paquete universal: este build no ha pisado un G615 todavía.
3. **Ajustes del modo Steam → Rendimiento → "Interfaz de Steam en la GPU (experimental)"**:
   encender. Aplica en la próxima sesión; el log debe traer
   `== ui-on-gpu: Mali - ANGLE on Zink (Kopper, X11 DRI3) over PanVK; fn64 driver required`.
4. Abrir la tienda y navegar 1–2 minutos (buscar, abrir un juego, volver). Después cerrar la sesión
   desde la app, no con el botón de apagado.
5. Si el equipo se reinicia: al volver, el interruptor estará apagado solo. La carpeta de la sesión
   (`Download/DroidDeck/<fecha>-NN-steam/`) tendrá `cef-gpu.txt=1` y `ended-without-teardown.txt`:
   eso es exactamente lo que hay que reportar.

## R22b (19-10-2026): el driver `r7 (fn64)` arranca y dibuja — los 0-3 fps eran dos ajustes, no el driver

Sesión `Download/DroidDeck/2026-10-19-01-steam/` (POCO X6 Pro, app 0.3.2 (44), driver importado
`…r7_fn64_Steam_UI_fix_universal_`, interfaz de Steam **en la CPU**: `-cef-disable-gpu`). Steam
abrió, Karlson arrancó y **no hubo ni un fallo de GPU ni un reinicio**: el build `fn64` no se cayó
en ningún momento. Lo que falló fue el rendimiento: 0-3 fps y la RAM al 91%.

### 1. Lo que dice el log, con números

El compositor de la app recibe de gamescope lo que gamescope produce. Durante el juego, en ventanas
de 10 s (`wayland.log`):

```
12:22:32  stats last 10 s: 89 frames on screen (8.9 fps)
12:22:42  stats last 10 s: 32 frames on screen (3.2 fps)
12:22:52  stats last 10 s: 19 frames on screen (1.9 fps)
12:23:02  stats last 10 s: 10 frames on screen (1.0 fps)
12:23:12  stats last 10 s: 12 frames on screen (1.2 fps)
12:23:22  stats last 10 s: 29 frames on screen (2.9 fps)
```

Con la interfaz de Steam sola, a 2712x1220, el mismo camino daba **20-28 fps**; o sea, la interfaz
no era el problema del juego, pero ya venía a un tercio de lo normal. Y no es el present de la app:
`render_scene` del compositor se quedaba en 6-11 ms de media. Lo que llega es lo que hay.

Para comparar, la sesión `2026-10-17-02-steam` (mismo teléfono, misma app 44, driver
**rc3-lowmem**), a **1600x720**:

```
10:12:33  stats last 10 s: 383 frames on screen (38.3 fps)
10:12:43  stats last 10 s: 558 frames on screen (55.8 fps)
10:12:53  stats last 10 s: 548 frames on screen (54.8 fps)
```

55 fps en Dark Souls. La diferencia entre las dos sesiones no es el juego: es la configuración.

| Ajuste | 17-10 (55 fps) | 19-10 (0-3 fps) |
|---|---|---|
| Resolución (Steam) | 720p · 1600x720 | **Seguir la pantalla · 2712x1220** |
| Preajuste de FEX | PERFORMANCE_TSO | **EXTREME_TSO** |
| Sincronización de Wine | esync | ntsync |
| Override de núcleos del cliente | no | sí, 0-7 (equivalente a no ponerlo) |

### 2. Por qué EXTREME_TSO da 0-3 fps y no es "un preset extremo de velocidad"

`FexPreset.kt` lo enseña: `EXTREME_TSO` es `PERFORMANCE_TSO` **más** `FEX_DISABLEL2CACHE=1`,
`FEX_DYNAMICL1CACHE=1` y sus heurísticas (250/50), y `FEX_SMCCHECKS=none`. Es decir, **tira la caché
L2 de traducción de FEX y encoge la L1**: el código x86 de Unity se vuelve a traducir todo el rato.
Con un Unity (Karlson) eso es CPU pura: la sesión estaba a **78-96% de CPU sobre 8 núcleos**, que es
el equipo entero ocupado traduciendo, no dibujando. Es el preset para *ahorrar memoria* cuando una
sesión muere por RAM, no para jugar.

### 3. De dónde salió el 91% de RAM

- Al arrancar ya había **1,7 GB disponibles** (`device.txt`; el 17-10 eran 2,9 GB, y el primer
  latido de hoy dio 1.527 MB): apps de fondo abiertas.
- A 2712x1220, el navegador de Steam (CEF, en CPU) pasó a **1.071 MB en 8 procesos**
  (`heartbeat 12:20:16`); a 720p el mismo cliente rondaba 300 MB.
- Xwayland 465 MB, Karlson 656 MB, explorer 200 MB, la app 124 MB.
- `== memory low (753 MB)` a las 12:22:35, con esos ocho procesos listados.
- El patrón de siempre, pero con buffers 2,9× más grandes: **181 dma-bufs de 13.275.136 B**
  (2712x1220x4 + ~40 KB de AFBC, o sea el tamaño del swapchain) a ~1 por segundo, y los fds de
  Xwayland hasta **6.345**. En las sesiones de 720p el mismo patrón aparece con 4.608.000 B y los
  fds se quedan en ~7.700: no es nuevo, lo que cambió es el tamaño de la ventana.

### 4. Qué se hace

1. **Ajustes del modo Steam → Pantalla → Resolución → 1600×720** (se aplica en la próxima sesión).
2. **Ajustes del modo Steam → Juegos → Preajuste de FEX → PERFORMANCE_TSO** (se aplica al próximo
   juego). Si el juego va justo de CPU, `PERFORMANCE` (sin TSO) es el siguiente escalón.
3. Cerrar apps de fondo antes de abrir la sesión.
4. Repetir Karlson 2-3 minutos con el driver `r7 (fn64)`.
5. **Si sigue a 0-3 fps**, cambiar **Componentes → Controlador de runtime → rc3-lowmem** y repetir
   con los mismos ajustes: esa es la comparación limpia driver sí / driver no. Si el `r7` pierde
   contra `rc3-lowmem`, el siguiente build es el `fn64` sobre `panvk-kbase-v0.1.0-rc3-lowmem`.

La sesión ahora escribe una línea cuando corre por encima de 1600x720 en Mali
(`== note: the session is 2712x1220; …`), para que ese ajuste se vea en el log antes de sacar
conclusiones sobre el driver.
