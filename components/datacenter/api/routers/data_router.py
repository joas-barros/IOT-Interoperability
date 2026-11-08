"""
data_router.py — Endpoints de dados históricos e ingestão.

Endpoints:
  GET  /api/v1/data    → dados históricos de sensores (viewer + admin)
  POST /api/v1/ingest  → ingestão de NormalizedData (admin)
"""

from datetime import datetime, timezone
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query, Request

from api.auth.jwt_handler import UserContext, get_current_user, require_admin
from api.middleware.rate_limiter import limiter

router = APIRouter(tags=["Dados"])

def get_influx():
    from api.main import influx
    return influx


def get_twin_store():
    from api.main import twin_store
    return twin_store


def get_session_stats():
    from api.main import session_stats
    return session_stats


@router.get("/api/v1/data", summary="Dados históricos de sensores")
def get_sensor_data(
    source_type: Optional[str] = Query(None, description="DRONE ou STATION"),
    minutes:     int           = Query(60,   description="Janela de tempo em minutos"),
    _user: UserContext         = Depends(get_current_user),
):
    """
    Retorna dados históricos de sensores do período recente.
    Requer autenticação (perfil viewer ou admin).
    """
    return get_influx().query_sensor_data(source_type=source_type, minutes=minutes)


@router.post("/api/v1/ingest", status_code=200, summary="Ingestão de dados normalizados")
@limiter.limit("30/minute")
async def ingest(
    request: Request,
    _user: UserContext = Depends(require_admin),
):
    """
    Recebe NormalizedData do gateway, persiste no InfluxDB
    e atualiza o Digital Twin do dispositivo.
    Requer autenticação com perfil **admin**.
    Rate limit: 30 requisições/minuto por IP.
    """
    from api.models import NormalizedData

    body          = await request.json()
    data          = NormalizedData(**body)
    datacenter_ts = datetime.now(timezone.utc)
    stats         = get_session_stats()

    try:
        await get_twin_store().update(data)
        get_influx().write(data, datacenter_ts)

        stats["total_received"] += 1
        if data.source_type == "DRONE":
            stats["total_drones"] += 1
        else:
            stats["total_stations"] += 1

        transport_ms = data.transport_latency_ms()
        total_ms     = (datacenter_ts - data.sensor_datetime()).total_seconds() * 1000

        print(
            f"[INGEST] {data.source_type:<8} | {data.source_id:<12} | "
            f"seq={data.seq:<5} | fmt={data.payload_format or 'JSON':<4} | "
            f"transport={transport_ms:.0f}ms | total={total_ms:.0f}ms"
        )

        return {
            "status":        "ok",
            "datacenter_ts": datacenter_ts.isoformat(),
            "transport_ms":  round(transport_ms, 2),
            "total_ms":      round(total_ms, 2),
        }

    except Exception as e:
        stats["total_errors"] += 1
        print(f"[ERROR] {e}")
        raise HTTPException(status_code=500, detail=str(e))