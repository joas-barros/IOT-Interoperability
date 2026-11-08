"""
main.py — FastAPI: datacenter IoT com camada de serviço versionada.

  - Rotas versionadas sob /api/v1/
  - Autenticação JWT (perfis admin e viewer)
  - Rate limiting via slowapi
  - CORS para o Streamlit dashboard

Endpoints públicos:
  POST /api/v1/auth/token     ← obtém Bearer token

Endpoints autenticados (viewer ou admin):
  GET  /api/v1/twins
  GET  /api/v1/twins/{device_id}
  GET  /api/v1/metrics/latency
  GET  /api/v1/metrics/delivery
  GET  /api/v1/status
  GET  /api/v1/data

Endpoints admin:
  POST /api/v1/ingest
  POST /api/v1/gateway/metrics
"""

import asyncio
import os
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Optional

from dotenv import load_dotenv
from fastapi import APIRouter, FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded

from api.influx import InfluxWriter
from api.twin_store import TwinStore
from api.middleware.rate_limiter import limiter

from api.routers.auth_router    import router as auth_router
from api.routers.twins_router   import router as twins_router
from api.routers.data_router    import router as data_router
from api.routers.metrics_router import router as metrics_router

load_dotenv()

# ── Singleton: TwinStore e InfluxWriter ───────────────────────────────────────
twin_store = TwinStore()
influx:    Optional[InfluxWriter] = None
stats_lock: Optional[asyncio.Lock] = None

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
    global influx, stats_lock

    stats_lock = asyncio.Lock()

    print("=" * 55)
    print("  Datacenter IoT v2 — Camada de Serviço")
    print(f"  Porta      : {os.getenv('API_PORT', '8000')}")
    print(f"  InfluxDB   : {os.getenv('INFLUX_URL', 'http://localhost:8086')}")
    print(f"  Redis      : {os.getenv('REDIS_URL', 'redis://localhost:6379')}")
    print(f"  JWT expire : {os.getenv('JWT_EXPIRE_MINUTES', '60')} min")
    print("=" * 55)

    influx = InfluxWriter()
    yield

    if influx:
        influx.close()
    print("Datacenter encerrado.")


app = FastAPI(
    title="Datacenter IoT Interoperável",
    description = (
        "API versionada para ingestão, persistência e consulta de dados "
        "do gateway IoT heterogêneo (TCC — UFERSA).\n\n"
        "**Autenticação**: Bearer JWT. Obtenha um token em `POST /api/v1/auth/token`.\n\n"
        "**Perfis**: `admin` (leitura + escrita) · `viewer` (somente leitura)."
    ),
    version="2.0.0",
    lifespan=lifespan,
    docs_url    = "/docs",
    redoc_url   = "/redoc",
)

# Rate limiter — registra o handler de 429 Too Many Requests
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

# CORS — permite o Streamlit (localhost:8501) acessar a API (localhost:8000)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── Routers ────────────────────────────────────────────────────────────────────
app.include_router(auth_router)     # POST /api/v1/auth/token      (público)
app.include_router(twins_router)    # GET  /api/v1/twins/**         (viewer+)
app.include_router(data_router)     # GET  /api/v1/data             (viewer+)
                                    # POST /api/v1/ingest            (admin)
app.include_router(metrics_router)  # GET  /api/v1/metrics/**       (viewer+)
                                    # GET  /api/v1/status            (viewer+)
                                    # POST /api/v1/gateway/metrics   (admin)

# ── Health check público (sem autenticação) ────────────────────────────────────
@app.get("/health", tags=["Sistema"], include_in_schema=False)
def health():
    """Verifica se o servidor está no ar (sem autenticação)."""
    return {"status": "ok", "version": "2.0.0"}


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