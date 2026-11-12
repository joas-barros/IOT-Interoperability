"""
ml_router.py — Endpoints do módulo de Machine Learning.

Endpoints:
  POST /api/v1/ml/predict/battery  → predição avulsa de tempo restante (viewer+)
  GET  /api/v1/ml/status           → modo atual do predictor (viewer+)
  POST /api/v1/ml/reload           → recarrega modelo do disco sem restart (admin)

Uso principal do POST /predict/battery:
  - Validação e teste do modelo treinado
  - Comparação manual entre RF e método linear

O fluxo em produção é transparente: o TwinStore chama o predictor
internamente em cada mensagem, e o resultado aparece em
GET /api/v1/twins/{device_id} como estimated_return_min.
"""

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from typing import Optional

from api.auth.jwt_handler import UserContext, require_admin, get_current_user

router = APIRouter(prefix="/api/v1/ml", tags=["Machine Learning"])


def _get_predictor():
    from api.main import predictor
    return predictor


# ─────────────────────────────────────────────────────────────────────────────
#  Schema de entrada para predição avulsa
# ─────────────────────────────────────────────────────────────────────────────

class PredictRequest(BaseModel):
    """
    Payload para predição avulsa de tempo de voo restante.
    Espelha os campos relevantes do NormalizedData — útil para testes
    e para a validação comparativa RF vs linear do TCC.
    """
    flight_phase:     str            = Field(...,  example="RETURN",
                                             description="Fase de voo: IDLE, TAKEOFF, MISSION, HOVER, RETURN, LANDING")
    battery_pct:      float          = Field(...,  ge=0, le=100, example=45.0,
                                             description="Bateria atual (%)")
    drain_rate_pct_s: Optional[float]= Field(None, ge=0, example=0.055,
                                             description="Taxa de consumo efetiva (%/s) — do campo 'dr' do payload. Se omitido, usa taxa base da fase.")
    alt_m:            Optional[float]= Field(None, ge=0,   example=10.0)
    heading_deg:      Optional[float]= Field(None, ge=0, le=360, example=90.0)
    velocity_ms:      Optional[float]= Field(None, ge=0,   example=12.0)
    temp_c:           Optional[float]= Field(None,         example=30.5)
    hum_pct:          Optional[float]= Field(None, ge=0, le=100, example=70.0)
    rssi_dbm:         Optional[int]  = Field(None,         example=-65)


class PredictResponse(BaseModel):
    estimated_return_min: float = Field(..., description="Tempo estimado restante até pouso (minutos)")
    mode:                 str   = Field(..., description="'rf' se predição via Random Forest, 'linear' se fallback")
    drain_rate_used:      float = Field(..., description="Taxa de consumo usada na predição (%/s)")


# ─────────────────────────────────────────────────────────────────────────────
#  Endpoints
# ─────────────────────────────────────────────────────────────────────────────

@router.post(
    "/predict/battery",
    response_model=PredictResponse,
    summary="Predição avulsa de tempo de voo restante",
)
async def predict_battery(
    body: PredictRequest,
    _user: UserContext = Depends(get_current_user),
):
    """
    Prediz o tempo de voo restante (minutos) a partir do estado atual do drone.

    Usa o modelo Random Forest se treinado, ou o método linear como fallback.
    Ideal para validação comparativa durante o TCC.
    """
    from api.models import NormalizedData
    from datetime import datetime, timezone

    predictor = _get_predictor()

    # Monta um NormalizedData mínimo com os campos da requisição
    now_iso = datetime.now(timezone.utc).isoformat()
    data = NormalizedData(
        gateway_id      = "manual_request",
        source_id       = "test_drone",
        source_type     = "DRONE",
        source_protocol = "CoAP",
        sensor_ts       = now_iso,
        gateway_ts      = now_iso,
        flight_phase    = body.flight_phase,
        battery_pct     = body.battery_pct,
        drain_rate_pct_s= body.drain_rate_pct_s,
        alt_m           = body.alt_m,
        heading_deg     = body.heading_deg,
        velocity_ms     = body.velocity_ms,
        temp_c          = body.temp_c,
        hum_pct         = body.hum_pct,
        rssi_dbm        = body.rssi_dbm,
    )

    result = predictor.predict(data)

    # Drain rate efetivo usado (para transparência na resposta)
    from ml.predictor import BATTERY_DRAIN_PER_S
    dr_used = body.drain_rate_pct_s or BATTERY_DRAIN_PER_S.get(
        body.flight_phase.upper(), 0.05
    )

    return PredictResponse(
        estimated_return_min=result,
        mode=predictor.mode,
        drain_rate_used=round(dr_used, 5),
    )


@router.get(
    "/status",
    summary="Estado atual do módulo ML",
)
async def ml_status(
    _user: UserContext = Depends(get_current_user),
):
    """
    Retorna informações sobre o predictor:
    modo atual (rf/linear), número de árvores, caminho do modelo, etc.
    """
    predictor = _get_predictor()
    return predictor.status()


@router.post(
    "/reload",
    summary="Recarrega o modelo RF do disco (admin)",
)
async def reload_model(
    _user: UserContext = Depends(require_admin),
):
    """
    Recarrega o modelo pickle sem reiniciar o servidor.
    Use após executar ml/train_model.py com novos dados.
    Requer perfil admin.
    """
    predictor = _get_predictor()
    predictor.reload()
    return {
        "message": "Modelo recarregado.",
        "mode": predictor.mode,
        "ready": predictor.ready,
    }
