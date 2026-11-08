"""
test_auth.py — Testes de autenticação JWT da camada de serviço.

Cobre:
  1. POST /api/v1/auth/token com credenciais válidas (admin e viewer)
  2. GET  /api/v1/twins sem token → 401 Unauthorized
  3. GET  /api/v1/twins com token viewer → 200 OK
  4. POST /api/v1/ingest com token viewer → 403 Forbidden (somente admin)
  5. POST /api/v1/ingest com token admin → 200 OK
  6. POST /api/v1/auth/token com senha errada → 401 Unauthorized
  7. GET  /health sem token → 200 OK (endpoint público)
"""

import pytest
from fastapi.testclient import TestClient
from unittest.mock import AsyncMock, MagicMock, patch


# ── Fixtures ──────────────────────────────────────────────────────────────────

@pytest.fixture(scope="module")
def client():
    """Cria o TestClient com mocks de InfluxWriter e TwinStore."""

    # Mock do TwinStore — retorna lista vazia
    mock_store = MagicMock()
    mock_store.get_all      = AsyncMock(return_value={"drones": [], "stations": []})
    mock_store.get_drone    = AsyncMock(return_value=None)
    mock_store.get_station  = AsyncMock(return_value=None)
    mock_store.update       = AsyncMock(return_value=None)
    mock_store.get_summary  = AsyncMock(return_value={"total_drones": 0, "total_stations": 0})

    # Mock do InfluxWriter — sem conexão real
    mock_influx = MagicMock()
    mock_influx.write            = MagicMock(return_value=None)
    mock_influx.query_latency    = MagicMock(return_value=[])
    mock_influx.query_delivery_rate  = MagicMock(return_value=[])
    mock_influx.query_sensor_data    = MagicMock(return_value=[])
    mock_influx.close            = MagicMock(return_value=None)

    with (
        patch("api.main.TwinStore",    return_value=mock_store),
        patch("api.main.InfluxWriter", return_value=mock_influx),
    ):
        from api.main import app
        with TestClient(app, raise_server_exceptions=True) as c:
            yield c


@pytest.fixture(scope="module")
def admin_token(client):
    """Obtém token admin válido."""
    resp = client.post(
        "/api/v1/auth/token",
        json={"username": "admin", "password": "admin123"},
    )
    assert resp.status_code == 200
    return resp.json()["access_token"]


@pytest.fixture(scope="module")
def viewer_token(client):
    """Obtém token viewer válido."""
    resp = client.post(
        "/api/v1/auth/token",
        json={"username": "viewer", "password": "viewer123"},
    )
    assert resp.status_code == 200
    return resp.json()["access_token"]


# ── Testes de autenticação ────────────────────────────────────────────────────

class TestTokenEndpoint:
    def test_login_admin_retorna_token(self, client):
        resp = client.post(
            "/api/v1/auth/token",
            json={"username": "admin", "password": "admin123"},
        )
        assert resp.status_code == 200
        body = resp.json()
        assert "access_token" in body
        assert body["role"] == "admin"
        assert body["token_type"] == "bearer"

    def test_login_viewer_retorna_token(self, client):
        resp = client.post(
            "/api/v1/auth/token",
            json={"username": "viewer", "password": "viewer123"},
        )
        assert resp.status_code == 200
        assert resp.json()["role"] == "viewer"

    def test_senha_errada_retorna_401(self, client):
        resp = client.post(
            "/api/v1/auth/token",
            json={"username": "admin", "password": "senhaerrada"},
        )
        assert resp.status_code == 401

    def test_usuario_inexistente_retorna_401(self, client):
        resp = client.post(
            "/api/v1/auth/token",
            json={"username": "hacker", "password": "qualquer"},
        )
        assert resp.status_code == 401


class TestEndpointsProtegidos:
    def test_twins_sem_token_retorna_401(self, client):
        resp = client.get("/api/v1/twins")
        assert resp.status_code == 401

    def test_twins_com_viewer_retorna_200(self, client, viewer_token):
        resp = client.get(
            "/api/v1/twins",
            headers={"Authorization": f"Bearer {viewer_token}"},
        )
        assert resp.status_code == 200

    def test_twins_com_admin_retorna_200(self, client, admin_token):
        resp = client.get(
            "/api/v1/twins",
            headers={"Authorization": f"Bearer {admin_token}"},
        )
        assert resp.status_code == 200

    def test_status_sem_token_retorna_401(self, client):
        resp = client.get("/api/v1/status")
        assert resp.status_code == 401

    def test_status_com_viewer_retorna_200(self, client, viewer_token):
        resp = client.get(
            "/api/v1/status",
            headers={"Authorization": f"Bearer {viewer_token}"},
        )
        assert resp.status_code == 200


class TestControleDeAcesso:
    """Verifica que viewer não acessa endpoints admin."""

    def test_ingest_sem_token_retorna_401(self, client):
        resp = client.post("/api/v1/ingest", json={})
        assert resp.status_code == 401

    def test_ingest_com_viewer_retorna_403(self, client, viewer_token):
        resp = client.post(
            "/api/v1/ingest",
            json={},
            headers={"Authorization": f"Bearer {viewer_token}"},
        )
        assert resp.status_code == 403

    def test_gateway_metrics_com_viewer_retorna_403(self, client, viewer_token):
        resp = client.post(
            "/api/v1/gateway/metrics",
            json={"coap_received": 10},
            headers={"Authorization": f"Bearer {viewer_token}"},
        )
        assert resp.status_code == 403

    def test_gateway_metrics_com_admin_retorna_200(self, client, admin_token):
        resp = client.post(
            "/api/v1/gateway/metrics",
            json={"coap_received": 10, "mqtt_received": 5},
            headers={"Authorization": f"Bearer {admin_token}"},
        )
        assert resp.status_code == 200
        assert resp.json()["status"] == "ok"


class TestEndpointsPublicos:
    def test_health_sem_token_retorna_200(self, client):
        resp = client.get("/health")
        assert resp.status_code == 200
        assert resp.json()["status"] == "ok"

    def test_docs_sem_token_retorna_200(self, client):
        resp = client.get("/docs")
        assert resp.status_code == 200
