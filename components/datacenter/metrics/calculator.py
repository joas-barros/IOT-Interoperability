"""
calculator.py — Cálculos estatísticos para os experimentos.

Centraliza toda a lógica de análise que não pertence nem ao FastAPI
(ingestão) nem ao Streamlit (visualização), mas que os dois consomem.
"""

from __future__ import annotations

import statistics
from dataclasses import dataclass, field
from typing import Optional


# ─────────────────────────────────────────────────────────────────────────────
#  Estruturas de resultado
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class LatencyStats:
    """Estatísticas descritivas de latência para um conjunto de amostras."""
    count:  int   = 0
    min_ms: float = 0.0
    max_ms: float = 0.0
    mean_ms:   float = 0.0
    median_ms: float = 0.0
    p95_ms:    float = 0.0
    p99_ms:    float = 0.0
    stdev_ms:  float = 0.0

    def to_dict(self) -> dict:
        return {
            "count":     self.count,
            "min_ms":    round(self.min_ms,    2),
            "max_ms":    round(self.max_ms,    2),
            "mean_ms":   round(self.mean_ms,   2),
            "median_ms": round(self.median_ms, 2),
            "p95_ms":    round(self.p95_ms,    2),
            "p99_ms":    round(self.p99_ms,    2),
            "stdev_ms":  round(self.stdev_ms,  2),
        }

@dataclass
class DeliveryStats:
    """Estatísticas de taxa de entrega para um dispositivo."""
    device_id:     str
    source_protocol: str
    received:      int   = 0
    lost:          int   = 0
    expected:      int   = 0
    delivery_rate: float = 0.0

    def to_dict(self) -> dict:
        return {
            "device_id":       self.device_id,
            "source_protocol": self.source_protocol,
            "received":        self.received,
            "lost":            self.lost,
            "expected":        self.expected,
            "delivery_rate":   round(self.delivery_rate, 2),
        }

@dataclass
class ExperimentSummary:
    """
    Resumo completo de um período de experimento.
    Agrupa latência e entrega por protocolo e formato de payload.
    """
    period_minutes:   int
    total_messages:   int = 0

    # Latência de transporte por protocolo
    coap_transport:  Optional[LatencyStats] = None
    mqtt_transport:  Optional[LatencyStats] = None

    # Latência de transporte por formato (só CoAP)
    cbor_transport:  Optional[LatencyStats] = None
    json_transport:  Optional[LatencyStats] = None

    # Latência total (sensor → datacenter)
    coap_total:  Optional[LatencyStats] = None
    mqtt_total:  Optional[LatencyStats] = None

    # Entrega
    delivery: list[DeliveryStats] = field(default_factory=list)

    def to_dict(self) -> dict:
        def s(obj): return obj.to_dict() if obj else {}
        return {
            "period_minutes": self.period_minutes,
            "total_messages": self.total_messages,
            "latency": {
                "coap_transport": s(self.coap_transport),
                "mqtt_transport": s(self.mqtt_transport),
                "cbor_transport": s(self.cbor_transport),
                "json_transport": s(self.json_transport),
                "coap_total":     s(self.coap_total),
                "mqtt_total":     s(self.mqtt_total),
            },
            "delivery": [d.to_dict() for d in self.delivery],
        }