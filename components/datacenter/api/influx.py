"""
influx.py — Cliente InfluxDB para persistência de dados de telemetria.

Cada NormalizedData recebido é escrito como um ponto de série temporal
no InfluxDB com:
  - measurement: "telemetria"
  - tags:   source_id, source_type, source_protocol, payload_format, flight_phase
  - fields: todos os valores numéricos e booleanos
  - time:   sensor_ts (timestamp do sensor, não do ingresso)

Tags são indexadas pelo InfluxDB e permitem filtros eficientes.
Fields são os valores medidos — não indexados, mas consultáveis.
"""

import os
from datetime import datetime, timezone
from typing import Optional

from influxdb_client import InfluxDBClient, Point, WritePrecision
from influxdb_client.client.write_api import SYNCHRONOUS

from api.models import NormalizedData


class InfluxWriter:
    """Escreve NormalizedData no InfluxDB como pontos de série temporal."""

    def __init__(self):
        self._url = os.getenv("INFLUX_URL", "http://localhost:8086")
        self._token = os.getenv("INFLUX_TOKEN")
        self._org = os.getenv("INFLUX_ORG", "iot-ic")
        self._bucket = os.getenv("INFLUX_BUCKET", "telemetria")

        self._client = InfluxDBClient(url=self._url, token=self._token, org=self._org)
        self._write_api = self._client.write_api(write_options=SYNCHRONOUS)
        self._query_api = self._client.query_api()

    def write(self, data: NormalizedData, datacenter_ts: datetime) -> None:
        """
        Persiste um NormalizedData no InfluxDB.
        datacenter_ts é adicionado pelo FastAPI no momento do recebimento.
        """
        # Calcula latências para persistir junto com os dados
        transport_ms = data.transport_latency_ms()
        total_ms = (datacenter_ts - data.sensor_datetime()).total_seconds() * 1000

        point = (
            Point("telemetria")

            # ── Tags (indexadas — usadas em filtros e GROUP BY) ──
            .tag("source_id", data.source_id)
            .tag("source_type", data.source_type)
            .tag("source_protocol", data.source_protocol)
            .tag("payload_format", data.payload_format or "JSON")
            .tag("gateway_id", data.gateway_id)
        )

        # Tag de fase de voo (só para drone)
        if data.flight_phase:
            point = point.tag("flight_phase", data.flight_phase)
        
        # ── Fields — valores medidos ──────────────────────────────────────

        # Latências (métricas centrais dos experimentos)
        point = (
            point
            .field("latency_transport_ms", round(transport_ms, 2))
            .field("latency_total_ms",     round(total_ms, 2))
        )

        # Posição
        if data.lat is not None: point = point.field("lat", data.lat)
        if data.lon is not None: point = point.field("lon", data.lon)
        if data.alt_m is not None: point = point.field("alt_m", data.alt_m)
        if data.heading_deg is not None: point = point.field("heading_deg", data.heading_deg)
        if data.velocity_ms is not None: point = point.field("velocity_ms", data.velocity_ms)

        # Ambientes comuns
        if data.temp_c is not None: point = point.field("temp_c", data.temp_c)
        if data.hum_pct is not None: point = point.field("hum_pct", data.hum_pct)

        # Ambiente exclusivos da estação
        if data.pressure_hpa is not None: point = point.field("pressure_hpa", data.pressure_hpa)
        if data.co2_ppm is not None: point = point.field("co2_ppm", data.co2_ppm)
        if data.uv_index is not None: point = point.field("uv_index", data.uv_index)

        # Sistema
        if data.battery_pct is not None: point = point.field("battery_pct", data.battery_pct)
        if data.battery_ok is not None: point = point.field("battery_ok", data.battery_ok)
        if data.rssi_dbm is not None: point = point.field("rssi_dbm", data.rssi_dbm)
        if data.seq is not None: point = point.field("seq", data.seq)

        # Waypoint (drone)
        if data.waypoint_index is not None:
            point = point.field("waypoint_index", data.waypoint_index)

        # ── Timestamp: usa o sensor_ts para posicionamento correto ─────────
        # Isso garante que o ponto aparece no gráfico no momento em que
        # o sensor coletou o dado, não quando chegou no datacenter
        point = point.time(data.sensor_datetime(), WritePrecision.MS)

        self._write_api.write(bucket=self._bucket, record=point)
    
    def query_latency(self,
                      source_type: Optional[str] = None,
                      minutes: int = 60) -> list[dict]:
        """
        Consulta latências do período recente.
        Retorna lista de dicionários com campos time, source_type,
        source_protocol, payload_format, latency_transport_ms, latency_total_ms.
        """
        filter_clause = ""
        if source_type:
            filter_clause = f'|> filter(fn: (r) => r["source_type"] == "{source_type}")'

        flux = f"""
        from(bucket: "{self._bucket}")
          |> range(start: -{minutes}m)
          |> filter(fn: (r) => r["_measurement"] == "telemetria")
          |> filter(fn: (r) => r["_field"] == "latency_transport_ms"
                             or r["_field"] == "latency_total_ms")
          {filter_clause}
          |> pivot(rowKey:["_time"], columnKey: ["_field"], valueColumn: "_value")
          |> keep(columns: ["_time", "source_type", "source_protocol",
                             "payload_format", "latency_transport_ms",
                             "latency_total_ms"])
        """

        tables = self._query_api.query(flux, org=self._org)
        result = []
        for table in tables:
            for record in table.records:
                result.append({
                    "time":                  record.get_time().isoformat(),
                    "source_type":           record.values.get("source_type"),
                    "source_protocol":       record.values.get("source_protocol"),
                    "payload_format":        record.values.get("payload_format"),
                    "latency_transport_ms":  record.values.get("latency_transport_ms"),
                    "latency_total_ms":      record.values.get("latency_total_ms"),
                })
        return result
    
    def query_sensor_data(self,
                          source_type: Optional[str] = None,
                          minutes: int = 60) -> list[dict]:
        """
        Consulta dados de sensores do período recente.
        Retorna dados de temperatura, umidade, etc. para os gráficos.
        """
        filter_clause = ""
        if source_type:
            filter_clause = f'|> filter(fn: (r) => r["source_type"] == "{source_type}")'

        flux = f"""
        from(bucket: "{self._bucket}")
          |> range(start: -{minutes}m)
          |> filter(fn: (r) => r["_measurement"] == "telemetria")
          |> filter(fn: (r) => r["_field"] == "temp_c"
                             or r["_field"] == "hum_pct"
                             or r["_field"] == "pressure_hpa"
                             or r["_field"] == "co2_ppm"
                             or r["_field"] == "uv_index"
                             or r["_field"] == "alt_m"
                             or r["_field"] == "battery_pct"
                             or r["_field"] == "rssi_dbm")
          {filter_clause}
          |> pivot(rowKey:["_time","source_id"], columnKey: ["_field"], valueColumn: "_value")
        """

        tables = self._query_api.query(flux, org=self._org)
        result = []
        for table in tables:
            for record in table.records:
                result.append({
                    "time":         record.get_time().isoformat(),
                    "source_id":    record.values.get("source_id"),
                    "source_type":  record.values.get("source_type"),
                    "temp_c":       record.values.get("temp_c"),
                    "hum_pct":      record.values.get("hum_pct"),
                    "pressure_hpa": record.values.get("pressure_hpa"),
                    "co2_ppm":      record.values.get("co2_ppm"),
                    "uv_index":     record.values.get("uv_index"),
                    "alt_m":        record.values.get("alt_m"),
                    "battery_pct":  record.values.get("battery_pct"),
                    "rssi_dbm":     record.values.get("rssi_dbm"),
                })
        return result
    
    def query_delivery_rate(self, minutes: int = 60) -> dict:
        """
        Calcula taxa de entrega por protocolo detectando gaps no seq.
        """
        flux = f"""
        from(bucket: "{self._bucket}")
          |> range(start: -{minutes}m)
          |> filter(fn: (r) => r["_measurement"] == "telemetria")
          |> filter(fn: (r) => r["_field"] == "seq")
          |> pivot(rowKey:["_time","source_id"], columnKey:["_field"], valueColumn:"_value")
          |> sort(columns: ["_time"])
        """
        tables  = self._query_api.query(flux, org=self._org)
        by_src: dict[str, list[int]] = {}

        for table in tables:
            for record in table.records:
                sid = record.values.get("source_id", "?")
                seq = record.values.get("seq")
                if seq is not None:
                    by_src.setdefault(sid, []).append(int(seq))

        result = {}
        for sid, seqs in by_src.items():
            seqs.sort()
            expected  = seqs[-1] - seqs[0] + 1 if len(seqs) > 1 else 1
            received  = len(seqs)
            lost      = max(0, expected - received)
            rate      = received / expected * 100 if expected > 0 else 0
            result[sid] = {
                "received":      received,
                "lost":          lost,
                "expected":      expected,
                "delivery_rate": round(rate, 2),
            }
        return result
    
    def close(self):
        self._client.close()
