#!/bin/bash
# ============================================================
#  deploy.sh — Sobe o ambiente completo do Datacenter
# ============================================================

set -e

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log()  { echo -e "${GREEN}[DATACENTER]${NC} $1"; }
warn() { echo -e "${YELLOW}[AVISO]${NC} $1"; }

# 1. Cria diretório para guardar os logs dos processos Python, se não existir
mkdir -p logs

echo -e "${CYAN}================================================${NC}"
echo -e "${CYAN}      Iniciando Deploy do Datacenter IoT        ${NC}"
echo -e "${CYAN}================================================${NC}"

# ── 1. Verifica e Sobe o InfluxDB (Docker) ──────────────────
log "Verificando banco de dados (InfluxDB)..."
if docker compose ps | grep -qi "Up\|running"; then
    log "Container do InfluxDB já está rodando."
else
    warn "Container inativo. Subindo InfluxDB..."
    docker compose up -d
    log "Aguardando InfluxDB inicializar..."
    sleep 3
fi

# ── 2. Verifica e Sobe a API ────────────────────────────────
log "Verificando API..."
# pgrep busca na lista de processos do Linux se o script ou o servidor Python já estão rodando
if pgrep -f "run_api.sh" > /dev/null || pgrep -f "uvicorn\|flask\|fastapi" > /dev/null; then
    warn "A API já parece estar em execução."
else
    log "Iniciando API em background..."
    # nohup solta o processo do terminal e o '&' joga para background
    nohup ./run_api.sh > logs/api.log 2>&1 &
    log "API iniciada (PID $!)."
fi

# ── 3. Verifica e Sobe o Dashboard (Streamlit) ──────────────
log "Verificando Dashboard..."
if pgrep -f "run_dashboard.sh" > /dev/null || pgrep -f "streamlit" > /dev/null; then
    warn "O Dashboard já parece estar em execução."
else
    log "Iniciando Dashboard em background..."
    nohup ./run_dashboard.sh > logs/dashboard.log 2>&1 &
    log "Dashboard iniciado (PID $!)."
fi

echo ""
log "🚀 Deploy concluído com sucesso!"
log "Para ver os logs da API use: tail -f logs/api.log"
log "Para ver os logs do App use: tail -f logs/dashboard.log"