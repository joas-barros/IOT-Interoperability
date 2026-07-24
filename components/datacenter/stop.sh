#!/bin/bash
# ============================================================
#  stop.sh — Encerra os serviços do Datacenter e limpa os logs
# ============================================================

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log()  { echo -e "${GREEN}[STOP]${NC} $1"; }
warn() { echo -e "${YELLOW}[AVISO]${NC} $1"; }

echo -e "${CYAN}================================================${NC}"
echo -e "${CYAN}      Encerrando o Datacenter IoT               ${NC}"
echo -e "${CYAN}================================================${NC}"

# ── 1. Para o Dashboard (Streamlit) ─────────────────────────
log "Encerrando o Dashboard..."
if pgrep -f "streamlit" > /dev/null; then
    pkill -f "streamlit"
    log "Dashboard finalizado."
else
    warn "Dashboard não estava rodando."
fi

# ── 2. Para a API ───────────────────────────────────────────
log "Encerrando a API..."
if pgrep -f "uvicorn|flask|fastapi|run_api.sh" > /dev/null; then
    pkill -f "uvicorn|flask|fastapi|run_api.sh"
    log "API finalizada."
else
    warn "API não estava rodando."
fi

# ── 3. Para o InfluxDB (Docker Compose) ─────────────────────
log "Desligando contêineres do Docker Compose..."
if docker compose ps | grep -qi "Up\|running"; then
    docker compose down
    log "Docker Compose parado."
else
    warn "Nenhum contêiner ativo no Docker Compose."
fi

# ── 4. Limpa os arquivos de log ─────────────────────────────
log "Limpando arquivos de log..."
if [ -d "logs" ]; then
    rm -f logs/*.log
    log "Logs excluídos com sucesso."
else
    warn "Diretório de logs não encontrado."
fi

echo ""
log "🛑 Todos os serviços foram encerrados e o ambiente está limpo!"