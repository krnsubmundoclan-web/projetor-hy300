#!/usr/bin/env bash
# Compila o Projetor.apk sem Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner (chave de plataforma AOSP).
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME=/c/dev/android/jdk17
export PATH="$JAVA_HOME/bin:$PATH"
SDK=/c/dev/android/sdk
BT=$SDK/build-tools/34.0.0
AJ=$SDK/platforms/android-30/android.jar
KEYS=/c/dev/android/keys

VERSION_CODE=$(grep -oP 'android:versionCode="\K[0-9]+' AndroidManifest.xml)
VERSION_NAME=$(grep -oP 'android:versionName="\K[^"]+' AndroidManifest.xml)
rm -rf build && mkdir -p build

# construir <sistema|compat|teste>: "teste" e a compat com outro pacote e porta, para rodar ao lado do app instalado.
construir() {
  local V=$1 W=build/$1 NAME=$VERSION_NAME SRC=src
  local RENAME=()
  echo "Compilando $V $VERSION_NAME (code $VERSION_CODE)..."
  mkdir -p "$W/gen" "$W/classes" "$W/dex"
  if [ "$V" = sistema ]; then
    sed '/COMPAT-INICIO/,/COMPAT-FIM/d' AndroidManifest.xml > "$W/AndroidManifest.xml"
  else
    NAME=$VERSION_NAME-compat
    sed -e '/sharedUserId/d' -e '/SISTEMA-INICIO/,/SISTEMA-FIM/d' -e '/COMPAT-INICIO/d' -e '/COMPAT-FIM/d' \
      -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$NAME\"/" AndroidManifest.xml > "$W/AndroidManifest.xml"
  fi
  if [ "$V" = teste ]; then
    RENAME=(--rename-manifest-package com.mlab.projetor.teste)
    SRC=$W/src
    # Sem HOME: um segundo launcher faria o Android perder a tela inicial padrao.
    sed -i '/category.HOME/d' "$W/AndroidManifest.xml"
    cp -r src "$SRC"
    sed -i 's/PORT = 8080/PORT = 8081/' "$SRC/com/mlab/projetor/ControlService.java"
  fi

  "$BT/aapt2.exe" compile --dir res -o "$W/res.zip"
  "$BT/aapt2.exe" link -o "$W/base.apk" -I "$AJ" --manifest "$W/AndroidManifest.xml" -A assets \
    --java "$W/gen" --min-sdk-version 28 --target-sdk-version 28 \
    --version-code "$VERSION_CODE" --version-name "$NAME" ${RENAME[@]+"${RENAME[@]}"} "$W/res.zip"

  javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJ" -classpath "$BT/core-lambda-stubs.jar" -Xlint:-options \
    -d "$W/classes" $(find "$SRC" "$W/gen" -name '*.java')

  "$BT/d8.bat" --release --min-api 28 --lib "$AJ" --output "$W/dex" $(find "$W/classes" -name '*.class')

  python -I - "$W" <<'EOF'
import shutil, sys, zipfile
w = sys.argv[1]
shutil.copy(w + '/base.apk', w + '/unsigned.apk')
with zipfile.ZipFile(w + '/unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(w + '/dex/classes.dex', 'classes.dex')
EOF

  "$BT/zipalign.exe" -p -f 4 "$W/unsigned.apk" "$W/aligned.apk"
  "$BT/apksigner.bat" sign --key "$KEYS/platform.pk8" --cert "$KEYS/platform.x509.pem" \
    --out "build/Projetor-$V.apk" "$W/aligned.apk"
}

if [ $# -eq 0 ]; then set -- sistema compat; fi
for V in "$@"; do construir "$V"; done
[ -f build/Projetor-sistema.apk ] && cp build/Projetor-sistema.apk build/Projetor.apk
"$BT/apksigner.bat" verify --print-certs "build/Projetor-$1.apk" | grep -i "SHA-1"
ls -l build/*.apk
