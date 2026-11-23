#!/usr/bin/env bash
###############################################################################
# fix-merge-deliveries.sh
#
# Corrige a quebra de compilacao do modulo raiji-deliveries na branch
# feature/integracao-completa (merge de develop + feature/integracao-parte-2).
#
# CAUSA DO PROBLEMA:
#   A resolucao de conflitos manteve o lado da develop nos 6 arquivos
#   conflitantes do modulo deliveries, incluindo a REMOCAO de
#   com/raiji/deliveries/domain/Delivery.java (apagado pela develop).
#   Mas 2 arquivos NOVOS da feature entraram sem conflito e dependem do
#   modelo antigo:
#     - service/DeliveryPaymentService.java  (usa domain.Delivery e
#       DeliveryRepository.findByPaymentIntentId)
#     - service/DeliveryRefunds.java
#   -> javac falha: "package com.raiji.deliveries.domain does not exist",
#      "cannot find symbol: class Delivery", "cannot find symbol:
#      method findByPaymentIntentId".
#
# SOLUCAO (Plano A - padrao deste script):
#   Restaurar as 6 versoes da FEATURE dos arquivos que conflitaram, deixando
#   o modulo deliveries coerente com os servicos novos de pagamento.
#   O script compila antes de commitar: se algo ainda falhar, NADA e commitado.
#
# USO (dentro de ~/Documentos/AVXAE-CORE-MONOLITH):
#   bash fix-merge-deliveries.sh                # fix completo (compila + commita + build com testes)
#   SKIP_FULL=1 bash fix-merge-deliveries.sh    # pula o build final com testes
#   COMMIT=0  bash fix-merge-deliveries.sh      # restaura e compila, mas nao commita
#   FORCE=1   bash fix-merge-deliveries.sh      # nao exige estar na branch esperada
#
# DESFAZER (se precisar):
#   git reset --hard backup/antes-fix-deliveries
###############################################################################
set -uo pipefail

FEATURE_REF="${FEATURE_REF:-origin/feature/integracao-parte-2}"
BRANCH_ESPERADA="feature/integracao-completa"
BACKUP_BRANCH="backup/antes-fix-deliveries"
MOD="raiji-deliveries/src/main/java/com/raiji/deliveries"
LOG_DIR=".fix-merge-logs"
mkdir -p "$LOG_DIR"

ARQUIVOS=(
  "$MOD/domain/Delivery.java"
  "$MOD/dto/DeliveryResponse.java"
  "$MOD/repository/DeliveryRepository.java"
  "$MOD/service/DeliveryRideBookings.java"
  "$MOD/service/DeliveryService.java"
  "$MOD/web/DeliveryController.java"
)

