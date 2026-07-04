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


# ═════════════════════════════════════════════════════════════════════════════
#  PAINEL 1 — ESTADO ATUAL (Digital Twins)
# ═════════════════════════════════════════════════════════════════════════════

if painel == "🔵 Estado Atual":
    st.title("🔵 Estado Atual dos Dispositivos")
    st.caption("Digital Twins atualizados em tempo real. "
               "Cada card reflete o último estado recebido do dispositivo.")

    twins = fetch_twins()
    drones   = twins.get("drones",   {})
    stations = twins.get("stations", {})

    if not drones and not stations:
        st.warning("Nenhum dispositivo detectado ainda. "
                   "Aguardando mensagens do gateway...")
        st.stop()

    # ── Drone ─────────────────────────────────────────────────────────────
    if drones:
        st.subheader("🚁 Drone")
        for did, d in drones.items():
            online  = d.get("is_online", False)
            phase   = d.get("flight_phase", "UNKNOWN")
            emoji   = d.get("phase_emoji",  "⚪")
            bat     = d.get("battery_pct",  0)
            bat_ok  = d.get("battery_ok",   True)

            with st.container(border=True):
                c1, c2, c3, c4 = st.columns(4)

                with c1:
                    st.markdown(f"### {'🟢' if online else '🔴'} {did}")
                    st.markdown(f"**Fase:** {emoji} {phase}")
                    st.markdown(f"**Waypoint:** {d.get('waypoint_index', 0)}")
                    st.markdown(f"**Formato:** `{d.get('payload_format','?')}`")

                with c2:
                    bat_color = "🟢" if bat_ok else "🔴"
                    st.metric("Bateria", f"{bat}%",
                              delta=None,
                              help="🔴 = abaixo do limiar de retorno")
                    st.markdown(f"{bat_color} {'OK' if bat_ok else 'ALERTA'}")
                    st.metric("RSSI", f"{d.get('rssi_dbm', 0)} dBm")
                    st.metric("Seq",  d.get("seq", 0))

                with c3:
                    st.metric("Altitude",   f"{d.get('alt_m', 0):.1f} m")
                    st.metric("Velocidade", f"{d.get('velocity_ms', 0):.1f} m/s")
                    st.metric("Heading",    f"{d.get('heading_deg', 0):.1f}°")
                    st.metric("Temperatura",f"{d.get('temp_c', 0):.2f} °C")

                with c4:
                    st.metric("Retorno est.",
                              f"{d.get('estimated_return_min', 0):.1f} min")
                    st.metric("Alcance",
                              f"{d.get('coverage_radius_m', 0):.0f} m")
                    anomaly = d.get("anomaly_detected", False)
                    st.markdown(
                        f"**Anomalia temp:** {'⚠️ Sim' if anomaly else '✅ Não'}")
                    msgs_lost = d.get("messages_lost", 0)
                    st.markdown(
                        f"**Msg perdidas:** "
                        f"{'⚠️ ' if msgs_lost > 0 else ''}{msgs_lost}")

                # Barra de progresso de bateria
                st.progress(bat / 100,
                            text=f"Bateria: {bat}%")

                # Mapa de posição
                lat = d.get("lat")
                lon = d.get("lon")
                if lat and lon and lat != 0 and lon != 0:
                    st.map(pd.DataFrame({"lat": [lat], "lon": [lon]}),
                           zoom=14, use_container_width=True)

    # ── Estação ───────────────────────────────────────────────────────────
    if stations:
        st.subheader("📡 Estação Estacionária")
        for sid, s in stations.items():
            online = s.get("is_online", False)

            with st.container(border=True):
                c1, c2, c3, c4 = st.columns(4)

                with c1:
                    st.markdown(f"### {'🟢' if online else '🔴'} {sid}")
                    st.markdown(f"**Protocolo:** MQTT")
                    co2_trend = s.get("co2_trend", "STABLE")
                    trend_icon = {"RISING": "📈", "STABLE": "➡️",
                                  "FALLING": "📉"}.get(co2_trend, "➡️")
                    st.markdown(f"**CO₂ trend:** {trend_icon} {co2_trend}")
                    uv_period = s.get("uv_period", "NIGHT")
                    uv_icon   = {"DAY": "☀️", "DUSK": "🌅",
                                 "NIGHT": "🌙"}.get(uv_period, "🌙")
                    st.markdown(f"**Período UV:** {uv_icon} {uv_period}")

                with c2:
                    st.metric("Temperatura", f"{s.get('temp_c', 0):.2f} °C")
                    st.metric("Umidade",     f"{s.get('hum_pct', 0):.1f} %")
                    st.metric("Pressão",     f"{s.get('pressure_hpa', 0):.2f} hPa")

                with c3:
                    st.metric("CO₂",      f"{s.get('co2_ppm', 0):.1f} ppm")
                    st.metric("UV Index", f"{s.get('uv_index', 0):.1f}")
                    st.metric("RSSI",     f"{s.get('rssi_dbm', 0)} dBm")

                with c4:
                    st.metric("Seq",             s.get("seq", 0))
                    st.metric("Msg recebidas",   s.get("messages_received", 0))
                    msgs_lost = s.get("messages_lost", 0)
                    st.markdown(
                        f"**Msg perdidas:** "
                        f"{'⚠️ ' if msgs_lost > 0 else ''}{msgs_lost}")
                    rate = s.get("delivery_rate_pct", 100.0)
                    st.metric("Taxa entrega",
                              f"{rate:.1f}%" if rate else "—")

    # Auto-refresh
    time.sleep(REFRESH_S)
    st.rerun()