"""
test_models.py — Testes dos modelos Pydantic

Valida que NormalizedData aceita dados válidos e rejeita inválidos.
Sem rede necessária.

COMO EXECUTAR:
  pytest tests/test_models.py -v
"""

import pytest
from pydantic import ValidationError

from api.models import NormalizedData


def valid_drone(**kwargs) -> dict:
    base = dict(
        gateway_id      = "gateway_01",
        source_id       = "drone_01",
        source_type     = "DRONE",
        source_protocol = "CoAP",
        payload_format  = "CBOR",
        sensor_ts       = "2026-05-21T10:00:00.000Z",
        gateway_ts      = "2026-05-21T10:00:00.150Z",
        lat             = -5.7923,
        lon             = -35.2128,
        temp_c          = 29.5,
        hum_pct         = 68.0,
        seq             = 1,
    )
    base.update(kwargs)
    return base


# ── NormalizedData válido ─────────────────────────────────────────────────────

def test_drone_valido_aceito():
    data = NormalizedData(**valid_drone())
    assert data.source_id   == "drone_01"
    assert data.source_type == "DRONE"


def test_station_valido_aceito():
    data = NormalizedData(
        gateway_id      = "gateway_01",
        source_id       = "estacao_01",
        source_type     = "STATION",
        source_protocol = "MQTT",
        sensor_ts       = "2026-05-21T10:00:05.000Z",
        gateway_ts      = "2026-05-21T10:00:05.080Z",
        lat             = -5.7901,
        lon             = -35.2098,
        temp_c          = 29.8,
        hum_pct         = 71.3,
        seq             = 87,
    )
    assert data.source_type     == "STATION"
    assert data.source_protocol == "MQTT"


def test_campos_opcionais_none():
    """Campos exclusivos do drone são None para estação e vice-versa."""
    data = NormalizedData(**valid_drone())
    # Campos da estação devem ser None no drone
    assert data.pressure_hpa is None
    assert data.co2_ppm      is None
    assert data.uv_index     is None


# ── Timestamps ────────────────────────────────────────────────────────────────

def test_timestamp_invalido_rejeitado():
    with pytest.raises(ValidationError):
        NormalizedData(**valid_drone(sensor_ts="nao-e-um-timestamp"))


def test_latencia_transporte_calculada():
    data = NormalizedData(**valid_drone(
        sensor_ts  = "2026-05-21T10:00:00.000Z",
        gateway_ts = "2026-05-21T10:00:00.150Z",
    ))
    latency = data.transport_latency_ms()
    assert abs(latency - 150.0) < 1.0


def test_latencia_transporte_pequena():
    data = NormalizedData(**valid_drone(
        sensor_ts  = "2026-05-21T10:00:00.000Z",
        gateway_ts = "2026-05-21T10:00:00.020Z",
    ))
    assert data.transport_latency_ms() < 30.0


# ── Source type e protocol ────────────────────────────────────────────────────

def test_source_type_invalido_rejeitado():
    with pytest.raises(ValidationError):
        NormalizedData(**valid_drone(source_type="SENSOR"))


def test_source_protocol_invalido_rejeitado():
    with pytest.raises(ValidationError):
        NormalizedData(**valid_drone(source_protocol="HTTP"))


def test_payload_format_invalido_rejeitado():
    with pytest.raises(ValidationError):
        NormalizedData(**valid_drone(payload_format="XML"))


# ── Campos numéricos opcionais ────────────────────────────────────────────────

def test_campos_drone_opcionais_aceitos():
    """Drone pode ter todos os campos de voo preenchidos."""
    data = NormalizedData(**valid_drone(
        alt_m          = 80.0,
        heading_deg    = 127.0,
        velocity_ms    = 11.2,
        battery_pct    = 74,
        battery_ok     = True,
        flight_phase   = "MISSION",
        waypoint_index = 1,
    ))
    assert data.alt_m       == 80.0
    assert data.battery_pct == 74
    assert data.flight_phase == "MISSION"