"""
test_twin_store.py — Testes do TwinStore

Sem rede, sem InfluxDB — testa só a lógica em memória.

COMO EXECUTAR:
  pip install pytest pytest-asyncio
  pytest tests/test_twin_store.py -v
"""

import asyncio
from datetime import datetime, timezone

import pytest

from api.models import NormalizedData
from api.twin_store import TwinStore


# ── Fixtures ──────────────────────────────────────────────────────────────────

def make_drone(seq: int = 1, **kwargs) -> NormalizedData:
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
        alt_m           = 80.0,
        heading_deg     = 127.0,
        velocity_ms     = 11.2,
        temp_c          = 29.5,
        hum_pct         = 68.0,
        battery_pct     = 74,
        battery_ok      = True,
        rssi_dbm        = -61,
        seq             = seq,
        flight_phase    = "MISSION",
        waypoint_index  = 1,
    )
    base.update(kwargs)
    return NormalizedData(**base)


def make_station(seq: int = 1, **kwargs) -> NormalizedData:
    base = dict(
        gateway_id      = "gateway_01",
        source_id       = "estacao_01",
        source_type     = "STATION",
        source_protocol = "MQTT",
        payload_format  = "JSON",
        sensor_ts       = "2026-05-21T10:00:05.000Z",
        gateway_ts      = "2026-05-21T10:00:05.080Z",
        lat             = -5.7901,
        lon             = -35.2098,
        temp_c          = 29.8,
        hum_pct         = 71.3,
        pressure_hpa    = 1012.4,
        co2_ppm         = 418.2,
        uv_index        = 6.1,
        rssi_dbm        = -48,
        seq             = seq,
    )
    base.update(kwargs)
    return NormalizedData(**base)


# ── Testes do Drone Twin ──────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_drone_twin_criado_apos_update():
    store = TwinStore()
    await store.update(make_drone(seq=1))

    twin = store.get_drone("drone_01")
    assert twin is not None
    assert twin.device_id    == "drone_01"
    assert twin.flight_phase == "MISSION"
    assert twin.seq          == 1
    assert twin.battery_pct  == 74
    assert twin.alt_m        == 80.0


@pytest.mark.asyncio
async def test_drone_twin_atualizado_sequencialmente():
    store = TwinStore()
    await store.update(make_drone(seq=1, battery_pct=74))
    await store.update(make_drone(seq=2, battery_pct=73))

    twin = store.get_drone("drone_01")
    assert twin.battery_pct      == 73
    assert twin.seq              == 2
    assert twin.messages_received == 2
    assert twin.messages_lost     == 0


@pytest.mark.asyncio
async def test_drone_twin_detecta_gap_de_seq():
    store = TwinStore()
    await store.update(make_drone(seq=1))
    await store.update(make_drone(seq=4))   # pulou 2 e 3

    twin = store.get_drone("drone_01")
    assert twin.messages_lost     == 2
    assert twin.messages_received == 2


@pytest.mark.asyncio
async def test_drone_twin_sem_gap_sem_perda():
    store = TwinStore()
    for seq in range(1, 11):
        await store.update(make_drone(seq=seq))

    twin = store.get_drone("drone_01")
    assert twin.messages_lost     == 0
    assert twin.messages_received == 10


@pytest.mark.asyncio
async def test_drone_twin_campos_exclusivos_nao_tem_pressure():
    store = TwinStore()
    await store.update(make_drone(seq=1))

    twin = store.get_drone("drone_01")
    # Twin do drone não tem pressure, co2, uv — são da estação
    assert not hasattr(twin, "pressure_hpa")
    assert not hasattr(twin, "co2_ppm")
    assert not hasattr(twin, "uv_index")


@pytest.mark.asyncio
async def test_drone_twin_inferencia_estimated_return():
    store = TwinStore()
    # Bateria em 80%, fase MISSION (drain=0.05%/s), limiar=20%
    # usable = 60%, tempo = 60/0.05 = 1200s = 20 min
    await store.update(make_drone(seq=1, battery_pct=75, flight_phase="MISSION"))

    twin = store.get_drone("drone_01")
    assert twin.estimated_return_min > 0
    assert abs(twin.estimated_return_min - 20.0) < 1.0


