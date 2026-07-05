"""
test_api.py — Testes dos endpoints FastAPI

Usa TestClient do FastAPI (sem servidor real).
Mocka o InfluxDB para não precisar do container.
Sem rede necessária.

COMO EXECUTAR:
  pip install httpx
  pytest tests/test_api.py -v
"""

from datetime import datetime, timezone
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient


# ── Mock do InfluxWriter antes de importar main ───────────────────────────────

@pytest.fixture(autouse=True)
def mock_influx():
    """Substitui InfluxWriter por mock em todos os testes."""
    with patch("api.main.InfluxWriter") as mock_cls:
        instance = MagicMock()
        instance.write.return_value           = None
        instance.query_latency.return_value   = []
        instance.query_sensor_data.return_value = []
        instance.query_delivery_rate.return_value = {}
        mock_cls.return_value = instance
        yield instance


@pytest.fixture(autouse=True)
def reset_global_state():
    """
    Reseta o TwinStore e os contadores de sessão antes de cada teste.

    api.main usa singletons em nível de módulo (twin_store, session_stats)
    — sem reset, dados de um teste 'vazam' para o próximo, causando
    falsos positivos/negativos dependendo da ordem de execução.
    """
    import api.main as main_module
    from api.twin_store import TwinStore

    main_module.twin_store = TwinStore()
    main_module.session_stats.update({
        "total_received":  0,
        "total_drones":    0,
        "total_stations":  0,
        "total_errors":    0,
        "gateway_metrics": {},
    })
    yield


@pytest.fixture
def client(mock_influx):
    from api.main import app
    with TestClient(app) as c:
        yield c


# ── Payload de teste ──────────────────────────────────────────────────────────
# Timestamps relativos a "agora" — evita que o teste quebre quando a data
# do sistema avançar além de uma data fixa codificada no passado.

def _now_iso(offset_ms: int = 0) -> str:
    from datetime import timedelta
    dt = datetime.now(timezone.utc) + timedelta(milliseconds=offset_ms)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


DRONE_PAYLOAD = {
    "gateway_id":      "gateway_01",
    "source_id":       "drone_01",
    "source_type":     "DRONE",
    "source_protocol": "CoAP",
    "payload_format":  "CBOR",
    "sensor_ts":       _now_iso(-200),    # 200ms atrás
    "gateway_ts":      _now_iso(-50),     # 50ms atrás (150ms de latência)
    "lat":             -5.7923,
    "lon":             -35.2128,
    "alt_m":           80.0,
    "heading_deg":     127.0,
    "velocity_ms":     11.2,
    "temp_c":          29.5,
    "hum_pct":         68.0,
    "battery_pct":     74,
    "battery_ok":      True,
    "rssi_dbm":        -61,
    "seq":             1,
    "flight_phase":    "MISSION",
    "waypoint_index":  1,
}

STATION_PAYLOAD = {
    "gateway_id":      "gateway_01",
    "source_id":       "estacao_01",
    "source_type":     "STATION",
    "source_protocol": "MQTT",
    "sensor_ts":       _now_iso(-180),
    "gateway_ts":      _now_iso(-100),
    "lat":             -5.7901,
    "lon":             -35.2098,
    "temp_c":          29.8,
    "hum_pct":         71.3,
    "pressure_hpa":    1012.4,
    "co2_ppm":         418.2,
    "uv_index":        6.1,
    "rssi_dbm":        -48,
    "seq":             87,
}


# ── POST /ingestao ────────────────────────────────────────────────────────────

def test_ingestao_drone_retorna_200(client):
    r = client.post("/ingest", json=DRONE_PAYLOAD)
    assert r.status_code == 200
    body = r.json()
    assert body["status"]        == "ok"
    assert "datacenter_ts"       in body
    assert "transport_ms"        in body
    assert body["transport_ms"]  >= 0


def test_ingestao_station_retorna_200(client):
    r = client.post("/ingest", json=STATION_PAYLOAD)
    assert r.status_code == 200
    assert r.json()["status"] == "ok"


def test_ingestao_payload_invalido_retorna_422(client):
    r = client.post("/ingest", json={"invalid": "data"})
    assert r.status_code == 422


