# R28 — 0.7.1 oficial, mejorada

Fecha: 2026-11-16 · Rama `arena/cd8ab18a-droiddeck-mali`
Base: **0.7.1** (`cdff94e72`, 10-11-2026) de [Droid-Deck/DroidDeck](https://github.com/Droid-Deck/DroidDeck)
Merge: `2b5cb7ce8` · Último commit de la ronda: `bd29b603e`

La pregunta de esta ronda era una sola: **¿qué le falta al 0.7.1 oficial para ser el fork, sin
perder nada del oficial?** La respuesta es este árbol: 95 ficheros y +7 188 líneas sobre la
etiqueta oficial, con las cuatro rutas Mali del oficial intactas y las piezas propias portadas
encima de su código, no al lado.

El build de CI de esta ronda terminó **verde** en los dos workflows:

| Workflow | Run | Resultado |
|---|---|---|
| Build APK (compilación + 3 874 tests de unidad + Python + nativos) | [43815365695](https://github.com/jdmg28/DroidDeck-Mali/actions/runs/43815365695) | ✅ |
| Mali-G615 branch APK (APK firmado, «instala sobre el publicado») | [43815365686](https://github.com/jdmg28/DroidDeck-Mali/actions/runs/43815365686) | ✅ |

APK de la ronda (en `software-mode-latest`): `versionCode 450161`, paquete
`com.droiddeck.launcher.mali.g615`, certificado `a40da80a…bf5dc`.

---

## 1. Qué se conservó del fork

Todo lo que el fork había ganado en R19–R25, ya sobre el código de 0.7.1:

| Pieza | Dónde | Qué hace |
|---|---|---|
| Kit PanVK-kbase + fn64 | `tools/mali-driver/`, `driver-patches/0001-…` | Driver propio (Linux 23-10 + `provoking-vertex`) y adaptador ICD que sintetiza `VK_EXT_physical_device_drm` |
| `PANVK_KBASE_DRI3`, `GPU_SEMAPHORE_WAITS`, `PANVK_BC_AFRC` | `SessionService.kt`, `droiddeck-game-env`, `droiddeck-session` | Los tres interruptores de la ruta nativa |
| `BL_MALI_GAMESCOPE_WSI` off | `SessionService.kt` | gamescope no toma el WSI de Mali por defecto |
| Política CEF en CPU | `SessionService.kt` | El navegador de Steam va en CPU salvo driver `fn64` (`SessionPrefs.steamUiGpuAllowed`) |
| Escalera de seguridad | `session/RebootGuard.kt` | 3 reinicios con juego → `SAFE_PRESENT` → `NO_SEMA` → `SMALL`; el usuario la limpia desde el diálogo de modo |
| Auto-instalación | `gpu/MaliAutoSetup.kt` | Primer arranque en Mali: instala y elige el PanVK del repo **sólo si nada más puede dibujar** |
| Interruptor de GPU | `gpu/GpuSafety.kt` | `AUTO` / `SOFTWARE` / `MALI`, resuelto en `SessionService` |
| Catálogo propio | `gpu/MaliReleases.kt`, `gpu/MaliProfile.java`, `DriverArchivePolicy.java` | Paquetes del repo y política de archivos del zip |
| Importación de juegos por ZIP | `frontend/GameImport.kt` (+ test) | Un `.zip` se convierte en carpeta dentro de *Juegos* |
| HUD de CPU | `session/PerfStats.kt` (+ test) | Porcentaje de CPU del HUD, compartido entre pantallas |
| Grupo **Mali** en el diálogo de modo | `ui/ModeSettingsDialog.kt` | Presentación (`DRI3`/sin AFBC/`shm`), Steam UI en GPU y fila de modo seguro, antes de *Steam → Cliente* |
| Panel de drivers | `ui/GpuDriversPanel.kt`, `gpu/DeviceInfo.kt` | GPU safety, Advanced y la línea de diagnóstico |

## 2. Qué se adoptó del oficial

Lo que 0.7.1 traía y el fork no tenía — ahora es el código de base, no un parche:

- **Venus** (`tools/venus/`, `docs/development/mali-venus.md`) y **MaliBridge** (`tools/malibridge`).
- `preload/drm.c` y el *stand-in* de `/dev/mali0` para el par kbase oficial.
- `BL_OPEN_DRIVER`, `BL_VKBRIDGE`, `BL_STEAM_SOFT_COMPOSITING`.
- FEX `0004`, gamescope `p23`, EAC offline.
- Todo el resto de 0.7.1: 3 874 tests de unidad, el runtime, las tiendas, las gafas.

## 3. El merge, en números

95 ficheros frente a `0.7.1`, +7 188 / −96 líneas. Por área:

| Área | Ficheros | Líneas |
|---|---|---|
| `app/src/main/java` | 33 | ~1 500 |
| `app/src/main/res` | 4 | ~350 |
| `tools/linuxfs` (scripts del invitado) | 8 | ~220 |
| `.github/workflows`, `tools/mali-driver`, `tools/*` | ~12 | ~200 |
| `docs/` (informes y capturas) | 13 | — |

## 4. Lo que costó: seis tandas de CI

La primera compilación completa del merge encontró, una tanda por ejecución, todo esto — cada
uno corregido en su propio commit:

| Síntoma | Causa | Arreglo |
|---|---|---|
| `Overload resolution ambiguity` en `DriverMenus`, `FrontEndActivity`, `SessionActivity` | `DeviceSupport.kt` declaraba **dos** `fun mali()` tras el merge | Se queda la oficial (`isMali`) |
| `duplicate method compositorFault()` en javac | `WaylandCompositor.java` tenía la pareja oficial y una copia idéntica del fork | Se queda la primera |
| `Unresolved reference` en `ControllerCustoms.kt` | Los ficheros de entrada muertos volvieron como *untracked* y un `git add -A` los re-subió | Borrados del árbol (0.7.1 ya los sustituyó) |
| `non-static method mali() cannot be referenced from a static context` | `TurnipDriver.java` llamaba a la versión del fork, que era `@JvmStatic` | Llama por `INSTANCE`, como sus vecinos |
| `Unresolved reference 'healIncompatiblePackages'` | `ForcedPackageTest` llevaba dos tests de máquinas que no sobrevivieron al merge | Fichero devuelto al de 0.7.1 |
| 4 tests rojos | `VKD3D_FEATURE_LEVEL`/`SHADER_MODEL` en los defaults; gradiente púrpura a 3,3:1; fallback de tema | Ver §5 |

## 5. Tres decisiones de comportamiento

1. **Los defaults ya no fuerzan VKD3D.** El fork publicaba `VKD3D_FEATURE_LEVEL=12_2` y
   `VKD3D_SHADER_MODEL=6_9` para todo el mundo; 0.7.1 los deja al perfil del juego y sus tests lo
   exigen. Se conservan los ajustes de memoria y caché del fork (`MESA_SHADER_CACHE_MAX_SIZE`,
   `WINEVMEMMAXSIZE`, los recortes de FEX).
2. **El gradiente púrpura se aclaró** a `#B26BFF → #A855F7`: el anterior terminaba en `#8B2FD6`,
   donde el texto oscuro del botón caía a 3,3:1. Ahora el peor punto es 5,0:1.
3. **El perfil Mali sólo se activa cuando hay con qué dibujar.** `MaliAutoSetup` ponía
   `GpuSafety = MALI` antes de saber si había driver: un primer arranque sin red dejaba la sesión
   sin driver y sin nodo de GPU. Ahora el interruptor espera a un driver instalado, elegido,
   recomendado o descargado.

## 6. Lo que **no** está verificado

- **El teléfono.** Ninguna ronda desde R26 se ha probado en el X6 Pro. El requisito duro —que la
  ruta de GPU no reinicie— sigue apoyado en `RebootGuard` y en el parche fn64, no en una prueba
  nueva.
- **WOWBox64 no sobrevivió.** El fork tenía un componente `wowbox64` que cambiaba
  `libwow64fex.dll`; 0.7.1 gestiona sólo `fex`, `dxvk` y `vkd3d`, y el merge dejó el componente
  fuera. El menú ya no lo busca (lo hacía en cada refresco). Recuperarlo es trabajo aparte.
- **lavapipe**: la ejecución `43762897791` falló en el paso 12 y no se ha vuelto a mirar.
- **`components-runner`**: el paso «Build the add-on packs» falló una vez por red (19 s, descarga
  cortada) y pasó en las dos ejecuciones siguientes sin tocar nada.

## 7. Instalar

```bash
adb install -r DroidDeck-Mali-G615-debug.apk   # versionCode 450161
```

Instala sobre el build publicado (mismo `applicationId` y mismo certificado) y conserva el runtime
y Steam. En un Mali sin driver: *Componentes → Controladores de GPU → Avanzado → Driver del
runtime → Importar* para lavapipe, o dejar que `MaliAutoSetup` traiga el PanVK del repo en el
primer arranque con red.

## 8. Siguiente

1. Probar el APK en el X6 Pro: arranque, sesión de Steam, un juego nativo y uno con Venus.
2. Si no hay reinicios, publicar la **estable** (la etiqueta la crea `release.yml` con el APK de
   este build).
3. Recuperar WOWBox64 sobre el `ComponentsManager` de 0.7.1 si se quiere de vuelta.
