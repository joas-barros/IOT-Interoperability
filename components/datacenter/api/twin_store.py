"""
twin_store.py — TwinStore: dicionário em memória com estado atual dos dispositivos. (Possivelmente persistido em banco de dados REDIS no futuro.)

Arquitetura de acesso:
  FastAPI (processo 1) → atualiza twins via update()
  Streamlit (processo 2) → lê twins via GET /twins (endpoint FastAPI)

O TwinStore é um singleton instanciado uma vez no startup do FastAPI.
Acesso concorrente protegido por asyncio.Lock() — FastAPI é async e
múltiplas requisições podem chamar update() simultaneamente.
"""

import asyncio
import math
import os
import json
from datetime import datetime, timezone
from typing import Optional

import redis.asyncio as redis
from api.models import NormalizedData, DroneTwin, StationTwin

# Constantes de inferência
BATTERY_DRAIN_PER_S = {
    "IDLE":     0.00,
    "TAKEOFF":  0.08,
    "MISSION":  0.05,
    "HOVER":    0.07,
    "RETURN":   0.05,
    "LANDING":  0.04,
    "UNKNOWN":  0.05,
}

# Gradiente adiabático: -0.0065°C por metro
ADIABATIC_LAPSE_RATE = 0.0065

# Quantas leituras de CO2 manter para calcular tendência
CO2_HISTORY_SIZE = 10

BATTERY_RESERVE_PCT = 15  # Percentual de bateria que não deve ser usado para voo

# Tempo máximo sem mensagem para considerar offline (segundos)
OFFLINE_THRESHOLD_S = int(os.getenv("TWIN_OFFLINE_THRESHOLD_S", "30"))

TEMP_BASE  = 28.0   # SENSOR_TEMP_BASE do config.h do ESP32

REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379/0")

