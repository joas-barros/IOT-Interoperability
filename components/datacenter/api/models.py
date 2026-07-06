"""
models.py — Modelos Pydantic do Datacenter IoT

Três categorias de modelos:
  1. NormalizedData   — payload recebido do gateway via HTTP POST
  2. DroneTwin        — estado atual do drone (calculado pelo TwinStore)
  3. StationTwin      — estado atual da estação (calculado pelo TwinStore)
"""

from __future__ import annotations
from datetime import datetime, timezone
from typing import Optional, Literal
from pydantic import BaseModel, field_validator

# ─────────────────────────────────────────────────────────────────────────────
#  1. NormalizedData — espelho do NormalizedData.java do gateway
# ─────────────────────────────────────────────────────────────────────────────

class NormalizedData(BaseModel):
    """
    Payload unificado recebido do gateway via POST /ingestao.
    Campos ausentes para uma fonte específica chegam como null/None.
    """

    # ── Metadados do gateway ──────────────────────────────────────────────
    gateway_id: str
    source_id: str
    source_type:      Literal["DRONE", "STATION"]
    source_protocol: Literal["CoAP", "MQTT"]
    payload_format: Optional[Literal["CBOR", "JSON"]] = None

    # ── Timestamps ────────────────────────────────────────────────────────
    sensor_ts:   str    # ISO 8601 UTC gerado no dispositivo
    gateway_ts:  str    # ISO 8601 UTC adicionado pelo gateway

    # ── Position ───────────────────────────────────────────────────────────
    lat: Optional[float] = None
    lon: Optional[float] = None
    alt_m: Optional[float] = None
    heading_deg: Optional[float] = None
    velocity_ms: Optional[float] = None

    # ── Ambiente comuns ───────────────────────────────────────────────────
    temp_c:    Optional[float] = None
    hum_pct:   Optional[float] = None

    # ── Ambiente exclusivos da estação ────────────────────────────────────
    pressure_hpa: Optional[float] = None
    co2_ppm: Optional[float] = None
    uv_index: Optional[float] = None

    # ── Sistema ───────────────────────────────────────────────────────────
    battery_pct: Optional[float] = None
    battery_ok: Optional[bool] = None
    rssi_dbm: Optional[int] = None
    seq: Optional[int] = None

    # ── Voo (exclusivos do drone) ─────────────────────────────────────────
    flight_phase:    Optional[str] = None   # null para estação
    waypoint_index:  Optional[int] = None   # null para estação

    @field_validator("sensor_ts", "gateway_ts", mode="before")
    @classmethod
    def parse_timestamp(cls, v: str) -> str:
        """Valida que o timestamp é ISO 8601 parseável."""
        try:
            datetime.fromisoformat(v.replace("Z", "+00:00"))
        except ValueError:
            raise ValueError(f"Timestamp inválido: {v}")
        return v

    def sensor_datetime(self) -> datetime:
        return datetime.fromisoformat(self.sensor_ts.replace("Z", "+00:00"))

    def gateway_datetime(self) -> datetime:
        return datetime.fromisoformat(self.gateway_ts.replace("Z", "+00:00"))

    def transport_latency_ms(self) -> float:
        """Latência de transporte: gateway_ts - sensor_ts (ms)."""
        delta = self.gateway_datetime() - self.sensor_datetime()
        return delta.total_seconds() * 1000

# ─────────────────────────────────────────────────────────────────────────────
#  2. DroneTwin — estado atual do drone
# ─────────────────────────────────────────────────────────────────────────────

class DroneTwin(BaseModel):
    """
    Representação virtual em tempo real do drone simulado (ESP32).
    Atualizado a cada mensagem CoAP recebida pelo gateway.
    """

    # ── Identidade ────────────────────────────────────────────────────────
    device_id: str
    last_update: datetime
    source_protocol: str = "CoAP"
    payload_format: str = "CBOR"

    # ── Estado de voo ─────────────────────────────────────────────────────
    flight_phase: str = "UNKNOWN"
    waypoint_index: int = 0
    flight_duration_s: int = 0

    # ── Posição atual ─────────────────────────────────────────────────────
    lat: float = 0.0
    lon: float = 0.0
    alt_m: float = 0.0
    heading_deg: float = 0.0
    velocity_ms: float = 0.0

    # ── Ambiente ──────────────────────────────────────────────────────────
    temp_c: float = 0.0
    hum_pct: float = 0.0

    # ── Sistema ───────────────────────────────────────────────────────────
    battery_pct: int = 0
    battery_ok: bool = False
    rssi_dbm: int = 0
    seq: int = 0

    # ── Métricas de sessão ────────────────────────────────────────────────
    messages_received: int = 0
    messages_lost:     int = 0   # gaps no campo seq

    # ── Inferências calculadas pelo twin ──────────────────────────────────
    is_online: bool = False
    estimated_return_min:   float = 0.0   # estimativa baseada em bateria
    coverage_radius_m:      float = 0.0   # alcance máximo com bateria atual
    anomaly_detected:       bool  = False # temperatura vs altitude esperada

    @property
    def delivery_rate_pct(self) -> float:
        """Taxa de entrega calculada a partir do seq."""
        total = self.messages_received + self.messages_lost
        return (self.messages_received / total * 100) if total > 0 else 0.0

    @property
    def phase_emoji(self) -> str:
        """Emoji representando a fase de voo — para o dashboard."""
        return {
            "IDLE":     "🟡",
            "TAKEOFF":  "🟢",
            "MISSION":  "🔵",
            "HOVER":    "🟣",
            "RETURN":   "🟠",
            "LANDING":  "🔴",
            "UNKNOWN":  "⚪",
        }.get(self.flight_phase, "⚪")


# ─────────────────────────────────────────────────────────────────────────────
#  3. StationTwin — estado atual da estação
# ─────────────────────────────────────────────────────────────────────────────

class StationTwin(BaseModel):
    """
    Representação virtual em tempo real da estação estacionária (RPi A).
    Atualizado a cada mensagem MQTT recebida pelo gateway.
    """

    # ── Identidade ────────────────────────────────────────────────────────
    device_id: str
    last_update: datetime
    source_protocol: str = "MQTT"

    # ── Posição (fixa) ─────────────────────────────────────────────────────
    lat: float = 0.0
    lon: float = 0.0

    # ── Ambiente ──────────────────────────────────────────────────────────
    temp_c: float = 0.0
    hum_pct: float = 0.0
    pressure_hpa: float = 0.0
    co2_ppm: float = 0.0
    uv_index: float = 0.0

    # ── Sistema ───────────────────────────────────────────────────────────
    rssi_dbm: int = 0
    seq: int = 0

    # ── Métricas de sessão ────────────────────────────────────────────────
    messages_received: int = 0
    messages_lost:     int = 0

    # ── Inferências calculadas pelo twin ──────────────────────────────────
    is_online:    bool = False
    co2_trend:    Literal["RISING", "STABLE", "FALLING"] = "STABLE"
    uv_period:    Literal["DAY", "DUSK", "NIGHT"]        = "NIGHT"
    _co2_history: list[float] = []   # últimas 10 leituras (não serializado)

    model_config = {"arbitrary_types_allowed": True}

    @property
    def delivery_rate_pct(self) -> float:
        total = self.messages_received + self.messages_lost
        return (self.messages_received / total * 100) if total > 0 else 0.0

    @property
    def online_emoji(self) -> str:
        return "🟢" if self.is_online else "🔴"