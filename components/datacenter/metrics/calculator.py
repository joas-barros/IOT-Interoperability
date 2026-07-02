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


# ─────────────────────────────────────────────────────────────────────────────
#  Funções de cálculo
# ─────────────────────────────────────────────────────────────────────────────

def compute_latency_stats(values: list[float]) -> Optional[LatencyStats]:
    """
    Calcula estatísticas descritivas completas para uma lista de latências.
    Retorna None se a lista estiver vazia.
    """
    if not values:
        return None
    
    n = len(values)
    sorted_v = sorted(values)

    return LatencyStats(
        count= n,
        min_ms = sorted_v[0],
        max_ms = sorted_v[-1],
        mean_ms = statistics.mean(sorted_v),
        median_ms = statistics.median(sorted_v),
        p95_ms    = sorted_v[max(0, int(n * 0.95) - 1)],
        p99_ms    = sorted_v[max(0, int(n * 0.99) - 1)],
        stdev_ms  = statistics.stdev(sorted_v) if n > 1 else 0.0
    )

def compute_delivery_stats(
        seqs_dy_device: dict[str, list[int]],
        protocol_by_delivery: dict[str, str],
) -> list[DeliveryStats]:
    """
    Calcula taxa de entrega por dispositivo a partir dos números de sequência.

    Detecta gaps: se seq vai de 10 para 13, as mensagens 11 e 12 foram perdidas.

    Args:
        seqs_by_device: {device_id: [seq1, seq2, ...]} ordenados
        protocol_by_device: {device_id: "CoAP" ou "MQTT"}
    """

    results = []

    for device_id, seqs in seqs_dy_device.items():
        if not seqs:
            continue

        seqs_sorted = sorted(seqs)
        received = len(seqs_sorted)

        # Calcula gaps entre sequências consecutivas
        lost = sum(
            seqs_sorted[i] - seqs_sorted[i - 1] - 1
            for i in range(1, len(seqs_sorted))
            if seqs_sorted[i] - seqs_sorted[i - 1] > 1
        )

        expected = received + lost
        delivery_rate = (received / expected * 100) if expected > 0 else 0.0

        results.append(DeliveryStats(
            device_id=device_id,
            source_protocol=protocol_by_delivery.get(device_id, "unknown"),
            received=received,
            lost=lost,
            expected=expected,
            delivery_rate=delivery_rate
        ))

    return results

def build_experiment_summary(
        latency_records: list[dict],
        seq_records: list[dict],
        period_minutes: int
) -> ExperimentSummary:
    """
    Constrói um ExperimentSummary a partir dos registros brutos do InfluxDB.

    Args:
        latency_records: lista de dicts com latency_transport_ms,
                         latency_total_ms, source_protocol, payload_format
        seq_records:     lista de dicts com source_id, source_protocol, seq
        period_minutes:  duração do período analisado
    """

    summary = ExperimentSummary(
        period_minutes=period_minutes,
        total_messages=len(latency_records)
    )

    # ── Separa por protocolo ──────────────────────────────────────────────
    coap_transport_vals = [
        r["latency_transport_ms"] for r in latency_records
        if r.get("source_protocol") == "CoAP"
        and r.get("latency_transport_ms") is not None
    ]

    mqtt_transport_vals = [
        r["latency_transport_ms"] for r in latency_records
        if r.get("source_protocol") == "MQTT"
        and r.get("latency_transport_ms") is not None
    ]

    coap_total_vals = [
        r["latency_total_ms"] for r in latency_records
        if r.get("source_protocol") == "CoAP"
        and r.get("latency_total_ms") is not None
    ]

    mqtt_total_vals = [
        r["latency_total_ms"] for r in latency_records
        if r.get("source_protocol") == "MQTT"
        and r.get("latency_total_ms") is not None
    ]

    # ── Separa por formato de payload (só CoAP) ───────────────────────────
    cbor_vals = [
        r["latency_transport_ms"] for r in latency_records
        if r.get("payload_format") == "CBOR"
        and r.get("latency_transport_ms") is not None
    ]
    json_vals = [
        r["latency_transport_ms"] for r in latency_records
        if r.get("payload_format") == "JSON"
        and r.get("latency_transport_ms") is not None
    ]

    summary.coap_transport = compute_latency_stats(coap_transport_vals)
    summary.mqtt_transport = compute_latency_stats(mqtt_transport_vals)
    summary.coap_total     = compute_latency_stats(coap_total_vals)
    summary.mqtt_total     = compute_latency_stats(mqtt_total_vals)
    summary.cbor_transport = compute_latency_stats(cbor_vals)
    summary.json_transport = compute_latency_stats(json_vals)

    # ── Taxa de entrega ───────────────────────────────────────────────────
    seqs_by_device:     dict[str, list[int]] = {}
    protocol_by_device: dict[str, str]       = {}

    for r in seq_records:
        did  = r.get("source_id",       "?")
        prot = r.get("source_protocol", "?")
        seq  = r.get("seq")
        if seq is not None:
            seqs_by_device.setdefault(did, []).append(int(seq))
            protocol_by_device[did] = prot

    summary.delivery = compute_delivery_stats(seqs_by_device, protocol_by_device)

    return summary

def format_latency_table(summary: ExperimentSummary) -> list[dict]:
    """
    Formata as estatísticas de latência como tabela.
    Retorna lista de dicts prontos para pandas.DataFrame().
    """
    rows = []

    def add_row(label: str, stats: Optional[LatencyStats]):
        if stats and stats.count > 0:
            rows.append({
                "Configuração":   label,
                "N":              stats.count,
                "Mín (ms)":       stats.min_ms,
                "Média (ms)":     stats.mean_ms,
                "Mediana (ms)":   stats.median_ms,
                "P95 (ms)":       stats.p95_ms,
                "P99 (ms)":       stats.p99_ms,
                "Máx (ms)":       stats.max_ms,
                "DP (ms)":        stats.stdev_ms,
            })
    
    add_row("CoAP (transporte)",  summary.coap_transport)
    add_row("MQTT (transporte)",  summary.mqtt_transport)
    add_row("CBOR (transporte)",  summary.cbor_transport)
    add_row("JSON (transporte)",  summary.json_transport)
    add_row("CoAP (total E2E)",   summary.coap_total)
    add_row("MQTT (total E2E)",   summary.mqtt_total)

    return rows