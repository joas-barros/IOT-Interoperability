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
            f"{API_BASE}/metrics/delivery",
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
                    # 1. Mapeamento de cores por fase de voo
                    color_map = {
                        "IDLE": "#FFFFFF",    # Branco
                        "TAKEOFF": "#00FFFF", # Ciano
                        "MISSION": "#00FF00", # Verde
                        "HOVER": "#FF00FF",   # Magenta
                        "RETURN": "#FF8000",  # Laranja
                        "LANDING": "#FF0000"  # Vermelho
                    }
                    
                    # 2. Define a cor baseada na fase atual, usa cinza se a fase não for reconhecida
                    marker_color = color_map.get(phase, "#808080")

                    # 3. Cria o DataFrame com as novas colunas
                    map_df = pd.DataFrame({
                        "lat": [lat], 
                        "lon": [lon],
                        "color": [marker_color],
                        "radius": [15] # Um tamanho muito menor para o círculo
                    })

                    # 4. Renderiza o mapa apontando as propriedades visuais para as colunas do DataFrame
                    st.map(
                        map_df,
                        latitude="lat", 
                        longitude="lon", 
                        color="color", 
                        size="radius",
                        zoom=14, 
                        use_container_width=True
                    )

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


# ═════════════════════════════════════════════════════════════════════════════
#  PAINEL 2 — SENSORES (dados históricos)
# ═════════════════════════════════════════════════════════════════════════════

elif painel == "🌡️ Sensores":
    st.title("🌡️ Dados dos Sensores")
    st.caption(f"Últimos {janela} minutos")

    fonte = st.segmented_control(
        "Fonte",
        ["Todos", "Drone", "Estação"],
        default="Todos",
    )

    source_map = {"Drone": "DRONE", "Estação": "STATION", "Todos": None}
    raw = fetch_sensor_data(source_map[fonte], janela)

    if not raw:
        st.info("Sem dados no período selecionado.")
        st.stop()

    df = pd.DataFrame(raw)
    df["time"] = pd.to_datetime(df["time"], format='mixed', utc=True)
    df = df.sort_values("time")

    # ── Temperatura ───────────────────────────────────────────────────────
    st.subheader("🌡️ Temperatura (°C)")
    df_temp = df[df["temp_c"].notna()]
    if not df_temp.empty:
        fig = px.line(
            df_temp, x="time", y="temp_c",
            color="source_id",
            color_discrete_map={"drone_01": "#3B82F6", "estacao_01": "#10B981"},
            labels={"temp_c": "Temperatura (°C)", "time": "", "source_id": "Fonte"},
            markers=True,
        )
        fig.update_layout(height=280, margin=dict(t=10, b=10))
        st.plotly_chart(fig, use_container_width=True)
        st.caption(
            "📌 A diferença de temperatura entre drone e estação é explicada "
            "pelo gradiente adiabático (-0.65°C/100m de altitude)."
        )

    col1, col2 = st.columns(2)

    # ── Umidade ───────────────────────────────────────────────────────────
    with col1:
        st.subheader("💧 Umidade (%)")
        df_hum = df[df["hum_pct"].notna()]
        if not df_hum.empty:
            fig = px.line(df_hum, x="time", y="hum_pct", color="source_id",
                          color_discrete_map={
                              "drone_01": "#3B82F6", "estacao_01": "#10B981"},
                          markers=True)
            fig.update_layout(height=250, margin=dict(t=10, b=10),
                              showlegend=False)
            st.plotly_chart(fig, use_container_width=True)

    # ── Altitude (drone) ──────────────────────────────────────────────────
    with col2:
        st.subheader("✈️ Altitude do Drone (m)")
        df_alt = df[(df["alt_m"].notna()) & (df["source_id"] == "drone_01")]
        if not df_alt.empty:
            fig = px.area(df_alt, x="time", y="alt_m",
                          color_discrete_sequence=["#3B82F6"])
            fig.update_layout(height=250, margin=dict(t=10, b=10))
            st.plotly_chart(fig, use_container_width=True)

    # ── CO₂ e Pressão (estação) ───────────────────────────────────────────
    st.subheader("🏭 CO₂ e Pressão Atmosférica (Estação)")
    df_sta = df[df["source_id"] == "estacao_01"]

    col3, col4 = st.columns(2)
    with col3:
        df_co2 = df_sta[df_sta["co2_ppm"].notna()]
        if not df_co2.empty:
            fig = px.line(df_co2, x="time", y="co2_ppm",
                          color_discrete_sequence=["#F59E0B"],
                          labels={"co2_ppm": "CO₂ (ppm)"}, markers=True)
            fig.update_layout(height=250, margin=dict(t=10, b=10))
            st.plotly_chart(fig, use_container_width=True)

    with col4:
        df_pres = df_sta[df_sta["pressure_hpa"].notna()]
        if not df_pres.empty:
            fig = px.line(df_pres, x="time", y="pressure_hpa",
                          color_discrete_sequence=["#8B5CF6"],
                          labels={"pressure_hpa": "Pressão (hPa)"}, markers=True)
            fig.update_layout(height=250, margin=dict(t=10, b=10))
            st.plotly_chart(fig, use_container_width=True)

    # ── UV Index e Bateria ────────────────────────────────────────────────
    col5, col6 = st.columns(2)
    with col5:
        st.subheader("☀️ Índice UV (Estação)")
        df_uv = df_sta[df_sta["uv_index"].notna()]
        if not df_uv.empty:
            fig = px.area(df_uv, x="time", y="uv_index",
                          color_discrete_sequence=["#F97316"],
                          labels={"uv_index": "UV Index"})
            fig.update_layout(height=230, margin=dict(t=10, b=10))
            st.plotly_chart(fig, use_container_width=True)

    with col6:
        st.subheader("🔋 Bateria do Drone (%)")
        df_bat = df[(df["battery_pct"].notna()) &
                    (df["source_id"] == "drone_01")]
        if not df_bat.empty:
            fig = px.line(df_bat, x="time", y="battery_pct",
                          color_discrete_sequence=["#EF4444"])
            fig.add_hline(y=20, line_dash="dash", line_color="red",
                          annotation_text="Limiar de retorno (20%)")
            fig.update_layout(height=230, margin=dict(t=10, b=10))
            st.plotly_chart(fig, use_container_width=True)

    # ── RSSI ──────────────────────────────────────────────────────────────
    st.subheader("📶 RSSI Wi-Fi (dBm)")
    df_rssi = df[df["rssi_dbm"].notna()]
    if not df_rssi.empty:
        fig = px.line(df_rssi, x="time", y="rssi_dbm", color="source_id",
                      color_discrete_map={
                          "drone_01": "#3B82F6", "estacao_01": "#10B981"},
                      markers=True,
                      labels={"rssi_dbm": "RSSI (dBm)"})
        fig.update_layout(height=250, margin=dict(t=10, b=10))
        st.plotly_chart(fig, use_container_width=True)
        st.caption("Valores mais próximos de 0 indicam sinal mais forte. "
                   "Abaixo de -80 dBm: sinal fraco.")


