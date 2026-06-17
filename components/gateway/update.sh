#!/bin/bash
# ============================================================
#  update.sh — Atualiza e reinicia o Gateway IoT no RPi B
#
#  Uso:
#    ./update.sh           → atualiza do branch atual
#    ./update.sh main      → força atualização do branch main
#
#  Fluxo:
#    1. git pull
#    2. docker compose build gateway  (só reconstrói o gateway)
#    3. docker compose up -d --no-deps gateway  (reinicia só o gateway)
#       ↑ Mosquitto continua rodando sem interrupção
#    4. Verifica saúde e mostra logs
# ============================================================

set -e

BRANCH=${1:-""}
PROJECT_DIR="$HOME/projects/IOT-Interoperability/components/gateway"
SERVICE="gateway"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log()  { echo -e "${GREEN}[UPDATE]${NC} $1"; }
warn() { echo -e "${YELLOW}[AVISO]${NC} $1"; }
fail() { echo -e "${RED}[ERRO]${NC} $1"; exit 1; }

# ── Pré-requisitos ──────────────────────────────────────────
command -v docker   >/dev/null || fail "Docker não encontrado."
command -v git      >/dev/null || fail "Git não encontrado."
[ -d "$PROJECT_DIR" ]          || fail "Diretório $PROJECT_DIR não encontrado."

cd "$PROJECT_DIR"

# ── Git pull ────────────────────────────────────────────────
log "Baixando atualizações do GitHub..."
[ -n "$BRANCH" ] && git checkout "$BRANCH"

COMMIT_BEFORE=$(git rev-parse --short HEAD)
git pull
COMMIT_AFTER=$(git rev-parse --short HEAD)

if [ "$COMMIT_BEFORE" = "$COMMIT_AFTER" ]; then
    warn "Nenhuma atualização (já na versão $COMMIT_AFTER)."
    warn "Continuar mesmo assim? [s/N]"
    read -r resp
    [ "$resp" != "s" ] && [ "$resp" != "S" ] && { log "Cancelado."; exit 0; }
else
    log "Atualizado: $COMMIT_BEFORE → $COMMIT_AFTER"
    git log --oneline "$COMMIT_BEFORE".."$COMMIT_AFTER"
    echo ""
fi

# ── Build da imagem do gateway ──────────────────────────────
log "Reconstruindo imagem do gateway..."
docker compose build $SERVICE

# ── Reinicia só o gateway (Mosquitto continua rodando) ──────
log "Reiniciando container do gateway..."
docker compose up -d --no-deps $SERVICE

# ── Aguarda estabilização ───────────────────────────────────
log "Aguardando estabilização (5s)..."
sleep 5

# ── Verifica saúde ──────────────────────────────────────────
GATEWAY_STATUS=$(docker compose ps $SERVICE --format json 2>/dev/null \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('State','?'))" \
    2>/dev/null || echo "unknown")

if docker compose ps $SERVICE | grep -q "Up"; then
    log "Gateway rodando!"
    echo ""
    docker compose ps
    echo ""
    log "Últimas linhas de log:"
    docker compose logs --tail=20 $SERVICE
else
    fail "Gateway falhou ao iniciar!"
    docker compose logs --tail=30 $SERVICE
fi

log "Atualização concluída. Commit: $COMMIT_AFTER"
log "Para acompanhar logs: docker compose logs -f gateway"