"""
main.py — FastAPI: servidor HTTP do Datacenter IoT.

Endpoints:
  POST /ingest              ← recebe NormalizedData do gateway
  GET  /twins                 ← retorna todos os Digital Twins (para o Streamlit)
  GET  /twins/{device_id}     ← retorna um twin específico
  GET  /status                ← resumo do sistema
  GET  /metrics/latency       ← latências do período
  GET  /metrics/delivery      ← taxa de entrega por dispositivo
  GET  /data                  ← dados históricos de sensores
  POST /gateway/metrics       ← recebe métricas periódicas do gateway
"""

import os
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Optional

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware

from api.influx import InfluxWriter
from api.models import NormalizedData
from api.twin_store import TwinStore

load_dotenv()

# ── Singleton: TwinStore e InfluxWriter ───────────────────────────────────────
twin_store = TwinStore()
influx:    Optional[InfluxWriter] = None

# Contadores de sessão
session_stats = {
    "total_received":  0,
    "total_drones":    0,
    "total_stations":  0,
    "total_errors":    0,
    "started_at":      datetime.now(timezone.utc).isoformat(),
    "gateway_metrics": {},
}

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Inicializa e encerra recursos ao subir/descer o servidor."""
    global influx
    print("=" * 50)
    print("  Datacenter IoT — Iniciando")
    print(f"  Porta: {os.getenv('API_PORT', '8000')}")
    print(f"  InfluxDB: {os.getenv('INFLUX_URL', 'http://localhost:8086')}")
    print("=" * 50)

    influx = InfluxWriter()
    yield

    if influx:
        influx.close()
    print("Datacenter encerrado.")


app = FastAPI(
    title="Datacenter IoT Interoperável",
    description="Ingestão, persistência e análise de dados do gateway IoT",
    version="1.0.0",
    lifespan=lifespan
)

# CORS — permite o Streamlit (localhost:8501) acessar a API (localhost:8000)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ─────────────────────────────────────────────────────────────────────────────
#  POST /ingest — recebe dados do gateway
# ─────────────────────────────────────────────────────────────────────────────

@app.post("/ingest", status_code=200)
async def ingest(data: NormalizedData):
    """
    Recebe NormalizedData do gateway, persiste no InfluxDB
    e atualiza o Digital Twin do dispositivo.
    """

    datacenter_ts = datetime.now(timezone.utc)

    try:
        # 1. Atualiza o Digital Twin (em memória — rápido)
        await twin_store.update(data)

        # 2. Persiste no InfluxDB (com latências calculadas)
        influx.write(data, datacenter_ts)

        # 3. Atualiza contadores de sessão
        session_stats["total_received"] += 1
        if data.source_type == "DRONE":
            session_stats["total_drones"] += 1
        else:
            session_stats["total_stations"] += 1
        
        transport_ms = data.transport_latency_ms()
        total_ms     = (datacenter_ts - data.sensor_datetime()).total_seconds() * 1000

        print(
            f"[INGEST] {data.source_type:<8} | {data.source_id:<12} | "
            f"seq={data.seq:<5} | "
            f"fmt={data.payload_format or 'JSON':<4} | "
            f"transport={transport_ms:.0f}ms | total={total_ms:.0f}ms"
        )

        return {
            "status": "ok",
            "datacenter_ts": datacenter_ts.isoformat(),
            "transport_ms": round(transport_ms, 2),
            "total_ms": round(total_ms, 2),
        }
    except Exception as e:
        session_stats["total_errors"] += 1
        print(f"[ERROR] {e}")
        raise HTTPException(status_code=500, detail=str(e))


# ─────────────────────────────────────────────────────────────────────────────
#  GET /twins — retorna todos os Digital Twins
# ─────────────────────────────────────────────────────────────────────────────

@app.get("/twins")
async def get_twins():
    """
    Retorna o estado atual de todos os Digital Twins.
    Consumido pelo Streamlit para o painel de estado atual.
    """
    return await twin_store.get_all()

@app.get("/twins/{device_id}")
async def get_twin(device_id: str):
    """Retorna o twin de um dispositivo específico."""
    drone   = await twin_store.get_drone(device_id)
    station = await twin_store.get_station(device_id)
    twin    = drone or station

    if not twin:
        raise HTTPException(
            status_code=404,
            detail=f"Dispositivo '{device_id}' não encontrado."
        )
    return twin.model_dump(mode="json")


# ─────────────────────────────────────────────────────────────────────────────
#  GET /status — resumo do sistema
# ─────────────────────────────────────────────────────────────────────────────

@app.get("/status")
async def get_status():
    """Resumo rápido do estado do sistema — health check."""
    return {
        "session":  session_stats,
        "devices":  await twin_store.get_summary(),
    }


# ─────────────────────────────────────────────────────────────────────────────
#  GET /metrics/latency
# ─────────────────────────────────────────────────────────────────────────────

@app.get("/metrics/latency")
def get_latency_metrics(
    source_type: Optional[str] = Query(None, description="DRONE ou STATION"),
    minutes:     int           = Query(60,   description="Janela de tempo em minutos"),
):
    """
    Latências do período — dados para os gráficos do artigo.
    Retorna séries temporais de latency_transport_ms e latency_total_ms.
    """
    data = influx.query_latency(source_type=source_type, minutes=minutes)

    if not data:
        return {"data": [], "summary": {}}

    transport_values = [d["latency_transport_ms"] for d in data
                        if d["latency_transport_ms"] is not None]
    total_values     = [d["latency_total_ms"] for d in data
                        if d["latency_total_ms"] is not None]

    def stats(values: list[float]) -> dict:
        if not values:
            return {}
        import statistics
        sorted_v = sorted(values)
        n        = len(sorted_v)
        return {
            "count":  n,
            "min":    round(min(sorted_v), 2),
            "max":    round(max(sorted_v), 2),
            "mean":   round(statistics.mean(sorted_v), 2),
            "median": round(statistics.median(sorted_v), 2),
            "p95":    round(sorted_v[int(n * 0.95)], 2),
            "p99":    round(sorted_v[int(n * 0.99)], 2),
            "stdev":  round(statistics.stdev(sorted_v), 2) if n > 1 else 0,
        }

    return {
        "data":    data,
        "summary": {
            "transport_ms": stats(transport_values),
            "total_ms":     stats(total_values),
        },
    }


# ─────────────────────────────────────────────────────────────────────────────
#  GET /metrics/delivery
# ─────────────────────────────────────────────────────────────────────────────

@app.get("/metrics/delivery")
def get_delivery_metrics(
    minutes: int = Query(60, description="Janela de tempo em minutos"),
):
    """
    Taxa de entrega calculada via gaps no número de sequência.
    Dados para o Experimento 2.1.
    """
    return influx.query_delivery_rate(minutes=minutes)


# ─────────────────────────────────────────────────────────────────────────────
#  GET /data — dados históricos de sensores
# ─────────────────────────────────────────────────────────────────────────────

@app.get("/data")
def get_sensor_data(
    source_type: Optional[str] = Query(None, description="DRONE ou STATION"),
    minutes:     int           = Query(60,   description="Janela de tempo em minutos"),
):
    """
    Dados históricos de sensores do período recente.
    Retorna dados de temperatura, umidade, etc. para os gráficos.
    """
    return influx.query_sensor_data(source_type=source_type, minutes=minutes)


# ─────────────────────────────────────────────────────────────────────────────
#  POST /gateway/metrics — recebe métricas periódicas do gateway
# ─────────────────────────────────────────────────────────────────────────────

@app.post("/gateway/metrics", status_code=200)
async def receive_gateway_metrics(metrics: dict):
    """
    Recebe métricas internas do gateway (CoAP, MQTT, pipeline, HTTP forwarder).
    Armazenadas em memória para exibição no dashboard.
    """
    session_stats["gateway_metrics"] = {
        **metrics,
        "received_at": datetime.now(timezone.utc).isoformat(),
    }
    return {"status": "ok"}


# ─────────────────────────────────────────────────────────────────────────────
#  Execução direta
# ─────────────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "api.main:app",
        host=os.getenv("API_HOST", "0.0.0.0"),
        port=int(os.getenv("API_PORT", "8000")),
        reload=True,
    )