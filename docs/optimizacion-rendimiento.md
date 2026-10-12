# Cómo exprimir DroidDeck en un Poco X6 Pro (modo software)

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

## 2. El HUD nuevo (build 37)

Arriba a la derecha, una línea por cosa medida. Antes sólo decía los fps, que en un teléfono es el
número que menos explica: **una sesión no muere por falta de fotogramas, muere por falta de
memoria**, y una sesión que se ralentiza suele estar caliente.

```
27 fps · 37 ms
RAM 2.1 GB libres de 7.1 GB · mata a 486 MB
ClickerHeroes.exe [4521] 1.4 GB
CPU 74% · 2.10 GHz · 8 núcleos · térmico: alto
GPU llvmpipe (Mesa 25.0.7) · lavapipe 25.0.7
960x720 · 120 Hz · tope 30 fps
```

- **`27 fps · 37 ms`** - el intervalo entre fotogramas dice más que la tasa: 30 fps con 33 ms es una
  sesión sana; 30 fps con 90 ms es una que va a trompicones.
- **`RAM … · mata a 486 MB`** - el umbral es el punto en el que Android empieza a matar procesos.
  Libre **por encima** del umbral = sesión que sobrevive; por debajo = sesión cazada, y lo que
  muere es lo que Android quiera, no necesariamente el juego.
- **`ClickerHeroes.exe [4521] 1.4 GB`** - la memoria residente del programa cuyos fotogramas están
  en pantalla. Es el número que ha decidido todas las sesiones hasta ahora.
- **`CPU 74% · 2.10 GHz …`** - porcentaje de todos los núcleos (no la suma), la frecuencia del
  núcleo más rápido y, cuando el dispositivo ya está bajando reloj, el estado térmico de Android.
- **`GPU … · lavapipe …`** - con qué dibuja la sesión y qué controlador Linux lleva puesto.
- **`960x720 · 120 Hz · tope 30 fps`** - la salida de la sesión, la tasa del panel y el límite.

Todo esto se lee desde Kotlin (`/proc`, `/sys` y los gestores de Android): sin root, sin JNI nuevo,
un muestreo por segundo. Lo único que el HUD **no** puede decir todavía es el porcentaje de uso de
la GPU: no existe una forma estándar de leerlo en Mali, y leer `/sys/class/kgsl` o el `gpu_busy` de
Mali depende de cada fabricante.

---

## 3. Palancas, en orden de impacto

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

### 3.3 Preajuste de FEX "Ahorro de memoria" (nuevo en el build 37)

Ajustes del modo → **Preajuste de FEX** → *Ahorro de memoria*.

Es el preajuste *Intermedio* con las cachés de traducción recortadas (las que FEX guarda del código
que ya ha traducido): **`FEX_DISABLEL2CACHE=1`** y **`FEX_DYNAMICL1CACHE=1`**, que liberan cientos
de MB en una sesión larga, más los dos umbrales de la heurística que decide cuándo crecer y menguar.
No cambia nada más - mismo TSO, misma precisión -, así que es lo primero que probar cuando una
sesión muere **por memoria** en vez de por velocidad.

El coste es real y conviene saberlo antes de culpar al teléfono: el código que se tiró hay que
traducirlo otra vez, y eso se ve como **tirones**, no como menos fps.

### 3.4 DXVK (build 37)

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
   cuenta: el build 37 escribe `presenting window: …` en el registro cada vez que cambia. Ver
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

1. Instalar el build 37 **sobre** el que ya hay instalado, sin desinstalar: es una actualización
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
- si **CPU** está al 100% con la imagen congelada (hipótesis 2) o baja (hipótesis 1);
- si aparece **`térmico`** antes de que la sesión se degrade;
- del registro: las líneas `presenting window:` de alrededor del momento del congelamiento, y el
  `heartbeat` de los últimos minutos (`available`, `anon`, y los tres procesos mayores).
