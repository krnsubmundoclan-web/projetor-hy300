#!/usr/bin/env bash
# Compila o ProjetorInstalador.apk sem Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner (chave propria de release).
# O Projetor.apk que vai dentro fica em assets/ (copie a versao nova antes de compilar).
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME=/c/dev/android/jdk17
export PATH="$JAVA_HOME/bin:$PATH"
SDK=/c/dev/android/sdk
BT=$SDK/build-tools/34.0.0
AJ=$SDK/platforms/android-30/android.jar
KEYS=/c/dev/android/keys
VERSION_CODE=${VERSION_CODE:-3}
VERSION_NAME=${VERSION_NAME:-1.1.0}
KS_PASS=$(grep '^senha:' "$KEYS/instalador-senha.txt" | cut -d' ' -f2)

rm -rf build/apk && mkdir -p build/apk/gen build/apk/classes build/apk/dex

"$BT/aapt2.exe" compile --dir res -o build/apk/res.zip
"$BT/aapt2.exe" link -o build/apk/base.apk -I "$AJ" --manifest AndroidManifest.xml -A assets -0 apk \
  --java build/apk/gen --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" build/apk/res.zip

javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJ" -classpath "$BT/core-lambda-stubs.jar" -Xlint:-options \
  -d build/apk/classes $(find src build/apk/gen -name '*.java')

"$BT/d8.bat" --release --min-api 26 --lib "$AJ" --output build/apk/dex $(find build/apk/classes -name '*.class')

python -I - <<'EOF'
import shutil, zipfile
shutil.copy('build/apk/base.apk', 'build/apk/unsigned.apk')
with zipfile.ZipFile('build/apk/unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('build/apk/dex/classes.dex', 'classes.dex')
EOF

"$BT/zipalign.exe" -p -f 4 build/apk/unsigned.apk build/apk/aligned.apk
"$BT/apksigner.bat" sign --ks "$KEYS/instalador.jks" --ks-pass "pass:$KS_PASS" \
  --out build/ProjetorInstalador.apk build/apk/aligned.apk
"$BT/apksigner.bat" verify --print-certs build/ProjetorInstalador.apk | grep -i "SHA-256"
ls -l build/ProjetorInstalador.apk
