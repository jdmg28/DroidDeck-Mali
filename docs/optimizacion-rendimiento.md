# Cómo exprimir DroidDeck en un Poco X6 Pro (software y GPU Mali)

Escrito a partir de lo que los registros de sesión **midieron** en este teléfono, no de lo que suele
funcionar en un PC. Cada número de aquí viene de un `heartbeat` o de un `dmesg` real de este
dispositivo, salvo los marcados como hipótesis.

Dispositivo: Poco X6 Pro · Mali-G615 (MediaTek) · Kbase r44p1 · `/dev/mali0` · 7.1 GB de RAM ·
pantalla de 120 Hz · HyperOS. Ruta de render: **lavapipe** (Mesa 25.0.7) sobre CPU, sin tocar la
GPU real - el modo experimental congeló y reinició el teléfono una vez y no se vuelve a usar.

---

## 1. Lo que ya está medido

### Memoria

| | |
|---|---|
| RAM total | 7.1 GB |
| Libre al arrancar la sesión | 3.2 GB |
| app + compositor | ~400 MB |
| Xwayland | 268 MB |
| cliente Steam | 344 MB (después de un pico de ~1.5 GB durante el arranque) |
| steamwebhelper | 412 MB |
| **Antes de que el juego exista** | **≈ 1.6 GB** |
| Juego ligero de Unity | pico 1.77 GB → sobrevive |
| Clicker Heroes | pico 2.46 GB → **muere con 408 MB libres** |

### Las tres cosas que esos números dicen

1. **El pico de 1.5 GB del arranque de Steam son los compiladores de shaders de Mesa**, no el
   cliente. En la sesión 40 apareció como 2 líneas en 35 segundos de registro de Proton, sin carga
   de DLL, sin error y con `load 0.00` - nada en la pila asigna así sin decir nada, salvo 8
   compiladores de ~190 MB cada uno. De ahí `dxvk.numCompilerThreads=2` (build 34).
2. **No es una fuga de memoria de usuario.** `AnonPages` *baja* (541 → 301 MB en la sesión 39,
   407 → 111 MB en la 40) mientras `available` se derrumba y la RSS del juego sube 1.5 GB. Lo que
   crece está en búferes que el proceso retiene (dma-buf / VRAM contabilizada al proceso), no en un
   montón que se escape. Por eso la pregunta útil no es "qué proceso pierde memoria" sino
   **"por qué los búferes se retienen y no se liberan"**.
3. **El "load average" es inútil aquí**: todas las sesiones dicen `load 0.00,0.00,0.00` mientras los
   fotogramas se detienen. El `cpu %` del build 34 se calcula de `/proc/stat`, no de `/proc/loadavg`.

---

## 2. El HUD (build 38)

Cuatro líneas cortas, arriba a la derecha. Se leen mientras se juega, encima de la imagen: un HUD
que tapa la pantalla es un HUD que se acaba apagando. Lo que dice es lo que decide si una sesión
vive - antes sólo decía los fps, que en un teléfono es el número que menos explica: **una sesión no
muere por falta de fotogramas, muere por falta de memoria**.

```
27 fps · 37 ms
CPU 74% · GPU 61% · 2.10 GHz · térmico: alto
RAM 900 MB de 7.1 GB · mata a 486 MB
juego 1.4 GB · 960x720 · 120 Hz
```

- **`27 fps · 37 ms`** - el intervalo entre fotogramas dice más que la tasa: 30 fps con 33 ms es una
  sesión sana; 30 fps con 90 ms es una que va a trompicones.
- **`CPU 74% · GPU 61% · 2.10 GHz`** - el porcentaje de **toda** la cpu (no la suma de los núcleos),
  el reloj del núcleo más rápido y, sólo cuando el dispositivo ya está bajando frecuencia, el estado
  térmico.
- **`RAM 900 MB de 7.1 GB · mata a 486 MB`** - el umbral es el punto en el que Android empieza a
  matar procesos para recuperar memoria. Libre **por encima** del umbral = sesión que sobrevive; por
  debajo = sesión cazada, y lo que muere es lo que Android quiera, no necesariamente el juego.