# ═════════════════════════════════════════════════════════════════════════════
#  PAINEL 3 — LATÊNCIA (resultados dos experimentos)
# ═════════════════════════════════════════════════════════════════════════════

elif painel == "⏱️ Latência":
    st.title("⏱️ Métricas de Latência")
    st.caption(
        "Resultados dos experimentos de latência. "
        "latência_transporte = gateway_ts − sensor_ts | "
        "latência_total = datacenter_ts − sensor_ts"
    )

    col_f1, col_f2 = st.columns(2)
    with col_f1:
        filtro_tipo = st.selectbox(
            "Filtrar por fonte", ["Todos", "DRONE", "STATION"])
    with col_f2:
        st.write(f"Janela: últimos **{janela} min** (ajuste na sidebar)")

    src = None if filtro_tipo == "Todos" else filtro_tipo
    resp = fetch_latency(src, janela)
    data = resp.get("data", [])
    summ = resp.get("summary", {})

    if not data:
        st.info("Sem dados de latência no período selecionado.")
        st.stop()

    df = pd.DataFrame(data)
    df["time"] = pd.to_datetime(df["time"], format='mixed', utc=True)
    df = df.sort_values("time")

    # ── Cards de estatísticas ─────────────────────────────────────────────
    st.subheader("📊 Estatísticas — Latência de Transporte")
    t_stats = summ.get("transport_ms", {})
    if t_stats:
        c1, c2, c3, c4, c5 = st.columns(5)
        c1.metric("Mínimo",  f"{t_stats.get('min',  0):.1f} ms")
        c2.metric("Média",   f"{t_stats.get('mean', 0):.1f} ms")
        c3.metric("Mediana", f"{t_stats.get('median',0):.1f} ms")
        c4.metric("P95",     f"{t_stats.get('p95',  0):.1f} ms")
        c5.metric("Máximo",  f"{t_stats.get('max',  0):.1f} ms")

    # ── Série temporal de latência ────────────────────────────────────────
    st.subheader("📈 Latência de Transporte ao Longo do Tempo")
    df_t = df[df["latency_transport_ms"].notna()]
    if not df_t.empty:
        fig = px.scatter(
            df_t, x="time", y="latency_transport_ms",
            color="source_protocol",
            color_discrete_map={"CoAP": "#3B82F6", "MQTT": "#10B981"},
            symbol="payload_format",
            labels={
                "latency_transport_ms": "Latência Transporte (ms)",
                "time": "",
                "source_protocol": "Protocolo",
                "payload_format": "Formato",
            },
            opacity=0.7,
        )
        # Linha de média móvel
        df_t_sorted = df_t.sort_values("time")
        df_t_sorted["rolling_mean"] = (
            df_t_sorted["latency_transport_ms"].rolling(10, min_periods=1).mean()
        )
        fig.add_scatter(
            x=df_t_sorted["time"],
            y=df_t_sorted["rolling_mean"],
            mode="lines",
            name="Média móvel (10)",
            line=dict(color="orange", width=2),
        )
        fig.update_layout(height=320, margin=dict(t=10, b=10))
        st.plotly_chart(fig, use_container_width=True)

    # ── Boxplot comparativo CoAP vs MQTT ──────────────────────────────────
    st.subheader("📦 Boxplot: CoAP vs MQTT")
    st.caption(
        "Este gráfico é o resultado central do Experimento 1.1 — "
        "comparativo de latência de transporte entre os dois protocolos."
    )
    df_box = df[df["latency_transport_ms"].notna()]
    if not df_box.empty:
        fig = px.box(
            df_box,
            x="source_protocol",
            y="latency_transport_ms",
            color="source_protocol",
            color_discrete_map={"CoAP": "#3B82F6", "MQTT": "#10B981"},
            points="all",
            labels={
                "latency_transport_ms": "Latência Transporte (ms)",
                "source_protocol": "Protocolo",
            },
        )
        fig.update_layout(height=350, margin=dict(t=10, b=10),
                          showlegend=False)
        st.plotly_chart(fig, use_container_width=True)

    # ── Boxplot CBOR vs JSON ──────────────────────────────────────────────
    df_fmt = df[
        df["latency_transport_ms"].notna() &
        df["payload_format"].notna() &
        (df["source_protocol"] == "CoAP")
    ]
    if not df_fmt.empty and df_fmt["payload_format"].nunique() > 1:
        st.subheader("📦 Boxplot: CBOR vs JSON (CoAP)")
        st.caption(
            "Resultado do Experimento 3.2 — impacto do formato de payload "
            "na latência de transporte CoAP."
        )
        fig = px.box(
            df_fmt,
            x="payload_format",
            y="latency_transport_ms",
            color="payload_format",
            color_discrete_map={"CBOR": "#8B5CF6", "JSON": "#F59E0B"},
            points="all",
            labels={
                "latency_transport_ms": "Latência Transporte (ms)",
                "payload_format": "Formato",
            },
        )
        fig.update_layout(height=320, margin=dict(t=10, b=10),
                          showlegend=False)
        st.plotly_chart(fig, use_container_width=True)

    # ── Latência total vs transporte ──────────────────────────────────────
    st.subheader("📊 Latência Total vs Transporte")
    df_both = df[
        df["latency_transport_ms"].notna() &
        df["latency_total_ms"].notna()
    ].copy()
    if not df_both.empty:
        df_both["latency_gateway_http_ms"] = (
            df_both["latency_total_ms"] - df_both["latency_transport_ms"]
        )
        df_melt = df_both[
            ["time", "source_protocol",
             "latency_transport_ms", "latency_gateway_http_ms"]
        ].melt(
            id_vars=["time", "source_protocol"],
            var_name="componente",
            value_name="ms",
        )
        label_map = {
            "latency_transport_ms":    "Transporte (sensor→gateway)",
            "latency_gateway_http_ms": "Gateway→Datacenter (HTTP)",
        }
        df_melt["componente"] = df_melt["componente"].map(label_map)

        fig = px.bar(
            df_melt,
            x="time", y="ms",
            color="componente",
            color_discrete_sequence=["#3B82F6", "#F59E0B"],
            barmode="stack",
            labels={"ms": "Latência (ms)", "time": "", "componente": ""},
        )
        fig.update_layout(height=300, margin=dict(t=10, b=10))
        st.plotly_chart(fig, use_container_width=True)
        st.caption(
            "Gráfico de barras empilhadas — decompõe a latência total "
            "em transporte e processamento gateway→datacenter."
        )


