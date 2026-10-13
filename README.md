# DroidDeck Mali (fork de jdmg28)

Este repositorio es un fork experimental de DroidDeck adaptado para dispositivos con GPU Mali.

Aviso rápido
-----------
Esta bifurcación contiene cambios experimentales que permiten usar controladores PanVK/Mali y un APK experimental para probar Steam Big Picture en dispositivos ARM con GPU Mali. No es una versión oficial; úsala bajo tu propia responsabilidad.

Qué hace este fork
------------------
- Incluye instrucciones y arte para probar una build experimental del APK de DroidDeck preparada para dispositivos con GPU Mali.
- Acompaña builds de controladores PanVK (drivers Vulkan para Mali) en artefactos de GitHub Actions (ver enlaces abajo).
- Facilita la instalación manual del APK de prueba y la sustitución de componentes gráficos para validar rendimiento y compatibilidad.

Principales diferencias con el proyecto original
------------------------------------------------
- Enfoque en compatibilidad con GPU Mali (por defecto el proyecto original está orientado a Adreno).
- Paquetes experimentales de PanVK (drivers Vulkan para Mali) incluidos como artefactos de CI en las ejecuciones referenciadas.
- Documentación y pasos de instalación específicos para extraer y usar el APK y los drivers en dispositivos ARM con Mali.

Archivos y artefactos relevantes
--------------------------------
He dejado referencias a los artefactos generados por las ejecuciones de GitHub Actions en estas URL (descarga manual recomendada):

- APK experimental: https://github.com/jdmg28/DroidDeck-Mali/actions/runs/38321529660
- Drivers (2 ZIPs con PanVK / kbase): https://github.com/jdmg28/DroidDeck-Mali/actions/runs/38319926336

Nota: desde esta sesión no pude descargar ni descomprimir esos artefactos automáticamente. Más abajo explico cómo hacerlo localmente o con la CLI de GitHub.

Instalación rápida (resumen)
----------------------------
1. Descargar el APK experimental y los zips de drivers de las ejecuciones de Actions (instrucciones detalladas más abajo).
2. Copia el APK al dispositivo Android (o úsalo con ADB):
   - adb install -r path/to/DroidDeck-experimental.apk
3. Extrae los zips de drivers y sigue las instrucciones de empaquetado/instalación que vengan dentro (normalmente consisten en copiar archivos a /vendor/lib*/ o en instalar paquetes específicos para la imagen del sistema). Esto es intrínsecamente riesgoso: haz copia de seguridad.
4. Reinicia el dispositivo y abre DroidDeck. En Configuración > Componentes (o el apartado equivalente) selecciona el controlador Mali (experimental) y el runtime de PanVK si aplica.

Cómo descargar y extraer los artefactos (opciones)
-------------------------------------------------
Opción A — Usando la GitHub CLI (recomendada si la tienes instalada):

1) Instala GitHub CLI: https://cli.github.com/
2) Autentícate: gh auth login
3) Descarga los artefactos de cada ejecución:

   gh run download 38321529660 --repo jdmg28/DroidDeck-Mali --dir artifacts/run-38321529660
   gh run download 38319926336 --repo jdmg28/DroidDeck-Mali --dir artifacts/run-38319926336

