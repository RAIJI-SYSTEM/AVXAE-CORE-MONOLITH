#!/usr/bin/env bash
###############################################################################
# diagnostico-merge.sh  --  SOMENTE LEITURA (nao altera NADA no repositorio)
#
# Coleta das duas branches os arquivos-chave da integracao
# raiji-deliveries x raiji-rides para montar o port exato dos arquivos
# de pagamento da feature para o modelo novo da develop.
#
# Uso:
#   bash diagnostico-merge.sh > saida-diagnostico.txt 2>&1
#   (ou rode direto e copie tudo que aparecer na tela)
#
# Depois:
#   - Cole aqui no chat o conteudo de saida-diagnostico.txt, OU
#   - Envie o pacote completo:  tar czf dumps-fix.tgz .fix-merge-dumps
###############################################################################
set -uo pipefail

DEV=origin/develop
FEAT=origin/feature/integracao-parte-2
OUT=.fix-merge-dumps
mkdir -p "$OUT" || exit 1

git rev-parse --is-inside-work-tree >/dev/null 2>&1 \
  || { echo "ERRO: rode dentro do repositorio AVXAE-CORE-MONOLITH"; exit 1; }

MOD=raiji-deliveries/src/main/java/com/raiji/deliveries
RIDES=raiji-rides/src/main/java/com/raiji/rides
SQLDIR=raiji-deliveries/src/main/resources/db/migration
BASE="$(git merge-base "$DEV" "$FEAT" 2>/dev/null || echo '?')"

hr() { echo; echo "##################################################"; echo "# $*"; echo "##################################################"; echo; }

# show_full <slug> <titulo> <ref> <caminho>  -> salva em $OUT e imprime com numero de linhas
show_full() {
  local slug="$1" titulo="$2" ref="$3" p="$4"
  hr "$titulo  [$ref]"
  if git cat-file -e "$ref:$p" 2>/dev/null; then
    git show "$ref:$p" > "$OUT/$slug.txt"
    cat -n "$OUT/$slug.txt"
  else
    echo "[NAO EXISTE EM $ref]" | tee "$OUT/$slug.txt"
  fi
}

# show_diff <slug> <titulo> <de> <para> <caminho>
show_diff() {
  local slug="$1" titulo="$2" de="$3" para="$4" p="$5"
  hr "DIFF: $titulo  ($de -> $para)"
  git diff "$de" "$para" -- "$p" > "$OUT/$slug.diff" 2>/dev/null || true
  if [ -s "$OUT/$slug.diff" ]; then
    cat -n "$OUT/$slug.diff"
  else
    echo "(sem diferencas)"
  fi
}

###############################################################################
hr "0) CONTEXTO"
echo "HEAD atual: $(git rev-parse --short HEAD) ($(git branch --show-current))"
echo "$DEV = $(git rev-parse --short "$DEV")"
echo "$FEAT = $(git rev-parse --short "$FEAT")"
echo "merge-base = $BASE"

###############################################################################
hr "1) ONDE FICA A ENTIDADE Delivery / PackageDetails / PackageSize EM CADA BRANCH"
echo "-- Arquivos com nome Delivery*.java / Package* em $DEV:"
git ls-tree -r --name-only "$DEV" -- raiji-deliveries/src/main/java | grep -E 'Delivery\.java$|Package' || echo "(nenhum)"
echo
echo "-- Arquivos com nome Delivery*.java / Package* em $FEAT:"
git ls-tree -r --name-only "$FEAT" -- raiji-deliveries/src/main/java | grep -E 'Delivery\.java$|Package' || echo "(nenhum)"
echo
echo "-- Quem define 'class Delivery' em $DEV:"
git grep -n "class Delivery" "$DEV" -- raiji-deliveries | head -5 || true
echo
echo "-- Referencias a startConfirmed em $DEV (quem usa a interface):"
git grep -n "startConfirmed" "$DEV" || echo "(nenhuma)" | head -20
echo
echo "-- Referencias a startConfirmed em $FEAT:"
git grep -n "startConfirmed" "$FEAT" || echo "(nenhuma)" | head -20
echo
echo "-- Referencias a PackageDetails/PackageSize em $DEV:"
git grep -n "PackageDetails\|PackageSize" "$DEV" -- raiji-deliveries | head -20 || echo "(nenhuma)"
echo
echo "-- Referencias a com.raiji.deliveries.domain em $FEAT (modelo antigo):"
git grep -n "com\.raiji\.deliveries\.domain" "$FEAT" -- raiji-deliveries | head -30 || echo "(nenhuma)"