- **`juego 1.4 GB`** - la memoria residente del programa cuyos fotogramas están en pantalla. Es el
  número que ha decidido todas las sesiones hasta ahora.

### Qué es exactamente el `GPU %`

Depende de con qué se dibuje:

- **Con un controlador real** (el camino del Mali, hoy aparcado): el contador que el propio
  controlador publica en `/sys` - el de kgsl en Qualcomm, el de utilización en los Mali que lo
  exponen. No hay un estándar, así que se prueban los nodos conocidos y, si ninguno contesta, **no
  se muestra nada**: una GPU a la que no se puede preguntar no es una GPU al 0%.
- **En software (lavapipe, lo que usas hoy)**: no hay GPU a la que preguntar, porque lavapipe *es*
  la cpu. El porcentaje que ves es **la parte de la cpu que se está usando el proceso del juego**,
  que es donde viven FEX, DXVK y el rasterizado. Por eso sale siempre por debajo del `CPU %`: está
  incluido en él, no sumado.

Lo que dibuja y con qué controlador ya no se muestra en pantalla: se escribe una vez en el registro
de la sesión (`gpu: …`).

Todo esto se lee desde Kotlin (`/proc`, `/sys` y los gestores de Android): sin root, sin JNI nuevo,
un muestreo por segundo. Lo único que el HUD **no** puede decir todavía es el porcentaje de uso de
la GPU: no existe una forma estándar de leerlo en Mali, y leer `/sys/class/kgsl` o el `gpu_busy` de
Mali depende de cada fabricante.

---

## 3. Palancas, en orden de impacto

### 3.0 La memoria del cliente de Steam (lo más grande que hay, y no hace falta build)

El cliente es, con diferencia, lo que más memoria se lleva antes de que el juego exista, y lo que
explica el número que viste: **1,9-2 GB libres al entrar al juego, 900 MB de golpe nada más
arrancarlo**. No es una suposición: hay un informe de Linux de exactamente esto - «al arrancar un
juego, el proceso steamwebhelper llena la RAM en segundos» - y la respuesta en todos los sitios es
la misma. La tienda, la biblioteca y el chat son **un proceso renderizador de Chromium cada uno**, y
Steam los mantiene vivos mientras juegas.

Esto se cambia dentro del propio Steam (se queda guardado en el runtime, no hay que repetirlo):