4) Una vez descargado, descomprime (si son .zip):

   unzip artifacts/run-38321529660/*.zip -d artifacts/run-38321529660
   unzip artifacts/run-38319926336/*.zip -d artifacts/run-38319926336

Opción B — Usando la API de GitHub con curl (requiere un token personal con scope repo):

1) Lista los artefactos y obtén el campo `archive_download_url`:

   curl -H "Authorization: token $GITHUB_TOKEN" \
     -s "https://api.github.com/repos/jdmg28/DroidDeck-Mali/actions/runs/38321529660/artifacts" | jq '.'

2) Descarga el artefacto (reemplaza la URL por la que obtuviste en el campo `archive_download_url`):

   curl -L -H "Authorization: token $GITHUB_TOKEN" -o artifact-38321529660.zip "ARCHIVE_DOWNLOAD_URL"

3) Descomprime:

   unzip artifact-38321529660.zip -d artifact-38321529660

Opción C — Desde la interfaz web de Actions:

1) Abre las URL de run que proporcioné arriba en tu navegador (debes iniciar sesión en GitHub).
2) En la página de la ejecución, baja hasta la sección "Artifacts" y descarga cada archivo.
3) Descomprime localmente.

Crear un release y adjuntar archivos
-----------------------------------
Desde aquí no puedo crear releases en tu cuenta directamente. Puedes crear uno localmente con la GitHub CLI o desde la interfaz web. Ejemplos:

Con GitHub CLI:

  gh release create v0.1.0-mali-experimental \
    --repo jdmg28/DroidDeck-Mali \
    --title "DroidDeck Mali - experimental APK + drivers" \
    --notes "Release experimental: APK y drivers PanVK para pruebas en GPUs Mali. Usa bajo tu responsabilidad." \
    path/to/DroidDeck-experimental.apk path/to/driver-panvk.zip path/to/driver-kbase.zip

Con la web UI:

1) Ve a https://github.com/jdmg28/DroidDeck-Mali/releases
2) Click en "Draft a new release"
3) Rellena tag/version y título, sube los binarios (APK y zips) y publica.

Sobre la versión/etiqueta del release
------------------------------------
Necesito que me indiques la versión/tags que quieres usar para el release (por ejemplo: v0.1.0-mali-experimental) y si deseas que el changelog se genere automáticamente. Si me das las rutas a los archivos binarios (si me subes los APK/ZIP aquí), puedo preparar el texto del release y el changelog listo para pegar.

Imágenes para el README
-----------------------
Has pedido que agregue las imágenes (1,2,3,4). Como los archivos de imagen no están disponibles por API desde esta sesión, he dejado instrucciones y marcadores dentro del README para que se añadan en la ruta docs/media/experimental/:

- docs/media/experimental/shot1.png  (Imagen 1)
- docs/media/experimental/shot2.png  (Imagen 2)
- docs/media/experimental/shot3.png  (Imagen 3)
- docs/media/experimental/shot4.png  (Imagen 4)

Si subes esas cuatro imágenes al repositorio en la ruta anterior o me las das aquí, actualizaré el README para incrustarlas directamente.

Créditos y licencia
-------------------
- Créditos: Este fork mantiene y reconoce el trabajo del creador de la app oficial DroidDeck y del desarrollador del driver PanVK. Gracias a ellos por la base sobre la que se ha probado este trabajo.
- Licencia: GPL-3.0 (como el proyecto original). Consulta la licencia en el repositorio para detalles.

Qué he hecho ahora
-------------------
He reemplazado el contenido de README.md en la rama por defecto del repositorio jdmg28/DroidDeck-Mali con este nuevo README específico para tu fork, que explica qué es este fork, en qué se diferencia del original, dónde están los artefactos de CI, cómo descargarlos y cómo crear un release.

Limitaciones y siguiente paso
-----------------------------
- No pude descargar ni descomprimir los artefactos de Actions desde aquí (la API de Actions no está expuesta a esta sesión de herramientas). Tampoco puedo crear el release desde esta herramienta.
- Para que cree y publique el release por ti necesitaría que subieras aquí los archivos APK y ZIP (adjúntalos en el chat), o que me des permiso/credenciales para operar en GitHub en tu nombre (no recomendable por razones de seguridad). La alternativa más segura es que descargues los artefactos en tu máquina con las instrucciones que te di y crees el release con la GitHub CLI o la web.

¿Quieres que:
1) Suba las imágenes al repo por ti si me las adjuntas aquí?
2) Prepare el texto del release (changelog, notas) listo para pegar si me indicas la versión que quieres usar?
3) Te doy comandos adicionales para un sistema concreto si necesitas (Linux/macOS/Windows)?

Si me adjuntas los cuatro capturas y/o los archivos APK/ZIP, los subiré y actualizaré el README para incrustar las imágenes, y prepararé un borrador de release listo para publicar.