def test_ingestao_source_type_invalido_retorna_422(client):
    payload = {**DRONE_PAYLOAD, "source_type": "SENSOR"}
    r = client.post("/ingest", json=payload)
    assert r.status_code == 422


def test_ingestao_chama_influx_write(client, mock_influx):
    client.post("/ingest", json=DRONE_PAYLOAD)
    assert mock_influx.write.call_count == 1


def test_ingestao_incrementa_contadores(client):
    client.post("/ingest", json=DRONE_PAYLOAD)
    client.post("/ingest", json=STATION_PAYLOAD)

    r = client.get("/status")
    assert r.status_code == 200
    session = r.json()["session"]
    assert session["total_received"]  == 2
    assert session["total_drones"]    == 1
    assert session["total_stations"]  == 1


# ── GET /twins ────────────────────────────────────────────────────────────────

def test_twins_vazio_inicialmente(client):
    r = client.get("/twins")
    assert r.status_code == 200
    body = r.json()
    assert body["drones"]   == {}
    assert body["stations"] == {}


def test_twins_retorna_drone_apos_ingestao(client):
    client.post("/ingest", json=DRONE_PAYLOAD)

    r = client.get("/twins")
    assert r.status_code == 200
    assert "drone_01" in r.json()["drones"]


def test_twins_retorna_station_apos_ingestao(client):
    client.post("/ingest", json=STATION_PAYLOAD)

    r = client.get("/twins")
    assert r.status_code == 200
    assert "estacao_01" in r.json()["stations"]


def test_twins_drone_tem_campos_corretos(client):
    client.post("/ingest", json=DRONE_PAYLOAD)

    twin = client.get("/twins").json()["drones"]["drone_01"]
    assert twin["flight_phase"]   == "MISSION"
    assert twin["battery_pct"]    == 74
    assert twin["seq"]            == 1
    assert twin["payload_format"] == "CBOR"


def test_twin_por_id_retorna_404_desconhecido(client):
    r = client.get("/twins/dispositivo_inexistente")
    assert r.status_code == 404


def test_twin_por_id_retorna_200_existente(client):
    client.post("/ingest", json=DRONE_PAYLOAD)
    r = client.get("/twins/drone_01")
    assert r.status_code == 200
    assert r.json()["device_id"] == "drone_01"


# ── GET /status ───────────────────────────────────────────────────────────────

def test_status_retorna_estrutura_correta(client):
    r = client.get("/status")
    assert r.status_code == 200
    body = r.json()
    assert "session" in body
    assert "devices" in body


def test_status_session_tem_campos_corretos(client):
    r      = client.get("/status")
    session = r.json()["session"]
    assert "total_received"  in session
    assert "total_drones"    in session
    assert "total_stations"  in session
    assert "total_errors"    in session
    assert "started_at"      in session


# ── GET /metricas/latencia ────────────────────────────────────────────────────

def test_metricas_latencia_retorna_200(client):
    r = client.get("/metrics/latency")
    assert r.status_code == 200


def test_metricas_latencia_aceita_filtro_source_type(client):
    r = client.get("/metrics/latency?source_type=DRONE&minutes=30")
    assert r.status_code == 200


# ── GET /metricas/entrega ─────────────────────────────────────────────────────

def test_metricas_entrega_retorna_200(client):
    r = client.get("/metrics/delivery")
    assert r.status_code == 200


# ── GET /dados ────────────────────────────────────────────────────────────────

def test_dados_retorna_200(client):
    r = client.get("/data")
    assert r.status_code == 200


# ── POST /gateway/metrics ─────────────────────────────────────────────────────

def test_gateway_metrics_aceito(client):
    payload = {
        "coap_received": 42,
        "mqtt_received": 38,
        "pipeline_queue": 0,
    }
    r = client.post("/gateway/metrics", json=payload)
    assert r.status_code == 200
    assert r.json()["status"] == "ok"


def test_gateway_metrics_visivel_no_status(client):
    client.post("/gateway/metrics", json={"coap_received": 99})
    r = client.get("/status")
    gw = r.json()["session"].get("gateway_metrics", {})
    assert gw.get("coap_received") == 99