class TwinStore:
    """
    Armazena e atualiza os Digital Twins dos dispositivos IoT.

    Cada chamada a update() recebe um NormalizedData do gateway,
    atualiza o twin correspondente (criando se não existir) e
    recalcula todas as inferências.
    """

    def __init__(self):
        self._lock = asyncio.Lock()

        # Inicializa o cliente Redis assíncrono. decode_responses=True converte bytes para string.
        self.redis = redis.from_url(REDIS_URL, decode_responses=True)

    
    # ── Interface pública ─────────────────────────────────────────────────
    async def update(self, data: NormalizedData) -> None:
        """Atualiza o twin correspondente ao NormalizedData recebido."""
        async with self._lock:
            if data.source_type == "DRONE":
                await self._update_drone_twin(data)
            elif data.source_type == "STATION":
                await self._update_station_twin(data)
            else:
                raise ValueError(f"Tipo de dispositivo desconhecido: {data.source_type}")
            
            # --- PUBLICADOR PUB/SUB ---
            # Após atualizar o banco, recupera o estado completo e publica
            estado_atual = await self.get_all()
            await self.redis.publish("twins_updates", json.dumps(estado_atual))
    
    async def get_drone(self, device_id: str) -> Optional[DroneTwin]:
        raw = await self.redis.hget("twins:drones", device_id)
        return DroneTwin.model_validate_json(raw) if raw else None
    
    async def get_station(self, device_id: str) -> Optional[StationTwin]:
        raw = await self.redis.hget("twins:stations", device_id)
        return StationTwin.model_validate_json(raw) if raw else None
    
    async def get_all(self) -> dict:
        """Busca todos os twins no Redis e formata para o endpoint GET /twins e Pub/Sub."""
        now = datetime.now(timezone.utc)

        drones_raw = await self.redis.hgetall("twins:drones")
        stations_raw = await self.redis.hgetall("twins:stations")

        drones = {}
        for did, raw in drones_raw.items():
            t = DroneTwin.model_validate_json(raw)
            elapsed = (now - t.last_update).total_seconds()
            t.is_online = elapsed <= OFFLINE_THRESHOLD_S
            drones[did] = t.model_dump(mode="json")  # Serializa para JSON
        
        stations = {}
        for sid, raw in stations_raw.items():
            t = StationTwin.model_validate_json(raw)
            elapsed = (now - t.last_update).total_seconds()
            t.is_online = elapsed <= OFFLINE_THRESHOLD_S
            stations[sid] = t.model_dump(mode="json")  # Serializa para JSON
        
        return {"drones": drones, "stations": stations}
    
    async def get_summary(self) -> dict:
        """Resumo rápido para o endpoint GET /status."""
        full_data = await self.get_all()
        now = datetime.now(timezone.utc)

        def seconds_ago(iso_date: str) -> float:
            dt = datetime.fromisoformat(iso_date.replace("Z", "+00:00"))
            return (now - dt).total_seconds()
        
        return {
            "drones": {
                did: {
                    "online": t["is_online"],
                    "last_update_s_ago": round(seconds_ago(t["last_update"]), 1),
                    "flight_phase": t["flight_phase"],
                    "battery_pct": t["battery_pct"],
                    "seq": t["seq"],
                    "messages_lost": t["messages_lost"],
                }
                for did, t in full_data["drones"].items()
            },
            "stations": {
                sid: {
                    "online": t["is_online"],
                    "last_update_s_ago": round(seconds_ago(t["last_update"]), 1),
                    "co2_trend": t["co2_trend"],
                    "seq": t["seq"],
                    "messages_lost": t["messages_lost"],
                }
                for sid, t in full_data["stations"].items()
            },
        }
    
    # ── Atualização de Drone ──────────────────────────────────────────────

    async def _update_drone_twin(self, data: NormalizedData) -> None:
        did = data.source_id
        now = datetime.now(timezone.utc)

        # Recupera o twin existente do Redis
        existing = await self.get_drone(did)

        # Detecta gaps de sequencia
        lost = await self._detect_gaps(did, data.seq or 0)

        twin = DroneTwin(
            device_id=did,
            last_update  = now,
            source_protocol = data.source_protocol,
            payload_format  = data.payload_format or "JSON",

            # Flight state
            flight_phase = data.flight_phase or "UNKNOWN",
            waypoint_index = data.waypoint_index or 0,

            # Position
            lat = data.lat or 0.0,
            lon = data.lon or 0.0,
            alt_m = data.alt_m or 0.0,
            heading_deg = data.heading_deg or 0.0,
            velocity_ms = data.velocity_ms or 0.0,

            # Environment
            temp_c = data.temp_c or 0.0,
            hum_pct = data.hum_pct or 0.0,

            # System
            battery_pct = data.battery_pct or 0,
            battery_ok = data.battery_ok if data.battery_ok is not None else False,
            rssi_dbm = data.rssi_dbm or 0,
            seq = data.seq or 0,

            # Metrics
            messages_received = (existing.messages_received if existing else 0) + 1,
            messages_lost = (existing.messages_lost     if existing else 0) + lost,

             # Inferências
            is_online              = True,
            estimated_return_min   = self._estimate_return_min(data),
            coverage_radius_m      = self._coverage_radius(data),
            anomaly_detected       = self._detect_temp_anomaly(data),
        )

        # Salva o Pydantic model como JSON no Redis Hash
        await self.redis.hset("twins:drones", did, twin.model_dump_json())

    # ── Atualização de Estação ────────────────────────────────────────────
    async def _update_station_twin(self, data: NormalizedData) -> None:
        sid = data.source_id
        now = datetime.now(timezone.utc)

        existing = await self.get_station(sid)
        lost = await self._detect_gaps(sid, data.seq or 0)

        # Atualiza histórico de CO2
        co2 = data.co2_ppm or 0.0
        co2_key = f"twins:co2_history:{sid}"
        await self.redis.rpush(co2_key, co2)
        await self.redis.ltrim(co2_key, -CO2_HISTORY_SIZE, -1)  # Mantém apenas os últimos N

        # Recupera a lista atualizada para calcular a tendência
        raw_history = await self.redis.lrange(co2_key, 0, -1)
        history = [float(x) for x in raw_history]

        twin = StationTwin(
            device_id    = sid,
            last_update  = now,
            source_protocol = data.source_protocol,

            # Posição fixa
            lat = data.lat or 0.0,
            lon = data.lon or 0.0,

            # Ambiente
            temp_c       = data.temp_c       or 0.0,
            hum_pct      = data.hum_pct      or 0.0,
            pressure_hpa = data.pressure_hpa or 0.0,
            co2_ppm      = co2,
            uv_index     = data.uv_index     or 0.0,

            # Sistema
            rssi_dbm = data.rssi_dbm or 0,
            seq      = data.seq      or 0,

            # Métricas acumuladas
            messages_received = (existing.messages_received if existing else 0) + 1,
            messages_lost     = (existing.messages_lost     if existing else 0) + lost,

            # Inferências
            is_online  = True,
            co2_trend  = self._co2_trend(history),
            uv_period  = self._uv_period(data.uv_index or 0.0),
        )

        await self.redis.hset("twins:stations", sid, twin.model_dump_json())


    # ── Inferências ───────────────────────────────────────────────────────

    async def _detect_gaps(self, device_id: str, current_seq: int) -> int:
        """Lê o último seq do Redis, atualiza e calcula a diferença."""

        last_str = await self.redis.hget("twins:last_seq", device_id)
        last = int(last_str) if last_str else None

        await self.redis.hset("twins:last_seq", device_id, current_seq)

        if last is None or current_seq <= last:
            return 0  # Primeira mensagem ou seq resetado
        
        gap = current_seq - last - 1
        return max(0, gap)

    def _estimate_return_min(self, data: NormalizedData) -> float:
        """
        Estima quanto tempo de voo resta antes do retorno forçado.
        Baseado na bateria atual e na taxa de consumo da fase.
        """

        battery = data.battery_pct or 0
        phase = data.flight_phase or "UNKNOWN"
        drain = BATTERY_DRAIN_PER_S.get(phase, 0.05)

        usable = max(0, battery - BATTERY_RESERVE_PCT)  # Assume 15% de reserva
        if drain == 0:
            return math.inf  # Sem consumo, tempo infinito
        
        remaining_s = usable / drain
        return round(remaining_s / 60, 1)  # Retorna em minutos
    
    def _coverage_radius(self, data: NormalizedData) -> float:
        """
        Raio máximo que o drone pode alcançar e ainda ter bateria para voltar.
        Assume velocidade de cruzeiro de 12 m/s (MISSION_SPEED_MS do ESP32).
        """

        battery     = data.battery_pct or 0
        phase       = data.flight_phase or "UNKNOWN"
        drain       = BATTERY_DRAIN_PER_S.get(phase, 0.05)
        cruise_speed = 12.0  # m/s

        usable = max(0, battery - BATTERY_RESERVE_PCT)  # Assume 15% de reserva
        if drain == 0:
            return 0.0

        # Metade do tempo disponível para ir, metade para voltar
        one_way_s  = (usable / drain) / 2
        return round(one_way_s * cruise_speed, 1)
    
    def _detect_temp_anomaly(self, data: NormalizedData) -> bool:
        """
        Detecta anomalia de temperatura comparando leitura atual
        com a esperada pelo gradiente adiabático.
        temp_esperada = temp_base - (alt * ADIABATIC_LAPSE_RATE)
        Threshold: > 3°C de desvio é anomalia.
        """

        temp = data.temp_c or 0.0
        alt  = data.alt_m  or 0.0

        expected   = TEMP_BASE - (alt * ADIABATIC_LAPSE_RATE)
        deviation  = abs(temp - expected)
        return deviation > 3.0
    
    def _co2_trend(self, history: list[float]) -> str:
        """
        Calcula tendência de CO2 comparando a média das últimas 5
        leituras com as 5 anteriores.
        """

        if len(history) < 4:
            return "STABLE" 
        
        mid = len(history) // 2
        first_half  = history[:mid]
        second_half = history[mid:]

        recent = sum(second_half) / len(second_half)
        older = sum(first_half) / len(first_half)

        diff = recent - older

        if diff > 1.0:
            return "RISING"
        if diff < -1.0:
            return "FALLING"
        return "STABLE"
    
    def _uv_period(self, uv_index: float) -> str:
        """Classifica o período do dia pelo índice UV simulado."""
        if uv_index >= 3.0:
            return "DAY"
        if uv_index >= 0.5:
            return "DUSK"
        return "NIGHT"
        