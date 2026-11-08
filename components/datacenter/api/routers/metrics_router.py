"""
metrics_router.py — Endpoints de métricas e status.

Endpoints:
  GET  /api/v1/metrics/latency    → latências do período (viewer+)
  GET  /api/v1/metrics/delivery   → taxa de entrega (viewer+)
  GET  /api/v1/status             → resumo do sistema (viewer+)
  POST /api/v1/gateway/metrics    → métricas do gateway (admin)
"""

import statistics as stats_lib
from datetime import datetime, timezone
from typing import Optional

from fastapi import APIRouter, Depends, Query, Request

from api.auth.jwt_handler import UserContext, get_current_user, require_admin
from api.middleware.rate_limiter import limiter

router = APIRouter(tags=["Métricas"])


def get_influx():
    from api.main import influx
    return influx


def get_session_stats():
    from api.main import session_stats
    return session_stats


@router.get("/api/v1/metrics/latency", summary="Latências do período")
def get_latency_metrics(
    source_type: Optional[str] = Query(None, description="DRONE ou STATION"),
    minutes:     int           = Query(60,   description="Janela de tempo em minutos"),
    _user: UserContext         = Depends(get_current_user),
):
    """
    Retorna latências de transporte e fim-a-fim com estatísticas descritivas.
    Requer autenticação (perfil viewer ou admin).
    """
    data = get_influx().query_latency(source_type=source_type, minutes=minutes)

    if not data:
        return {"data": [], "summary": {}}

    transport = [d["latency_transport_ms"] for d in data
                 if d.get("latency_transport_ms") is not None]
    total     = [d["latency_total_ms"] for d in data
                 if d.get("latency_total_ms") is not None]

    def make_stats(values: list) -> dict:
        if not values:
            return {}
        sv = sorted(values)
        n  = len(sv)
        return {
            "count":  n,
            "min":    round(sv[0], 2),
            "max":    round(sv[-1], 2),
            "mean":   round(stats_lib.mean(sv), 2),
            "median": round(stats_lib.median(sv), 2),
            "p95":    round(sv[int(n * 0.95)], 2),
            "p99":    round(sv[int(n * 0.99)], 2),
            "stdev":  round(stats_lib.stdev(sv), 2) if n > 1 else 0,
        }

    return {
        "data":    data,
        "summary": {
            "transport_ms": make_stats(transport),
            "total_ms":     make_stats(total),
        },
    }


@router.get("/api/v1/metrics/delivery", summary="Taxa de entrega por dispositivo")
def get_delivery_metrics(
    minutes:     int       = Query(60, description="Janela de tempo em minutos"),
    _user: UserContext     = Depends(get_current_user),
):
    """
    Taxa de entrega calculada via gaps no número de sequência.
    Requer autenticação (perfil viewer ou admin).
    """
    return get_influx().query_delivery_rate(minutes=minutes)


@router.get("/api/v1/status", summary="Resumo do sistema")
async def get_status(_user: UserContext = Depends(get_current_user)):
    """
    Health check do sistema — estado dos dispositivos e contadores de sessão.
    Requer autenticação (perfil viewer ou admin).
    """
    from api.main import twin_store
    return {
        "session": get_session_stats(),
        "devices": await twin_store.get_summary(),
    }


@router.post("/api/v1/gateway/metrics", status_code=200,
             summary="Recebe métricas do gateway")
@limiter.limit("10/minute")
async def receive_gateway_metrics(
    request: Request,
    _user: UserContext = Depends(require_admin),
):
    """
    Recebe métricas internas do gateway (CoAP, MQTT, pipeline, HTTP forwarder).
    Requer autenticação com perfil **admin**.
    Rate limit: 10 requisições/minuto por IP.
    """
    metrics = await request.json()
    get_session_stats()["gateway_metrics"] = {
        **metrics,
        "received_at": datetime.now(timezone.utc).isoformat(),
    }
    return {"status": "ok"}
