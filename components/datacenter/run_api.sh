#!/bin/bash
# ============================================================
#  run_api.sh — Inicia o servidor FastAPI (ingestão + métricas)
# ============================================================
set -e

cd "$(dirname "$0")"

if [ ! -d ".venv" ]; then
    echo "[setup] Criando ambiente virtual..."
    python -m venv .venv
fi

source .venv/bin/activate
pip install -q -r requirements.txt

echo "[run] Iniciando FastAPI em http://localhost:8000 ..."
echo "[run] Documentação Swagger em http://localhost:8000/docs"
uvicorn api.main:app --host 0.0.0.0 --port 8000 --reload