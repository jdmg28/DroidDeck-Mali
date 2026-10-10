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
mkdir -p /tmp/patchzip
rm -rf /tmp/patchzip && mkdir -p /tmp/patchzip/series
cd /tmp/patchzip

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
git -C "$REPO" format-patch "$BASE..HEAD" -o series > /dev/null

# 3. the instructions, generated from the tree so the numbers and the list cannot be stale.
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
  echo "https://github.com/$(git -C "$REPO" config --get remote.origin.url | sed -E 's#.*github.com[:/]##; s#\.git$##')/releases/download/gpu-guard-latest/DroidDeck-GPU-Guard-debug.apk"
  echo "~~~"
  echo
  echo "## Que trae este parche"
  echo
  git -C "$REPO" log --reverse --format=' - %s' "$BASE..HEAD"
  echo
  echo "## Que NO esta verificado"
  echo
  echo "Nada de esto se compilo en la maquina que escribio el codigo (no hay JDK ahi): el workflow es el"
  echo "primer compilador que ve estos commits. Lo que si se ejecuto aqui, y se puede reproducir:"
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