say()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m  OK\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mAVISO\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mERRO\033[0m %s\n' "$*" >&2; exit 1; }

###############################################################################
# 0) Preflight
###############################################################################
say "0) Verificacoes iniciais"

git rev-parse --is-inside-work-tree >/dev/null 2>&1 \
  || die "Rode este script dentro do repositorio AVXAE-CORE-MONOLITH."

cd "$(git rev-parse --show-toplevel)" || die "Nao foi possivel entrar na raiz do repositorio."

RAMO_ATUAL="$(git branch --show-current)"
if [ "$RAMO_ATUAL" != "$BRANCH_ESPERADA" ] && [ "${FORCE:-0}" != "1" ]; then
  die "Voce esta na branch '$RAMO_ATUAL'; esperado '$BRANCH_ESPERADA'.
     Rode: git checkout $BRANCH_ESPERADA   (ou FORCE=1 bash fix-merge-deliveries.sh)"
fi
ok "Branch atual: $RAMO_ATUAL"

git rev-parse --verify -q "$FEATURE_REF^{commit}" >/dev/null \
  || die "Referencia $FEATURE_REF nao existe. Rode antes: git fetch origin --prune --tags"
ok "Referencia encontrada: $FEATURE_REF ($(git rev-parse --short "$FEATURE_REF"))"

GITDIR="$(git rev-parse --git-dir)"
[ -e "$GITDIR/MERGE_HEAD" ] && die "Ha um merge em andamento. Conclua (git commit) ou aborte (git merge --abort) antes."

if ! git diff --quiet || ! git diff --cached --quiet; then
  die "Arvore de trabalho suja. Faca commit ou stash antes de rodar o script."
fi
ok "Arvore de trabalho limpa (arquivos nao monitorados, como os dumps, nao atrapalham)."

for f in "${ARQUIVOS[@]}"; do
  git rev-parse -q --verify "$FEATURE_REF:$f" >/dev/null \
    || die "Arquivo nao existe em $FEATURE_REF: $f"
done
ok "Os 6 arquivos existem na feature."

say "Backup de seguranca"
git branch -f "$BACKUP_BRANCH" HEAD
BACKUP_SHA="$(git rev-parse --short HEAD)"
ok "Estado atual salvo na branch '$BACKUP_BRANCH' ($BACKUP_SHA)"

###############################################################################
# 1) Diagnostico (evidencias do problema)
###############################################################################
say "1) Diagnostico da arvore atual"
echo "-- Arquivos que ainda referenciam o pacote antigo com.raiji.deliveries.domain:"
grep -rln "com.raiji.deliveries.domain" raiji-deliveries/src/main/java 2>/dev/null || echo "   (nenhum)"
echo "-- Referencias a findByPaymentIntentId:"
grep -rn "findByPaymentIntentId" raiji-deliveries/src/main/java 2>/dev/null || echo "   (nenhuma)"

###############################################################################
# 2) Aplica a correcao: restaura as 6 versoes da FEATURE
###############################################################################
say "2) Restaurando as versoes da feature nos arquivos que conflitaram"
for f in "${ARQUIVOS[@]}"; do
  git checkout "$FEATURE_REF" -- "$f" && ok "restaurado: $f" \
    || die "Falha ao restaurar $f (ref: $FEATURE_REF)"
done

###############################################################################
# 3) Verificacao rapida: compila o modulo + dependencias (sem testes)
###############################################################################
say "3) Compilacao de verificacao (raiji-deliveries + dependencias, sem testes)"
MVN="mvn"
[ -x ./mvnw ] && MVN="./mvnw"
command -v mvn >/dev/null 2>&1 || { [ -x ./mvnw ] || die "Maven nao encontrado (nem 'mvn' nem './mvnw')."; }

QLOG="$LOG_DIR/quick-compile.log"
if "$MVN" -B -pl raiji-deliveries -am clean install -DskipTests >"$QLOG" 2>&1; then
  ok "Compilacao rapida PASSOU (log: $QLOG)"
else
  echo
  warn "A compilacao AINDA falhou. Nada foi commitado. Erros encontrados:"
  grep -E "^\[ERROR\]" "$QLOG" | head -40 || true
  echo
  warn "Suas opcoes agora:"
  echo "  a) DESFAZER:  git reset --hard $BACKUP_BRANCH"
  echo "  b) PLANO B (adaptar os 2 arquivos novos ao modelo da develop) - colete isso e me envie:"
  echo "       cat $QLOG"
  echo "       git show origin/develop:$MOD/repository/DeliveryRepository.java | head -80"
  echo "       git show origin/develop:$MOD/service/DeliveryService.java | head -80"
  echo "       git log --oneline -10 origin/develop -- raiji-deliveries"
  exit 1
fi

###############################################################################
# 4) Commit da correcao
###############################################################################
if [ "${COMMIT:-1}" = "1" ]; then
  say "4) Commitando a correcao"
  git commit \
    -m "fix(deliveries): restaura modelo da feature nos arquivos conflitantes do modulo deliveries" \
    -m "O merge manteve o lado da develop nos 6 conflitos, mas DeliveryPaymentService e DeliveryRefunds (novos, da feature) dependem de com.raiji.deliveries.domain.Delivery e de DeliveryRepository.findByPaymentIntentId, que so existem na versao da feature. Restaura Delivery.java, DeliveryResponse.java, DeliveryRepository.java, DeliveryRideBookings.java, DeliveryService.java e DeliveryController.java de origin/feature/integracao-parte-2." \
    || die "git commit falhou."
  ok "Commit criado: $(git rev-parse --short HEAD)"
else
  warn "COMMIT=0: mudancas restauradas e estagiadas, mas NAO commitadas."
  echo "   Para commitar depois:"
  echo "     git commit -m 'fix(deliveries): restaura modelo da feature nos arquivos conflitantes'"
fi

###############################################################################
# 5) Build completo com testes (gate final)
###############################################################################
if [ "${SKIP_FULL:-0}" != "1" ]; then
  say "5) Build completo com testes (mvn clean install)"
  FLOG="$LOG_DIR/full-build.log"
  if "$MVN" -B clean install >"$FLOG" 2>&1; then
    ok "BUILD SUCCESS - projeto 100% compilavel e testes verdes (log: $FLOG)"
    grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+" "$FLOG" | tail -6 || true
  else
    warn "Build completo FALHOU (a compilacao rapida tinha passado; provavel teste). Erros:"
    grep -E "^\[ERROR\]|BUILD FAILURE|Tests run:.*(Failures: [1-9]|Errors: [1-9])" "$FLOG" | head -40 || true
    warn "O commit do fix ja foi feito. Para desfazer tudo: git reset --hard $BACKUP_BRANCH"
    echo "   Me envie o arquivo $FLOG para eu analisar os testes."
    exit 1
  fi
fi

###############################################################################
# 6) Resumo
###############################################################################
say "Resumo"
echo "  - 6 arquivos do modulo raiji-deliveries restaurados da feature/integracao-parte-2"
[ "${COMMIT:-1}" = "1" ] && echo "  - Correcao commitada em $(git rev-parse --short HEAD)"
echo "  - Backup do estado anterior em '$BACKUP_BRANCH' ($BACKUP_SHA)"
echo
say "Proximos passos"
echo "  git push -u origin $BRANCH_ESPERADA"
echo "  # e rode novamente o build Docker que falhou pelo mesmo motivo:"
echo "  # docker build ...   (o RUN mvn -B clean package agora deve passar)"