###############################################################################
hr "2) A INTERFACE COMPARTILHADA (a 'costura' entre rides e deliveries)"
show_diff "02_interface_RidePackageBookings" "RidePackageBookings.java" "$FEAT" "$DEV" "$RIDES/cargo/RidePackageBookings.java"
show_full "02a_interface_RidePackageBookings_FEAT" "RidePackageBookings.java (versao completa)" "$FEAT" "$RIDES/cargo/RidePackageBookings.java"
show_full "02b_interface_RidePackageBookings_DEV" "RidePackageBookings.java (versao completa)" "$DEV" "$RIDES/cargo/RidePackageBookings.java"
show_diff "02c_DeliveryRideBookings" "DeliveryRideBookings.java" "$FEAT" "$DEV" "$MOD/service/DeliveryRideBookings.java"
show_full "02d_DeliveryRideBookings_FEAT" "DeliveryRideBookings.java (versao completa)" "$FEAT" "$MOD/service/DeliveryRideBookings.java"

###############################################################################
hr "3) ARQUIVOS DE PAGAMENTO DA FEATURE (os 2 que precisam de port)"
show_full "03_DeliveryPaymentService_FEAT" "DeliveryPaymentService.java" "$FEAT" "$MOD/service/DeliveryPaymentService.java"
show_full "03_DeliveryRefunds_FEAT" "DeliveryRefunds.java" "$FEAT" "$MOD/service/DeliveryRefunds.java"
show_full "03_V23_sql_FEAT" "V23__delivery_payment_status.sql" "$FEAT" "$SQLDIR/V23__delivery_payment_status.sql"

###############################################################################
hr "4) MODELO NOVO DA DEVELOP (destino do port)"
DEV_DELIVERY="$(git ls-tree -r --name-only "$DEV" -- raiji-deliveries/src/main/java | grep -E '/Delivery\.java$' | head -1)"
echo "(entidade Delivery da develop detectada em: ${DEV_DELIVERY:-NENHUMA})"
if [ -n "${DEV_DELIVERY:-}" ]; then
  show_full "04_Delivery_DEV" "Entidade Delivery (develop)" "$DEV" "$DEV_DELIVERY"
fi
show_full "04_DeliveryRepository_DEV" "DeliveryRepository.java (develop)" "$DEV" "$MOD/repository/DeliveryRepository.java"
show_full "04_DeliveryRepository_FEAT" "DeliveryRepository.java (feature)" "$FEAT" "$MOD/repository/DeliveryRepository.java"

###############################################################################
hr "5) CONTEXTO EXTRA (salvo em $OUT, impressao resumida)"
show_diff "05_DeliveryService" "DeliveryService.java" "$FEAT" "$DEV" "$MOD/service/DeliveryService.java"
show_diff "05_FreightService" "FreightService.java" "$FEAT" "$DEV" "$MOD/service/FreightService.java"
show_diff "05_TrackingCodeGenerator" "TrackingCodeGenerator.java" "$FEAT" "$DEV" "$MOD/service/TrackingCodeGenerator.java"
show_diff "05_DeliveryController" "DeliveryController.java" "$FEAT" "$DEV" "$MOD/web/DeliveryController.java"
show_diff "05_DeliveryResponse" "DeliveryResponse.java" "$FEAT" "$DEV" "$MOD/dto/DeliveryResponse.java"

hr "5b) ALCANCE DO REFACTOR DA DEVELOP NO MODULO"
echo "-- git diff --stat base..develop (raiji-deliveries):"
git diff --stat "$BASE" "$DEV" -- raiji-deliveries | tail -40 || true
echo
echo "-- commits recentes da develop tocando raiji-deliveries:"
git log --oneline -10 "$DEV" -- raiji-deliveries || true

hr "5c) ARQUIVOS SO EM UMA DAS BRANCHES (raiji-deliveries)"
echo "-- Somente na FEATURE (nao existem na develop):"
comm -13 <(git ls-tree -r --name-only "$DEV" -- raiji-deliveries | sort) \
         <(git ls-tree -r --name-only "$FEAT" -- raiji-deliveries | sort) | tee "$OUT/apenas_feature.txt"
echo
echo "-- Somente na DEVELOP (nao existem na feature):"
comm -23 <(git ls-tree -r --name-only "$DEV" -- raiji-deliveries | sort) \
         <(git ls-tree -r --name-only "$FEAT" -- raiji-deliveries | sort) | tee "$OUT/apenas_develop.txt"

###############################################################################
hr "6) COMO ME ENVIAR ISTO"
echo "Opcao A (preferida): cole aqui no chat a saida completa deste script."
echo "Opcao B: gere o pacote e envie:  tar czf dumps-fix.tgz .fix-merge-dumps"
echo
echo "Arquivos salvos em $OUT:"
ls -la "$OUT" 2>/dev/null || true
