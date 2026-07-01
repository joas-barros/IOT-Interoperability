#!/bin/bash
# ============================================================
#  run_dashboard.sh — Inicia o dashboard Streamlit
# ============================================================
set -e

cd "$(dirname "$0")"

if [ ! -d ".venv" ]; then
    echo "[setup] Criando ambiente virtual..."
    python3 -m venv .venv
fi

source .venv/bin/activate
pip install -q -r requirements.txt

echo "[run] Iniciando Streamlit em http://localhost:8501 ..."
streamlit run dashboard/app.py