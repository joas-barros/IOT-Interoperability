"""
test_calculator.py — Testes do módulo de cálculos estatísticos

Sem rede necessária.

COMO EXECUTAR:
  pytest tests/test_calculator.py -v
"""

import pytest

from metrics.calculator import (
    compute_delivery_stats,
    compute_latency_stats,
    build_experiment_summary,
    format_latency_table,
)


# ── compute_latency_stats ─────────────────────────────────────────────────────

def test_latency_stats_lista_vazia():
    assert compute_latency_stats([]) is None


def test_latency_stats_valor_unico():
    stats = compute_latency_stats([100.0])
    assert stats.count     == 1
    assert stats.min_ms    == 100.0
    assert stats.max_ms    == 100.0
    assert stats.mean_ms   == 100.0
    assert stats.stdev_ms  == 0.0


def test_latency_stats_valores_conhecidos():
    values = [10.0, 20.0, 30.0, 40.0, 50.0]
    stats  = compute_latency_stats(values)

    assert stats.count     == 5
    assert stats.min_ms    == 10.0
    assert stats.max_ms    == 50.0
    assert stats.mean_ms   == 30.0
    assert stats.median_ms == 30.0


def test_latency_stats_p95_correto():
    # 100 valores de 1 a 100 — p95 deve ser próximo de 95
    values = [float(i) for i in range(1, 101)]
    stats  = compute_latency_stats(values)
    assert stats.p95_ms >= 94.0
    assert stats.p95_ms <= 96.0


def test_latency_stats_to_dict():
    stats = compute_latency_stats([10.0, 20.0, 30.0])
    d = stats.to_dict()

    assert "count"     in d
    assert "min_ms"    in d
    assert "max_ms"    in d
    assert "mean_ms"   in d
    assert "median_ms" in d
    assert "p95_ms"    in d
    assert "p99_ms"    in d
    assert "stdev_ms"  in d


# ── compute_delivery_stats ────────────────────────────────────────────────────

def test_delivery_stats_sem_perdas():
    seqs     = {"drone_01": list(range(1, 11))}
    protocol = {"drone_01": "CoAP"}

    result = compute_delivery_stats(seqs, protocol)
    assert len(result) == 1

    d = result[0]
    assert d.received      == 10
    assert d.lost          == 0
    assert d.expected      == 10
    assert d.delivery_rate == 100.0


def test_delivery_stats_com_perdas():
    # Seq: 1, 2, 5, 6 → perdeu 3 e 4
    seqs     = {"drone_01": [1, 2, 5, 6]}
    protocol = {"drone_01": "CoAP"}

    result = compute_delivery_stats(seqs, protocol)
    d = result[0]

    assert d.received      == 4
    assert d.lost          == 2
    assert d.expected      == 6
    assert abs(d.delivery_rate - 66.67) < 0.1


def test_delivery_stats_multiplos_dispositivos():
    seqs = {
        "drone_01":   [1, 2, 3, 4, 5],
        "estacao_01": [1, 2, 4, 5],     # perdeu 3
    }
    protocol = {
        "drone_01":   "CoAP",
        "estacao_01": "MQTT",
    }

    result = compute_delivery_stats(seqs, protocol)
    assert len(result) == 2

    by_dev = {d.device_id: d for d in result}

    assert by_dev["drone_01"].delivery_rate   == 100.0
    assert by_dev["drone_01"].source_protocol == "CoAP"
    assert by_dev["estacao_01"].lost          == 1
    assert by_dev["estacao_01"].source_protocol == "MQTT"


def test_delivery_stats_lista_vazia():
    result = compute_delivery_stats({}, {})
    assert result == []


# ── build_experiment_summary ──────────────────────────────────────────────────

def test_experiment_summary_separado_por_protocolo():
    latency_records = [
        {"source_protocol": "CoAP", "payload_format": "CBOR",
         "latency_transport_ms": 50.0, "latency_total_ms": 80.0},
        {"source_protocol": "CoAP", "payload_format": "CBOR",
         "latency_transport_ms": 60.0, "latency_total_ms": 90.0},
        {"source_protocol": "MQTT", "payload_format": "JSON",
         "latency_transport_ms": 30.0, "latency_total_ms": 60.0},
    ]
    seq_records = [
        {"source_id": "drone_01",   "source_protocol": "CoAP", "seq": 1},
        {"source_id": "drone_01",   "source_protocol": "CoAP", "seq": 2},
        {"source_id": "estacao_01", "source_protocol": "MQTT", "seq": 1},
    ]

    summary = build_experiment_summary(latency_records, seq_records, 60)

    assert summary.total_messages    == 3
    assert summary.coap_transport    is not None
    assert summary.mqtt_transport    is not None
    assert summary.coap_transport.count == 2
    assert summary.mqtt_transport.count == 1
    assert summary.cbor_transport    is not None
    assert summary.cbor_transport.count == 2


def test_experiment_summary_to_dict():
    latency_records = [
        {"source_protocol": "CoAP", "payload_format": "CBOR",
         "latency_transport_ms": 55.0, "latency_total_ms": 85.0},
    ]
    summary = build_experiment_summary(latency_records, [], 30)
    d = summary.to_dict()

    assert "period_minutes" in d
    assert "total_messages" in d
    assert "latency"        in d
    assert "delivery"       in d
    assert "coap_transport" in d["latency"]


# ── format_latency_table ──────────────────────────────────────────────────────

def test_format_latency_table_retorna_lista():
    latency_records = [
        {"source_protocol": "CoAP", "payload_format": "CBOR",
         "latency_transport_ms": 55.0, "latency_total_ms": 85.0},
        {"source_protocol": "MQTT", "payload_format": "JSON",
         "latency_transport_ms": 30.0, "latency_total_ms": 60.0},
    ]
    summary = build_experiment_summary(latency_records, [], 60)
    table   = format_latency_table(summary)

    assert isinstance(table, list)
    assert len(table) > 0

    row = table[0]
    assert "Configuração" in row
    assert "N"            in row
    assert "Média (ms)"   in row
    assert "P95 (ms)"     in row