# ═════════════════════════════════════════════════════════════════════════════
#  PAINEL 4 — CONFIABILIDADE
# ═════════════════════════════════════════════════════════════════════════════

elif painel == "📦 Confiabilidade":
    st.title("📦 Confiabilidade da Entrega")
    st.caption(
        "Taxa de entrega calculada via gaps no número de sequência (seq). "
        "Gaps indicam mensagens perdidas entre o sensor e o datacenter."
    )

    delivery = fetch_delivery(janela)
    twins    = fetch_twins()
    drones   = twins.get("drones",   {})
    stations = twins.get("stations", {})

    if not delivery:
        st.info("Sem dados de confiabilidade no período selecionado.")
        st.stop()

    # ── Gauges de taxa de entrega ─────────────────────────────────────────
    st.subheader("📊 Taxa de Entrega por Dispositivo")
    cols = st.columns(len(delivery))

    for i, (device_id, stats) in enumerate(delivery.items()):
        rate     = stats.get("delivery_rate", 0)
        received = stats.get("received", 0)
        lost     = stats.get("lost",     0)
        expected = stats.get("expected", 0)

        with cols[i]:
            fig = go.Figure(go.Indicator(
                mode="gauge+number",
                value=rate,
                title={"text": device_id, "font": {"size": 14}},
                number={"suffix": "%", "font": {"size": 28}},
                gauge={
                    "axis":  {"range": [0, 100]},
                    "bar":   {"color": "#10B981" if rate >= 95 else
                                       "#F59E0B" if rate >= 80 else "#EF4444"},
                    "steps": [
                        {"range": [0,  80], "color": "#FEE2E2"},
                        {"range": [80, 95], "color": "#FEF3C7"},
                        {"range": [95, 100],"color": "#D1FAE5"},
                    ],
                    "threshold": {
                        "line":  {"color": "red", "width": 2},
                        "thickness": 0.75,
                        "value": 95,
                    },
                },
            ))
            fig.update_layout(height=220, margin=dict(t=30, b=10, l=20, r=20))
            st.plotly_chart(fig, use_container_width=True)

            c1, c2, c3 = st.columns(3)
            c1.metric("Recebidas", received)
            c2.metric("Perdidas",  lost,
                      delta=f"-{lost}" if lost > 0 else None,
                      delta_color="inverse")
            c3.metric("Esperadas", expected)

    st.divider()

    # ── Timeline de mensagens perdidas ────────────────────────────────────
    st.subheader("📉 Mensagens Perdidas por Dispositivo (Twin)")
    rows = []
    for did, d in drones.items():
        rows.append({
            "Dispositivo": did,
            "Tipo":        "Drone",
            "Recebidas":   d.get("messages_received", 0),
            "Perdidas":    d.get("messages_lost", 0),
            "Seq atual":   d.get("seq", 0),
        })
    for sid, s in stations.items():
        rows.append({
            "Dispositivo": sid,
            "Tipo":        "Estação",
            "Recebidas":   s.get("messages_received", 0),
            "Perdidas":    s.get("messages_lost", 0),
            "Seq atual":   s.get("seq", 0),
        })

    if rows:
        df_summary = pd.DataFrame(rows)
        df_summary["Taxa (%)"] = (
            df_summary["Recebidas"] /
            (df_summary["Recebidas"] + df_summary["Perdidas"]) * 100
        ).round(2)
        st.dataframe(df_summary, use_container_width=True, hide_index=True)

    st.divider()

    # ── Status online/offline ─────────────────────────────────────────────
    st.subheader("🟢 Status Online/Offline dos Dispositivos")
    status_data = fetch_status()
    devices_status = status_data.get("devices", {})

    all_devices = {}
    for did, d in devices_status.get("drones", {}).items():
        all_devices[did] = {**d, "tipo": "Drone"}
    for sid, s in devices_status.get("stations", {}).items():
        all_devices[sid] = {**s, "tipo": "Estação"}

    if all_devices:
        col_a, col_b = st.columns(2)
        for i, (dev_id, info) in enumerate(all_devices.items()):
            col = col_a if i % 2 == 0 else col_b
            with col:
                with st.container(border=True):
                    online = info.get("online", False)
                    ago    = info.get("last_update_s_ago", 0)
                    tipo   = info.get("tipo", "?")

                    st.markdown(
                        f"{'🟢' if online else '🔴'} **{dev_id}** ({tipo})")
                    st.caption(f"Última mensagem: {ago:.1f}s atrás")

                    if tipo == "Drone":
                        st.markdown(
                            f"Fase: `{info.get('flight_phase','?')}` | "
                            f"Bat: `{info.get('battery_pct','?')}%`"
                        )
                    else:
                        st.markdown(
                            f"CO₂ trend: `{info.get('co2_trend','?')}`"
                        )

                    lost = info.get("messages_lost", 0)
                    if lost > 0:
                        st.warning(f"⚠️ {lost} mensagens perdidas nesta sessão")