1. **Steam → Ajustes → Interfaz → desmarcar «Activar la representación acelerada por GPU en vistas
   web»** y reiniciar Steam. Es el más grande con diferencia: hay informes de 1 a 2 GB recuperados,
   y en Linux tiene un mecanismo conocido - CEF no tiene `GpuMemoryBuffers` nativos en Linux, así
   que cada fotograma de una vista web hace una lectura de vuelta desde la GPU, y eso con un juego
   funcionando es justo cuando la GPU está ocupada ([ValveSoftware/steam-for-linux #13513](
   https://github.com/ValveSoftware/steam-for-linux/issues/13513)). **Es también un candidato serio
   para la imagen congelada.**
2. **Ajustes → Biblioteca**: activar *Modo de bajo ancho de banda*, *Modo de bajo rendimiento* y
   *Desactivar contenido de la comunidad*. Entre 100 y 360 MB según el medidor de turno, y no cuesta
   nada.
3. **Ajustes → Amigos y chat**: desmarcar los avatares animados. **Ajustes → En el juego**:
   desmarcar la superposición de Steam.
4. **Ajustes → Precaché de sombreadores → desactivado**. Al arrancar un juego, Steam compila
   sombreadores en segundo plano, y ésos son los compiladores de Mesa de los que se habla en el
   punto 1 de más arriba. Desactivarlo quita el pico; se paga con tirones la primera vez que sale
   cada efecto.
5. **No dejar la tienda abierta**: cada pestaña de la tienda es un renderizador más. La biblioteca
   gasta bastante menos.

Y dos mecanismos en la sesión, los dos **desactivados por defecto** porque los dos tienen letra
pequeña:

- **`BL_STEAM_ARGS`** - argumentos que se añaden al cliente al arrancarlo. `-cef-single-process`
  mete todo el navegador en un solo proceso: es el mayor recorte disponible del lado del cliente, y
  el que más fácilmente puede hacer que la tienda vuelva en blanco.
- **`BL_MEM_VALVE_MB=700`** - con la memoria libre por debajo de esa cifra, la sesión cierra por sí
  misma el renderizador del navegador más grande. Steam levanta uno nuevo y pequeño en cuanto lo
  necesita; lo que se muere si no es el juego. Sólo toca al navegador: nunca al cliente ni al juego.

### 3.1 Límite de fotogramas = 30 (lo primero que hay que probar)

Ajustes del modo (el engranaje de Steam o Escritorio) → **Límite de fotogramas** → 30.

Es la palanca más grande y ya estaba en la app. Hace cuatro cosas a la vez:

- le pasa `-r 30` a gamescope, que es la tasa que se le **ofrece** al juego (sin `-r`, gamescope
  anuncia 60; y un panel de 120 Hz sólo ofrece 60 en los ajustes del juego si no se dice nada);
- el compositor deja de trabajar a 120 Hz para presentar 30 fotogramas - en lavapipe, cada
  composición es tiempo de CPU que se le quita al juego;
- la capa de Android se pide a 30 Hz en vez de 120 Hz;
- `PerfMode` cambia el panel a una tasa de refresco que el límite divida exactamente.

Todo eso es CPU que vuelve al juego y memoria que no se toca. **Hipótesis** (no verificado): parte
del problema de la imagen congelada puede ser un compositor que se queda con la CPU que el juego
necesita para presentar el siguiente fotograma.

### 3.2 Resolución

La misma pantalla de ajustes. El coste en lavapipe es por píxel: bajar de 960x720 a 800x600 quita
un 31% de los píxeles, y a 640x480 un 56%. Es el cambio que más fps da por cada otra cosa que se
estropea (y la que más se nota en la nitidez, claro).

### 3.3 Preajuste de FEX "Ahorro de memoria" (nuevo en el build 38)

Ajustes del modo → **Preajuste de FEX** → *Ahorro de memoria*.

Es el preajuste *Intermedio* con las cachés de traducción recortadas (las que FEX guarda del código
que ya ha traducido): **`FEX_DISABLEL2CACHE=1`** y **`FEX_DYNAMICL1CACHE=1`**, que liberan cientos
de MB en una sesión larga, más los dos umbrales de la heurística que decide cuándo crecer y menguar.
No cambia nada más - mismo TSO, misma precisión -, así que es lo primero que probar cuando una
sesión muere **por memoria** en vez de por velocidad.

El coste es real y conviene saberlo antes de culpar al teléfono: el código que se tiró hay que
traducirlo otra vez, y eso se ve como **tirones**, no como menos fps.

### 3.4 DXVK (build 38)

El contenedor de compatibilidad prepara esto para cada lanzamiento de Proton:

```
dxvk.numCompilerThreads=2          # 8 compiladores ≅ 1.5 GB; 2 es un pico que el teléfono aguanta
dxgi.maxDeviceMemory=2048          # lo que se le dice que tiene la "tarjeta"
dxgi.maxSharedMemory=3072          # y lo que comparte con el sistema
```

En un teléfono no hay tarjeta: el controlador reporta la memoria del dispositivo, y un juego que
dimensiona sus piscinas de texturas con ese número pide gigabytes sin pensarlo. Decirle "2 GB
dedicados y 3 GB compartidos" es una tarjeta media normal para cualquier juego, y presupuesta en
consecuencia. **Ojo: son números que se reportan, no topes** - el propio `dxvk.conf` de DXVK dice
que un juego puede ignorarlos -, así que no frenan a un juego que insiste, pero son la única palanca
que hay sobre ese pico.

Añadir cosas sin tocar el resto: en **Entorno del juego** (o en las opciones de lanzamiento de Steam
del juego) poner `BL_DXVK_CONFIG=d3d11.disableMsaa=True`. Ésa es la que vale la pena en software:
baja todas las texturas a una sola muestra, que es donde se va una parte importante del tiempo y la
memoria de lavapipe, a cambio de perder el antialias por completo.

Comprobado en el código de DXVK: las opciones de `DXVK_CONFIG` se separan con **`;`**
(`Config::getUserConfig` parte la variable por `;` y analiza cada trozo como una línea de
`dxvk.conf`). Si un juego define `DXVK_CONFIG` él mismo en sus opciones de lanzamiento, gana y todo
lo de arriba se queda fuera.

Otras opciones reales de DXVK que existen y que conviene tener a mano (todas **opt-in**, ninguna
activada): `dxgi.maxFrameRate` (limita presentaciones; ahorra CPU pero añade latencia y a algunos
juegos no les sienta bien), `dxgi.maxFrameLatency`, `dxvk.tearFree`, `d3d11.relaxedBarriers`
(mejora el rendimiento y **puede** romper el dibujado), `dxvk.enableGraphicsPipelineLibrary`
(`Auto` / `True` / `False`; con `Auto` es automático cuando el controlador trae la extensión, así
que no hay nada que ganar).

### 3.5 HyperOS: apagar la "extensión de memoria"

Ajustes del sistema → memoria / RAM → **extensión de memoria** → apagada. Es swap sobre el
almacenamiento: convierte un pico de memoria en un pico de E/S, y es lo último que quiere una
sesión que ya va justa de las dos cosas. **Hipótesis**: contribuye a que el final de sesión se vea
como un bloqueo largo y no como un cierre.

### 3.6 Lo que NO conviene hacer

- **`-no-browser` en Steam**: ahorra los más de 400 MB del `steamwebhelper`, pero deja la interfaz
  sin navegador, y la interfaz es justo lo que se usa para navegar. Sólo como último recurso.
- **`FEX` sin TSO** o `FEX_SMCCHECKS=none` para "ir más rápido": no dan fps, y rompen juegos con
  DRM o con JIT. El preajuste *Extremo* ya los trae y sigue siendo la última opción, no la primera.
- **Tocar `/dev/mali0`**: congela y reinicia el teléfono. El modo software no lo abre nunca.
- **VKD3D / DX12**: imposible en Mali-G615 en este estado.

---

## 4. La imagen congelada

Lo que se ve: el juego entra, **suena**, el menú **recibe el tacto**, pero la imagen no cambia
nunca. Es decir: el proceso vive, el bucle de eventos vive, el audio vive, y lo único que no llega
es un fotograma nuevo al compositor.

Ordenadas por lo que explicarían, de más a menos probable. **Ninguna está verificada** - no tengo
acceso al teléfono, y distinguirlas necesita el registro de una sesión en la que pase.

1. **El juego deja de entregar búferes al compositor.** Con `dxgi.syncInterval=1` (la mayoría de
   juegos Unity en pantalla completa) un `Present` que no vuelve deja la última imagen en pantalla
   para siempre, y como el bucle de eventos va en otro hilo el juego sigue vivo. Encaja con el
   síntoma al milímetro. Lo delata que el HUD siga contando fotogramas del compositor (que sí
   presenta, la imagen vieja) mientras la tasa cae a 0.
2. **El compositor se queda sin CPU para presentar.** En lavapipe, presentar es trabajo de CPU. Si
   el compositor va a la tasa del panel (120 Hz) y el juego a 30, el reparto puede dejar al juego
   sin tiempo para el siguiente fotograma. **Esto es lo que el límite de fotogramas (3.1) debería
   arreglar, y es la razón de probarlo primero.**
3. **Steam pone algo delante.** Si la ventana que presenta deja de ser la del juego, el HUD lo
   cuenta: el build 38 escribe `presenting window: …` en el registro cada vez que cambia. Ver
   `game` → otra cosa justo cuando la imagen se congela sería la prueba.
4. **gamescope y su reenvío directo de búferes.** gamescope manda los búferes de un juego a pantalla
   completa directamente y compone cuando el menú de Steam está encima. Un juego que el reenvío no
   reconoce como "a pantalla completa" se queda en la ruta compuesta. Prueba: abrir y cerrar el menú
   de Steam dentro del juego - si la imagen **vuelve** al abrirlo y se congela al cerrarlo, es esto.
5. **El hilo de presentación de Unity.** En Unity, `-force-gfx-direct` obliga a dibujar en el hilo
   principal; hay registros de "suena la música y la imagen se congela" resueltos con eso, con una
   resolución forzada (`-screen-width` / `-screen-height`) o moviendo la ventana de ventana a
   pantalla completa. Se prueba desde las opciones de lanzamiento del juego en Steam, sin reinstalar
   nada.

### Cómo distinguirlas en cinco minutos

1. Límite de fotogramas a 30. ¿Se congela igual? (prueba 2)
2. Con la imagen congelada, **abrir el menú de Steam**: si al abrirlo se ve el juego de fondo
   moviéndose, es la 4; si todo sigue helado, es la 1, la 2 o la 5.
3. **Salir y volver a entrar** con Alt+Tab / el gesto de Android: si al volver se ve un fotograma
   nuevo y luego se vuelve a congelar, es la 1.
4. Opciones de lanzamiento del juego: `-force-gfx-direct -screen-width 960 -screen-height 720`
   (Unity). Si deja de congelarse, es la 5.

---

## 5. Qué probar y qué pegar

Orden: una cosa cada vez, y anotar qué se cambió. Si se puede, con el HUD activado todo el rato.

1. Instalar el build 38 **sobre** el que ya hay instalado, sin desinstalar: es una actualización
   (mismo paquete, mismo firmante y versión superior, comprobado en el CI antes de publicarlo), así
   que los ~5 GB de runtime y de Steam se quedan donde están.
2. Ajustes del modo → Límite de fotogramas → **30**; jugar 5 minutos.
3. Mismo juego, **sin límite**, otros 5 minutos.
4. Si la sesión muere o se congela: compartir el registro de la sesión (el ZIP de la sesión, no una
   captura) y decir en qué minuto pasó y qué se estaba haciendo.
5. Con el HUD a la vista, pegar las seis líneas en tres momentos: al entrar al juego, a los 2
   minutos y justo antes de que se congele o muera.

Lo que más quiero ver de cada pegada:

- la línea **RAM** (libre y umbral) y la del **juego** (cuánto retiene) - juntas dicen si la sesión
  murió por memoria o por otra cosa;
- si **CPU** está al 100% con la imagen congelada (hipótesis 2) o baja (hipótesis 1), y qué hace
  **GPU** - en software es la cpu del propio juego, así que GPU muy alta con CPU alta es el juego
  comiéndose el dispositivo;
- si aparece **`térmico`** antes de que la sesión se degrade;
- del registro: el `heartbeat` de los últimos minutos, que desde el build 38 dice **`tienda N MB en
  M`** (cuánto se lleva el navegador del cliente y en cuántos procesos) y, por debajo de 1 GB libres,
  una línea **`== memory low`** que nombra los ocho procesos mayores con sus argumentos - con eso se
  puede distinguir la memoria del juego de la del navegador, la de FEX y la de los compiladores;
- las líneas `presenting window:` de alrededor del momento del congelamiento.

**Cómo compartirlo**: lo ideal es la **carpeta de la sesión tal cual** (la que la app deja en Drive,
con `session.log`, `device.txt`, `wayland.log` y la subcarpeta `steam/`): puedo recorrerla y leer
los ficheros sueltos, y fue así como se leyó el registro del build 40. Un ZIP comprimido **no**
puedo abrirlo. Si no queda otra, pega en el chat las últimas 60-80 líneas del `heartbeat`, las de
`== fds:` y las de `== memory low`.


## 6. Lo que dijo el primer registro con la GPU real (build 40)

El 12 de octubre llegó el primer `session.log` de una sesión con el controlador PanVK-kbase, es decir
con la GPU de verdad. Dos conclusiones, una buena y una que lo explica todo.

### La GPU funciona

- DXVK: `Found device: Mali-G615 MC6 (panvk 26.2.99)`.
- El propio Steam la lista como `Mali-G615 MC6`, `driver_id: k_EGpuDriverId_MesaPanVK`, 5,7 GB de
  VRAM.
- gamescope: `selecting physical device 'Mali-G615 MC6'`, y compone con ella.

No es lavapipe: el trabajo de dibujar ya no lo hace la CPU. Ese camino está abierto.

### Pero la sesión se muere a los nueve minutos, y no es por memoria

El registro acaba así:

```
MESA: error: kbase: failed to duplicate dma-buf: Too many open files
MESA: error: zink: couldn't allocate memory: heap=0 size=2818048
== agent: focus snapshot failed: X connection refused on :0
```

**Descriptores de archivo agotados**, no memoria: cuando la sesión se vino abajo todavía quedaban
**1.870 MB libres**. Contando los números que el propio driver imprime (`succeeded for N`, que es el
descriptor que acaba de abrir) se ve la fuga:

| Momento | Descriptor más alto visto |
|---|---|
| 12:26:22 (26 s de sesión) | 1.384 |
| 12:35:11 (9 min 15 s) | 30.935 |
| a partir de ahí | `Too many open files` |

**~56 descriptores por segundo, sin devolver ninguno**, durante toda la sesión. El techo de un
proceso en Android es 32.768, así que 32.768 ÷ 56 ≈ **9,8 minutos de vida**. Calza con el minuto
exacto en que se cayó.

Eso explica las tres cosas que se vieron a la vez:

1. **Los tirones de FPS**: cada vez más asignaciones fallan y se reintentan.
2. **Los juegos no arrancan**: el juego (Clicker Heroes, AppID 363970) se lanzó a las 12:35:01,
   justo cuando el cubo se vaciaba. Sin descriptores no hay memoria de GPU, y sin memoria de GPU no
   hay juego: se queda en «abriendo juego» para siempre.
3. **La interfaz se queda colgada**: Xwayland deja de aceptar conexiones
   (`X connection refused on :0`), y el hilo de la interfaz de Steam se para 6-30 segundos
   (`SteamUI thread frame stalled`).

### Qué se puede hacer mientras no se arregle la fuga

La fuga está dentro del controlador (viene precompilado de
`zenithblue-oss/panvk-kbase-android`; aquí no se compila Mesa), así que de momento se trata de
**gastar menos y de no llegar al techo**:

1. **Lanzar el juego en los primeros dos o tres minutos** de sesión, en cuanto aparezca Steam. Es la
   prueba más importante: si el juego arranca pronto y a los diez minutos ya no, la diagnosis queda
   confirmada en el teléfono.
2. **Bajar el refresco a 60 Hz** (menú rápido de Steam → Rendimiento). Con el limitador a 30 FPS,
   un panel a 120 Hz no da nada y cuesta el doble de trabajo del compositor, que es quien gasta los
   descriptores.
3. **Desactivar la superposición de Steam** (Ajustes → En el juego). En el registro se ve activa
   (`Adding process 11088 for gameID 363970`).
4. **Cerrar la tienda antes de jugar**: sigue siendo ~1 GB en 8 procesos.
5. Para experimentos, en `Download/droiddeck-env`: `BL_XWAYLAND_COUNT=1` (un servidor X en vez de
   dos) y `BL_REFRESH=60`.

### Lo que añade el build 41 para localizarla

- Al arrancar: `== fd limit: soft N hard M`, y sube el límite blando hasta el duro (eso no necesita
  permisos; si Android permite más de 32.768, el problema desaparece solo).
- En el heartbeat, cada 30 s: `== fds: 29412 of 32768 · worst: steamwebhelper [26488]`, que dice
  **quién** se los está llevando y cuánto techo queda.
- Al llegar al 80% del techo: `== fd warning: ...`, con el nombre del proceso culpable.

---

## Diagnóstico de la sesión `2026-10-12-19-steam` (Build 42) y correcciones en Build 43

En la sesión `2026-10-12-19-steam` se verificó que el techo de descriptores quedó resuelto (`fds: 7112 of 32768`), pero aparecieron dos fallos críticos: **reinicios repetidos de la interfaz de Steam** y **cierre inesperado de Clicker Heroes (`363970`) tras renderizar a 25.4 FPS**.

### 1. Por qué se reiniciaba Steam repetidamente (`Triggering shutdown due to GPU process restarts`)

1. **`-cef-use-gl=angle -cef-use-angle=vulkan` anulaba `-cef-disable-gpu`:**
   `droiddeck-session` pasaba `-cef-use-gl=angle -cef-use-angle=vulkan` de forma incondicional antes de añadir `-cef-disable-gpu`, y `steamwebhelper` heredaba `VK_ICD_FILENAMES=/tmp/vulkan-icd.json`. Como resultado, tanto el proceso principal de `steamwebhelper` (395 MB RSS) como `steamwebhelper --type=gpu-process` (537 MB RSS) inicializaron **cuatro instancias de `panvk` (`Mali-G615`)** y reservaron **40 buffers DMA-BUF (`~220 MB` en `kbase`)**.
2. **`web trim` mataba `--type=gpu-process` cada 20 s:**
   Al superar los 500 MB, el recorte periódico (`web trim`) elegía el proceso `steamwebhelper` con mayor RSS —que siempre era `--type=gpu-process`— y le enviaba `SIGTERM`. Tras tres muertes seguidas de `--type=gpu-process`, Chromium/CEF activó su salvaguarda interna (`Triggering shutdown due to GPU process restarts`, `gpu:3`), cerrando y reiniciando toda la interfaz de Steam (`startcount=1, 2`) incluso mientras el juego arrancaba.

**Corrección en Build 43:**
- En `droiddeck-session`, `-cef-use-gl=angle -cef-use-angle=vulkan -cef-force-gpu` **solo** se pasan cuando `BL_CEF_GPU=1`. Por defecto se pasa `-cef-disable-gpu -cef-disable-gpu-compositing`.
- En `libblsession.so` (`tracer.c`), un constructor aísla a `steamwebhelper` de Vulkan/PanVK cuando `BL_CEF_GPU != 1` (`VK_ICD_FILENAMES=/dev/null`, `LIBGL_ALWAYS_SOFTWARE=1`, sin `MESA_LOADER_DRIVER_OVERRIDE` ni `ENABLE_GAMESCOPE_WSI`), ahorrando **~500–700 MB de RAM y DMA-BUFs**.
- El recorte de navegador (`_bl_largest_renderer`) solo considera procesos reemplazables `--type=renderer` y nunca toca el proceso principal ni `--type=gpu-process`.

### 2. Por qué se cerró `Clicker Heroes` (`363970`) tras empezar a renderizar

1. **`steam-launch-wrapper --oom-score-adjust 900` marcaba el juego como app en caché para Android `lmkd`:**
   Steam lanza todos los juegos mediante `steam-launch-wrapper --oom-score-adjust 900`. En el kernel Linux 6.1 de Android, escribir `900` en `/proc/self/oom_score_adj` bloquea permanentemente `oom_score_adj_min = 900`, y el demonio `lmkd` de Android interpreta `oom_score_adj = 900` (`CACHED_APP_MIN_ADJ`) como una app en segundo plano desechable. Cuando `Clicker Heroes.exe` subió de 824 MB a 1.605 MB por asignaciones `ALLOC_EX` de `/dev/mali0` y CEF relanzó `--type=gpu-process`, `lmkd` mató `Clicker Heroes.exe` con `SIGKILL` a las `15:19:28.6` aunque aún quedaban ~573–753 MB disponibles.
2. **Presupuesto de memoria de DXVK (`dxvk.maxMemoryBudget`):**
   En GPUs integradas (`VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU`), DXVK usa `dxvk.maxMemoryBudget` para decidir cuándo purgar bloques vacíos (`freeEmptyChunksInPool`) y desfragmentar (`pickDefragChunk`).

**Corrección en Build 43:**
- En `libblsession.so` (`opens.c`), las escrituras a `/proc/.../oom_score_adj` se redirigen a `/dev/null`, evitando que `steam-launch-wrapper` marque los juegos como `CACHED_APP_MIN_ADJ (900)`.
- `dxvk.maxMemoryBudget`, `dxgi.maxDeviceMemory` y `dxgi.maxSharedMemory` bajan por defecto de `1024` a **`512` MiB**, y se añade `WINEVMEMMAXSIZE=4096`.
- El registro por defecto de Proton (`PROTON_LOG=1`) usa `WINEDEBUG=+timestamp,+pid,+tid,+err` (sin `+seh,+loaddll`, que generó 4.747 líneas de traza en el hilo principal durante el JIT de Mono).

---

## Capas de compatibilidad para juegos `.exe`: FEX, Box64 (`WOWBox64`) y `DroidDeck-Proton`

### Cómo ejecuta ARM64 Proton los juegos `.exe`

Los builds de Proton ARM64 (`Proton - Experimental`, `GE-Proton`, `proton-cachyos`, `DroidDeck-Proton`) usan un Wine `aarch64` nativo sobre glibc que delega la emulación de CPU de cada `.exe` a un módulo PE en el registro del prefijo (`HKLM\Software\Microsoft\Wow64`):

| Tipo de ejecutable (`.exe`) | Registro de Wine (`dlls/ntdll/loader.c` / `dlls/wow64/syscall.c`) | Emulador por defecto | Alternativa disponible en Build 43 |
|---|---|---|---|
| **64 bits (`x86_64` PE32+)** | `HKLM\Software\Microsoft\Wow64\amd64` | `libarm64ecfex.dll` + `.so` (**FEX ARM64EC**) | Paquetes `FexCore-Linux` en **Components (`FEX`)** |
| **32 bits (`x86` / `i386` PE32)** | `HKLM\Software\Microsoft\Wow64\x86` | `libwow64fex.dll` (**FEX WoW64**) | **`WOWBox64`** (`wowbox64.dll`, **Box64 WoW64**) en **Components (`FEX`)** |
| **Binarios Linux (`x86_64` / `i386` ELF)** | `droiddeck-fex` (`FEXInterpreter` / `FEXLoader`) | **FEX-Emu** con rootfs Steam Linux Runtime | Preloads `libblsession.so` y `libfaultreport.so` |

> **Nota sobre `box86`:** Los núcleos ARMv9 del Dimensity 8300-Ultra (Cortex-A715 / Cortex-A510) son exclusivamente de 64 bits (`aarch64`) y no tienen soporte en hardware para ejecutar código ARM de 32 bits (`armhf`), por lo que el `box86` clásico no puede ejecutarse en este SoC. En su lugar, Box64 compila **`WOWBox64` (`wowbox64.dll`)**, una DLL ARM64 de 64 bits que emula juegos Windows `.exe` de 32 bits directamente dentro del subsistema WoW64 de Wine 11.

### Nuevas opciones integradas en Build 43 para pruebas

1. **`DroidDeck-Proton` (`The412Banner/DroidDeck-Proton`):**
   Disponible directamente en el menú **Proton** de la app junto a `GE-Proton` y `proton-cachyos`. Incluye parches específicos de ARM64/Android portados de CachyOS, Bannerlator y GameNative:
   - Soporte para `WINEVMEMMAXSIZE` (límite de memoria virtual de Wine).
   - Comprobación de límites en `RtlIsEcCode` (evita cierres por `SIGSEGV` en juegos con Denuvo/DRM bajo ARM64EC).
   - `MAX_FONT_HANDLES` ampliado a 32.768 (evita fallos de carga de fuentes en motores con miles de llamadas a `AddFontMemResourceEx`).
   - Mapeo de ejecutables `.exe`/`.dll` desde almacenamiento con `noexec` (tarjetas SD y carpetas compartidas de Android) y corrección de vídeo FMV DirectShow.
2. **`WOWBox64` (`Box64-Nightly` en Components → `FEX`):**
   Disponible en el gestor de **Components (`FEX`)** al pulsar actualizar catálogo. Permite sustituir el backend de 32 bits (`libwow64fex.dll`) de cualquier Proton por **`WOWBox64-*.wcp` (`wowbox64.dll`)**, manteniendo `libarm64ecfex.dll` intacto para los juegos de 64 bits. Se aceptan variables `BOX64_*` (por ejemplo `BOX64_DYNAREC_BIGBLOCK`, `BOX64_DYNAREC_SAFEFLAGS`, `BOX64_DYNAREC_STRONGMEM`) en el entorno del juego.

