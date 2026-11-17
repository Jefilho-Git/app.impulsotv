#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════
# Gera uma nova versão do ImpulsoTV para subir na Hostinger.
#
#   ./scripts/publicar.sh          → próxima versão (ex.: 3.2.1 → 3.2.2)
#   ./scripts/publicar.sh 3.3.0    → define o nome da versão
#
# O script:
#   1. soma 1 ao versionCode e atualiza o versionName em app/build.gradle
#   2. gera o APK de release (assinado com a chave de release)
#   3. cria dist/impulsotv-X.Y.Z.apk e dist/versao.json (com SHA-256)
#   4. registra a versão no git (commit "release: X.Y.Z")
#
# Depois, suba os 2 arquivos de dist/ para
#   https://impulsotv.impulsolab.com.br/upload/app/
# (primeiro o APK, por último o versao.json).
# As TV Boxes conferem a cada hora e instalam ao ligar ou das 3h às 5h.
# ════════════════════════════════════════════════════════════════
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f keystore.properties ] && [ ! -f ../keystore.properties ]; then
  echo "❌ keystore.properties não encontrado — sem a chave de release as TVs recusam a atualização."
  echo "   Veja ARCHITECTURE.md (seção Assinatura de release)."
  exit 1
fi

GRADLE_FILE=app/build.gradle
OLD_VC=$(grep -m1 -E '^[[:space:]]*versionCode [0-9]+' "$GRADLE_FILE" | awk '{print $2}')
OLD_VN=$(grep -m1 -E '^[[:space:]]*versionName "' "$GRADLE_FILE" | sed -E 's/.*"(.*)".*/\1/')

NEW_VC=$((OLD_VC + 1))
if [ $# -ge 1 ] && [ -n "$1" ]; then
  NEW_VN="$1"
else
  # 3.2.1 → 3.2.2 (aumenta o último número)
  IFS='.' read -r -a P <<< "$OLD_VN"
  LAST=$(( ${#P[@]} - 1 ))
  P[$LAST]=$(( ${P[$LAST]%%[!0-9]*} + 1 ))
  NEW_VN=$(IFS='.'; echo "${P[*]}")
fi

echo "▶ ImpulsoTV $OLD_VN ($OLD_VC) → $NEW_VN ($NEW_VC)"
sed -i '' -E "s/^([[:space:]]*versionCode )$OLD_VC$/\1$NEW_VC/" "$GRADLE_FILE"
sed -i '' -E "s/^([[:space:]]*versionName )\"$OLD_VN\"$/\1\"$NEW_VN\"/" "$GRADLE_FILE"

if ! ./gradlew -q :app:assembleRelease; then
  echo "❌ Build falhou — revertendo a versão em $GRADLE_FILE"
  git checkout -- "$GRADLE_FILE"
  exit 1
fi

APK=app/build/outputs/apk/release/app-release.apk
NOME="impulsotv-$NEW_VN.apk"
mkdir -p dist
rm -f dist/*.apk dist/versao.json
cp "$APK" "dist/$NOME"
SHA=$(shasum -a 256 "dist/$NOME" | awk '{print $1}')

cat > dist/versao.json <<JSON
{
  "versionCode": $NEW_VC,
  "versionName": "$NEW_VN",
  "url": "$NOME",
  "sha256": "$SHA"
}
JSON

git add "$GRADLE_FILE"
git commit -q -m "release: $NEW_VN (versionCode $NEW_VC)" && echo "✔ Versão registrada no git"

echo ""
echo "✅ Pronto. Suba para https://impulsotv.impulsolab.com.br/upload/app/ :"
echo "   1) dist/$NOME"
echo "   2) dist/versao.json   (por último)"
