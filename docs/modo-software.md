# Modo software (lavapipe) - qué hay, qué se probó y qué falta probar

Objetivo de esta rama: un arranque de sesión que **no toque la GPU en absoluto**, para validar la
pila (proot + FEX + Proton + gamescope) sin riesgo para el teléfono. Todo lo de aquí se compiló en
GitHub Actions; nada se probó en un teléfono, porque no tengo acceso a uno.

## Qué instalar

1. **APK** (se instala como actualización sobre lo que ya tienes, no pierde el runtime ni Steam):
   <https://github.com/jdmg28/DroidDeck-Mali-G615/releases/tag/software-mode-latest>
   `DroidDeck-Mali-G615-debug.apk`, versionCode **20**.

2. **Driver de CPU** (opcional; sin él la sesión arranca igual y sigue sin ver la GPU, pero dibuja
   con el driver del runtime):
   <https://github.com/jdmg28/DroidDeck-Mali-G615/releases/tag/software-mode-lavapipe>
   `DroidDeck-Software-Vulkan-lavapipe.zip`, 52 967 205 bytes (50,5 MB).

   En la app: **Components → GPU drivers → Advanced → Runtime driver → Import** y elegir el zip.
   "GPU safety" queda en **Auto**, que en un dispositivo Mali significa software; no hay que tocar
   nada más.

## Por qué el APK instala como actualización

Medido sobre el APK publicado en `gpu-guard-latest` (que es el que hay instalado), con `aapt` y
`apksigner`:

| | instalado | este build |
|---|---|---|
| asset | `DroidDeck-GPU-Guard-debug.apk` | `DroidDeck-Mali-G615-debug.apk` |
| applicationId | `com.droiddeck.launcher.mali.g615` | `com.droiddeck.launcher.mali.g615` |
| versionCode | **12** | **20** |
| certificado (SHA-256) | `a40da80a…1bf5dc` | `a40da80a…1bf5dc` |

Mismo `applicationId`, certificado idéntico y versionCode mayor: Android lo acepta como
actualización. La versión del runtime sigue siendo **r9**; no se tocó, así que no hay
re-extracción.

**Sobre la firma:** el APK instalado está firmado con la **AOSP testkey** (`keystore/testkey.p12`,
la que viene en el repositorio y la que usan `app/build.gradle`, `tools/build_local.sh` y
`.github/workflows/build.yml`). CI **no genera una clave nueva en cada ejecución**: la clave es ese
archivo. Por eso mi APK, firmado con la misma, actualiza sin problema.

Esto choca con lo que pediste ("nunca firmar con la testkey"), y conviene decirlo claro: si cambio
de clave ahora, la actualización falla y la única salida es desinstalar, con los 5 GB. Así que
**la clave se queda como está hasta que decidas lo contrario**, y el momento de cambiarla es uno en
el que puedas permitirte reinstalar. Cuando quieras: se crea una clave propia
(`tools/release/make-release-key.sh`), se guarda como secreto `RELEASE_KEYSTORE_B64` en el
environment `signing-main` y `sign.yml` la usa sin que pase por el repositorio.

## Qué hace el modo software

- `LinuxRuntime.binds()` ya no llama a `bindGpuNode()` (línea 278): no se crea ningún
  `/dev/dri/renderD*` falso, ni `/sys/class/drm`, ni `/sys/dev/char/…` a partir de `/dev/mali0`.
- Además, se monta un archivo vacío sobre **cada** `/dev/mali*` y `/dev/panfrost*` que exista
  (`hideGpuNodes()`, líneas 301-330). Aunque el resto de `/dev` entre al guest, ahí no hay GPU:
  `open()` falla y `test -c /dev/mali0` es falso dentro de la sesión.
- En el guest, `droiddeck-session` (líneas 72-119) con `BL_GPU_SAFETY=software`:
  - **no** ejecuta `droiddeck-mali-check` (el probe que hacía `dlopen` del ICD: trabajo de GPU);
  - **no** exige el driver Mali (`DD_MALI_DRIVER_REQUIRED`);
  - imprime si `/dev/mali0` es o no un char device dentro del guest, que es la comprobación directa.
- Adreno no cambia en nada: `GpuSafety.software()` solo es verdadero en Mali con Auto; en un
  Adreno el comportamiento es exactamente el de antes.

## Verificado (medido, no supuesto)

- **glibc del runtime: 2.43.** El runtime `r9` es Arch Linux ARM; su `libc.so.6` dice
  `GNU C Library (GNU libc) stable release version 2.43.`. El driver pide `GLIBC_2.38`. **La puerta
  que pediste que cerrara el trabajo si no se cumplía, se cumple con mucho margen.**
