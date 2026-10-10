#!/usr/bin/env bash
# Build the Termux patch zip for the GPU-guard work: the changed files as they are at HEAD, the commit
# series for `git am`, and the instructions in the language the work is being reported in.
#
# It lives in the repo because its predecessor did not: a generator sitting in a home directory is a
# file the next sandbox cleanup deletes, and then the deliverable can only be rebuilt by guessing.
#
#   tools/make_patch_zip.sh [base] [out.zip]
#
# base defaults to the commit this branch was cut from; out defaults to ../DroidDeck-GPU-Guard-patch.zip
# (outside the repo on purpose - a zip is not something to commit).
set -euo pipefail
BASE=${1:-61cf66e1}
cd "$(dirname "$0")/.."
REPO=$(git rev-parse --show-toplevel)
# Outside the repo: a zip is a deliverable, not something to commit.
OUT=${2:-"$(dirname "$REPO")/DroidDeck-GPU-Guard-patch.zip"}
HEAD=$(git rev-parse --short HEAD)
BRANCH=$(git rev-parse --abbrev-ref HEAD)
# A temp dir of its own, and an absolute -o for format-patch below. `git -C "$REPO" format-patch -o
# series` resolves that path against the repo rather than against the shell's cwd, which is how 29
# generated .patch files once landed in the tree - and a later `git add -A` committed them.
WORK=$(mktemp -d "${TMPDIR:-/tmp}/droiddeck-patchzip.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/series"
cd "$WORK"

# 1. every changed file, at its real path, so `unzip -o` onto a checkout is a complete update.
git -C "$REPO" diff --name-only "$BASE..HEAD" > files.txt
n=0
while IFS= read -r f; do
  [ -f "$REPO/$f" ] || continue            # deleted in the range: nothing to carry, see the patch
  mkdir -p "$(dirname "$f")"
  cp "$REPO/$f" "$f"
  n=$((n+1))
done < files.txt
git -C "$REPO" diff --name-status "$BASE..HEAD" > CHANGED.txt

# 2. the series, for anyone who would rather have the history than overwrite files.
git -C "$REPO" format-patch "$BASE..HEAD" -o "$WORK/series" > /dev/null
[ -n "$(ls -A "$WORK/series" 2>/dev/null)" ] || { echo "format-patch produced no series in $WORK/series"; exit 1; }
[ -d "$REPO/series" ] && { echo "format-patch wrote into the repo again ($REPO/series); refusing to leave it"; exit 1; }

# 3. the instructions, generated from the tree so the numbers and the list cannot be stale.
OWNER=$(git -C "$REPO" config --get remote.origin.url | sed -E 's#.*github.com[:/]##; s#\.git$##')
{
  echo "# Parche GPU Guard / modo software - $BRANCH @ $HEAD"
  echo
  echo "Basado en $(git -C "$REPO" rev-parse --short "$BASE"). $(wc -l < files.txt) archivos cambiados,"
  echo "$n copiado(s) aqui con su contenido completo, $(ls series | wc -l) commit(s) en \`series/\`."
  echo
  echo "## Poner el parche (Termux)"
  echo
  echo "~~~"
  echo "cd ~/DroidDeck                 # o donde tengas el checkout"
  echo "git fetch --all && git checkout '$BRANCH' 2>/dev/null || true"
  echo "unzip -o ~/downloads/DroidDeck-GPU-Guard-patch.zip -d ."
  echo "git status                     # debe mostrar solo los archivos de CHANGED.txt"
  echo "~~~"
  echo
  echo "O con el historial, si prefieres ver cada commit:"
  echo
  echo "~~~"
  echo "git am series/00*.patch"
  echo "~~~"
  echo
  echo "## Compilar el APK"
  echo
  echo "No hace falta compilar en el telefono. El workflow \`GPU guard branch APK\` corre solo en cada"
  echo "push a \`$BRANCH\` y publica el APK como *release asset* (no como artifact: un artifact se descarga"
  echo "como .zip y Android responde \"hubo un problema al analizar el paquete\"):"
  echo
  echo "~~~"
  echo "https://github.com/$OWNER/releases/download/gpu-guard-latest/DroidDeck-GPU-Guard-debug.apk"
  echo "~~~"
  echo
  echo "Si las notas del release citan el commit \`$HEAD\`, ese APK ya paso por el compilador y por las"
  echo "cuatro suites de gpu-guard-ci.yml: es el mismo codigo que hay en este zip. Si citan otro, el"
  echo "workflow esta en curso y conviene esperar a que termine antes de instalar."
  echo
  echo "## Driver Vulkan de software (sin esto, el modo software no arranca)"
  echo
  echo "El APK no lleva Mesa dentro. En modo software el huésped necesita un driver lavapipe *importado*:"
  echo "la app lo descomprime en \`files/linux_vulkan_drivers/<id>/\` con su \`icd.json\`, el script de sesion"
  echo "lee esa ruta (\`BL_VK_DRIVER\`), compara la glibc del rootfs con la que exige el paquete (hoy 2.38),"
  echo "y solo entonces exporta el driver. **No hay ruta de fichero alternativa**: descomprimir el zip a mano"
  echo "en \`~/drivers/\` no lo hace visible para la app, y sin importacion la sesion se niega diciendo que"
  echo "falta el driver en vez de caer a la GPU."
  echo
  echo "~~~"
  echo "cd ~/downloads"
  echo "curl -LO https://github.com/$OWNER/releases/download/software-driver-latest/DroidDeck-Software-Vulkan-lavapipe.zip"
  echo "curl -LO https://github.com/$OWNER/releases/download/software-driver-latest/sha256.txt"
  echo "sha256sum -c sha256.txt"
  echo "getconf GNU_LIBC_VERSION    # >= 2.38; con 2.36 (Debian 12) la sesion responde DD_SOFTWARE_GLIBC_OLD"
  echo "~~~"
  echo
  echo "Y en la app: Ajustes -> Components -> drivers -> importar el zip. Despues, el perfil debe decir"
  echo "*Automatico (Vulkan software)*; *Vulkan Mali (experimental)* sigue en rojo y bloqueado sin importacion."
  echo
  echo "## Que trae este parche"
  echo
  git -C "$REPO" log --reverse --format=' - %s' "$BASE..HEAD"
  echo
  echo "## Que NO esta verificado"
  echo
  echo "El trabajo de esta maquina (sin JDK, sin SDK, sin movil) fue escribir y ejecutar los verificadores"
  echo "de shell y las puertas del empaquetado; el APK se compila en el runner. Que este commit este verde en"
  echo "\`GPU guard branch APK\` es comprobable arriba, en las notas del release:"
  echo
  echo "~~~"
  echo "bash tools/test/run_probe_tests.sh              # el probe, contra un loader falso"
  echo "bash drivers/mali-g615/test/run_adapter_tests.sh # el adapter y su traza"
  echo "bash tools/test/run_software_session_tests.sh    # la rama de software del script de sesion"
  echo "bash tools/lavapipe/test_lavapipe_tools.sh       # las puertas del empaquetado lavapipe"
  echo "bash drivers/mali-g615/build.sh NATIVE=1         # el adapter"
  echo "~~~"
} > COMO-PONER_EL-PARCHE.md

# 4. the zip, with the file list kept next to it so an unexpected `git status` is checkable.
rm -f "$OUT"
zip -q -r "$OUT" . -x 'files.txt'
echo "== $(basename "$OUT"): $(du -h "$OUT" | cut -f1), $(unzip -l "$OUT" | tail -1 | awk '{print $2}') entradas"
unzip -t "$OUT" > /dev/null && echo "integridad: OK"
echo "ruta: $OUT"
