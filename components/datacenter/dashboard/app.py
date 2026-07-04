"""
app.py — Dashboard Streamlit do Datacenter IoT

Quatro painéis:
  1. Estado Atual     — Digital Twins (drone e estação em tempo real)
  2. Sensores         — dados históricos de temperatura, umidade, etc.
  3. Latência         — métricas dos experimentos (RTT, latência E2E)
  4. Confiabilidade   — taxa de entrega, gaps de sequência, offline
"""

import os
import time
from datetime import datetime, timezone

import pandas as pd
import plotly.express as px
import plotly.graph_objects as go
import requests
import streamlit as st
from dotenv import load_dotenv

load_dotenv()

API_BASE = os.getenv("API_BASE_URL", "http://localhost:8000")

REFRESH_S = int(os.getenv("DASHBOARD_REFRESH_S", "2"))


# ── Configuração da página ────────────────────────────────────────────────────

st.set_page_config(
    page_title="Datacenter IoT Interoperável",
    page_icon=":satellite:",
    layout="wide",
    initial_sidebar_state="expanded",
)

# ── Helpers de API ────────────────────────────────────────────────────────────

@st.cache_data(ttl=REFRESH_S)
def fetch_twins() -> dict:
    try:
        r = requests.get(f"{API_BASE}/twins", timeout=3)
        return r.json() if r.ok else {"drones": {}, "stations": {}}
    except Exception as e:
        st.error(f"Falha ao buscar Digital Twins: {e}")
        return {"drones": {}, "stations": {}}

@st.cache_data(ttl=REFRESH_S)
def fetch_status() -> dict:
    try:
        r = requests.get(f"{API_BASE}/status", timeout=3)
        return r.json() if r.ok else {}
    except Exception as e:
        st.error(f"Falha ao buscar status do sistema: {e}")
        return {}

@st.cache_data(ttl=5)
def fetch_latency(source_type: str | None, minutes: int) -> dict:
    params = {"minutes": minutes}
    if source_type:
        params["source_type"] = source_type
    try:
        r = requests.get(f"{API_BASE}/metrics/latency", params=params, timeout=3)
        return r.json() if r.ok else {"data": [], "summary": {}}
    except Exception as e:
        st.error(f"Falha ao buscar métricas de latência: {e}")
        return {"data": [], "summary": {}}

@st.cache_data(ttl=5)
def fetch_sensor_data(source_type: str | None, minutes: int) -> list:
    params = {"minutes": minutes}
    if source_type:
        params["source_type"] = source_type
    try:
        r = requests.get(f"{API_BASE}/data", params=params, timeout=5)
        return r.json() if r.ok else []
    except Exception as e:
        st.error(f"Falha ao buscar dados históricos de sensores: {e}")
        return []

@st.cache_data(ttl=5)
def fetch_delivery(minutes: int) -> dict:
    try:
        r = requests.get(
            f"{API_BASE}/metricas/entrega",
            params={"minutes": minutes},
            timeout=5,
        )
        return r.json() if r.ok else {}
    except Exception as e:
        st.error(f"Falha ao buscar métricas de entrega: {e}")
        return {}

def api_online() -> bool:
    try:
        r = requests.get(f"{API_BASE}/status", timeout=2)
        return r.ok
    except Exception:
        return False


# ── Sidebar ───────────────────────────────────────────────────────────────────

with st.sidebar:
    st.title("📡 IoT Interoperável")
    st.caption("Arquitetura de Comunicação entre Plataformas IoT")
    st.divider()

    painel = st.radio(
        "Painel",
        ["🔵 Estado Atual", "🌡️ Sensores", "⏱️ Latência", "📦 Confiabilidade"],
        index=0,
    )

    st.divider()
    janela = st.slider("Janela de tempo (min)", 5, 120, 60, 5)
    st.caption(f"Refresh: {REFRESH_S}s")

    st.divider()
    status_api = "🟢 Online" if api_online() else "🔴 Offline"
    st.caption(f"API FastAPI: {status_api}")

    status_data = fetch_status()
    session     = status_data.get("session", {})
    if session:
        st.metric("Total recebido", session.get("total_received", 0))
        st.metric("Drones",   session.get("total_drones",   0))
        st.metric("Estações", session.get("total_stations", 0))
        st.metric("Erros",    session.get("total_errors",   0))