- **Las librerías que el driver necesita y el runtime no tiene**, resueltas midiendo (no leyendo
  las `Depends:` de los paquetes): el zip lleva `libLLVM.so.19.1` (Debian), más `libedit.so.2`,
  `libtinfo.so.6` (no: esa la tiene el runtime), `libbsd.so.0`, `libmd.so.0`, `libz3.so.4` y
  `libxml2.so.2`. Cada una lleva `RPATH $ORIGIN`, así que se encuentran entre sí en el directorio
  donde el importador las deja.
- **Cada `NEEDED` de cada librería resuelve dentro del runtime**, comprobado con el cargador del
  propio runtime bajo qemu.
- **El ICD carga y enumera un dispositivo** a través del `libvulkan.so.1` del runtime (1.4.357):

  ```
  probe: manifest declares api 1.4.0
  probe: vkCreateInstance for Vulkan 1.4.0 -> 0
  probe: vkEnumeratePhysicalDevices -> 0, 1 device
  probe: device 0: llvmpipe (LLVM 19.1.7, 128 bits) (Vulkan 1.4.305, vendor 0x10005, type 4)
  ```

  `type 4` es `VK_PHYSICAL_DEVICE_TYPE_CPU`. Eso es lo más parecido a "funciona" que se puede
  obtener sin el teléfono.
- **405 tests unitarios pasan** (incluidos `GpuSafetyTest` y `DriverArchivePolicyTest`).

## Hipótesis (no verificado)

- Que proot aplique el bind del archivo vacío sobre `/dev/mali0` exactamente como espero. La
  teoría es sólida (es el mismo mecanismo que ya usa la app para `/sys/fs/selinux`), pero **en el
  teléfono se comprueba en 10 segundos** y es la primera cosa que quiero ver.
- Que gamescope acepte un dispositivo de CPU sin `VK_EXT_physical_device_DRM` ni render node.
  Podría negarse; en ese caso la sesión arranca y muere, sin tocar la GPU igualmente.
- Que 30 FPS con Steam sigan siendo el objetivo a largo plazo: **con lavapipe no se juega**, un
  fotograma tarda segundos. Esto es para validar la pila, no para jugar.

## Qué probar en el teléfono y qué pegar de vuelta

1. Instala el APK sobre el actual. Debe instalar **sin** desinstalar nada y sin pedir permiso
   raro. Si dice "la aplicación no se instaló" o pide desinstalar: **para** y pega el mensaje.
2. Abre la app. **No debería haber ninguna prueba de GPU al abrir.** Si ves algo de "check",
   "probe" o "Mali" en pantalla, pégalo.
3. Arranca una sesión y pega las primeras 40 líneas del log. Busco estas dos:
   - `== gpu safety: SOFTWARE - /dev/mali0 is not a character device in the guest`
   - la **ausencia** de `droiddeck-mali-check`
4. Dentro de la sesión, si puedes: `ls -l /dev/dri /dev/mali0 2>&1` y `test -c /dev/mali0; echo $?`
   (esperado: `/dev/dri` no existe o está vacío, y `$?` = 1).
5. Importa el zip y rearranca. Pega el log: debe decir qué driver Vulkan usa y, si llega,
   `llvmpipe (LLVM 19.1.7, 128 bits)`.
6. Si el teléfono se congela o reinicia: **dilo inmediatamente** y no vuelvas a arrancar la sesión.
   Eso significaría que algo sigue tocando la GPU y hay que encontrarlo antes de seguir.

Para cualquiera de estos pasos, pegar el texto tal cual (o una captura) es suficiente.

## El driver Mali experimental: veredicto

**No es seguro como comportamiento por defecto, y no en el teléfono que tiene los 5 GB.** El
adapter que sí se puede leer (`src/droiddeck_mali_icd.c`) es pequeño y cuidadoso: no abre la GPU,
solo `stat()`, y lo único que falsea es un metadato ("este dispositivo tiene render node DRM"). El
problema está alrededor:

- `LinuxRuntime.bindGpuNode()` entrega `/dev/mali0` al guest como `/dev/dri/renderD<minor>` **en
  todas las sesiones de un Mali**, con o sin driver Mali elegido (era incondicional antes de esta
  rama).
- Quien dibuja es `libpanvk_backend.so`, 21 MB sin código fuente en el paquete, mezcla de forks de
  terceros.
- La evidencia que traen (WSI, swapchain, AHB) es de **otro binario** (`725b13c7…`), no del que se
  entrega (`0b8eaf70…`).
- Ya colgó el teléfono una vez, sin dejar rastro en los logs de Android.

El detalle completo está en [`docs/mali-experimental-audit.md`](mali-experimental-audit.md).
