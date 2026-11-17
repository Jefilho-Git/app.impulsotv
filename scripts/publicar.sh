#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════
# Gera o pacote de atualização para subir na Hostinger.
#
#   1. Aumente versionCode e versionName em app/build.gradle
#   2. Rode:  ./scripts/publicar.sh
#   3. Suba os 2 arquivos de dist/ para
#      https://impulsotv.impulsolab.com.br/app/
#      (primeiro o APK, por último o versao.json)
#
# As TV Boxes conferem o versao.json a cada hora e instalam ao ligar
# ou de madrugada (3h–5h).
# ════════════════════════════════════════════════════════════════
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f keystore.properties ]; then
  echo "❌ keystore.properties não encontrado — sem a chave de release as TVs recusam a atualização."
  echo "   Veja ARCHITECTURE.md (seção Assinatura de release)."
  exit 1
fi

VC=$(grep -m1 -E '^\s*versionCode ' app/build.gradle | awk '{print $2}')
VN=$(grep -m1 -E '^\s*versionName ' app/build.gradle | sed -E 's/.*"(.*)".*/\1/')
echo "▶ Gerando ImpulsoTV $VN (versionCode $VC)…"

./gradlew -q :app:assembleRelease

APK=app/build/outputs/apk/release/app-release.apk
NOME="impulsotv-$VN.apk"
mkdir -p dist
cp "$APK" "dist/$NOME"
SHA=$(shasum -a 256 "dist/$NOME" | awk '{print $1}')

cat > dist/versao.json <<JSON
{
  "versionCode": $VC,
  "versionName": "$VN",
  "url": "$NOME",
  "sha256": "$SHA"
}
JSON

echo "✅ Pronto. Suba para https://impulsotv.impulsolab.com.br/app/ :"
echo "   1) dist/$NOME"
echo "   2) dist/versao.json   (por último)"