@pytest.mark.asyncio
async def test_drone_twin_anomalia_temperatura():
    store = TwinStore()
    # Alt=80m → temp esperada ≈ 28 - 0.52 = 27.48°C
    # Temperatura real = 35°C → desvio > 3°C → anomalia
    await store.update(make_drone(seq=1, alt_m=80.0, temp_c=35.0))

    twin = store.get_drone("drone_01")
    assert twin.anomaly_detected is True


@pytest.mark.asyncio
async def test_drone_twin_sem_anomalia_temperatura_normal():
    store = TwinStore()
    # Alt=80m → temp esperada ≈ 27.48°C
    # Temperatura real = 28°C → desvio < 3°C → sem anomalia
    await store.update(make_drone(seq=1, alt_m=80.0, temp_c=28.0))

    twin = store.get_drone("drone_01")
    assert twin.anomaly_detected is False


# ── Testes da Estação Twin ────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_station_twin_criado_apos_update():
    store = TwinStore()
    await store.update(make_station(seq=1))

    twin = store.get_station("estacao_01")
    assert twin is not None
    assert twin.device_id    == "estacao_01"
    assert twin.co2_ppm      == 418.2
    assert twin.pressure_hpa == 1012.4
    assert twin.uv_index     == 6.1
    assert twin.seq          == 1


@pytest.mark.asyncio
async def test_station_twin_detecta_gap_de_seq():
    store = TwinStore()
    await store.update(make_station(seq=10))
    await store.update(make_station(seq=15))   # perdeu 4

    twin = store.get_station("estacao_01")
    assert twin.messages_lost     == 4
    assert twin.messages_received == 2


@pytest.mark.asyncio
async def test_station_twin_co2_trend_rising():
    store = TwinStore()
    # Injeta leituras crescentes de CO2
    base = 415.0
    for i in range(10):
        await store.update(make_station(seq=i + 1, co2_ppm=base + i * 3.0))

    twin = store.get_station("estacao_01")
    assert twin.co2_trend == "RISING"


@pytest.mark.asyncio
async def test_station_twin_co2_trend_stable():
    store = TwinStore()
    for i in range(10):
        await store.update(make_station(seq=i + 1, co2_ppm=415.0))

    twin = store.get_station("estacao_01")
    assert twin.co2_trend == "STABLE"


@pytest.mark.asyncio
async def test_station_twin_uv_period_day():
    store = TwinStore()
    await store.update(make_station(seq=1, uv_index=7.0))

    twin = store.get_station("estacao_01")
    assert twin.uv_period == "DAY"


@pytest.mark.asyncio
async def test_station_twin_uv_period_night():
    store = TwinStore()
    await store.update(make_station(seq=1, uv_index=0.0))

    twin = store.get_station("estacao_01")
    assert twin.uv_period == "NIGHT"


# ── Testes do get_all() ───────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_get_all_retorna_drone_e_estacao():
    store = TwinStore()
    await store.update(make_drone(seq=1))
    await store.update(make_station(seq=1))

    result = store.get_all()
    assert "drones"   in result
    assert "stations" in result
    assert "drone_01"   in result["drones"]
    assert "estacao_01" in result["stations"]


@pytest.mark.asyncio
async def test_get_summary_retorna_estrutura_correta():
    store = TwinStore()
    await store.update(make_drone(seq=1))
    await store.update(make_station(seq=1))

    summary = store.get_summary()
    assert "drones"   in summary
    assert "stations" in summary
    assert "drone_01"   in summary["drones"]
    assert "estacao_01" in summary["stations"]

    drone_s = summary["drones"]["drone_01"]
    assert "online"         in drone_s
    assert "flight_phase"   in drone_s
    assert "battery_pct"    in drone_s


# ── Testes de concorrência ────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_concurrent_updates_sem_race_condition():
    """Múltiplas corrotinas atualizando o twin simultaneamente."""
    store = TwinStore()

    async def update_many(start: int, count: int):
        for i in range(start, start + count):
            await store.update(make_drone(seq=i))

    await asyncio.gather(
        update_many(1,  25),
        update_many(26, 25),
    )

    twin = store.get_drone("drone_01")
    assert twin is not None
    assert twin.messages_received == 50