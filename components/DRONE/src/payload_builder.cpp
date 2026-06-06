// ============================================================
//  payload_builder.cpp — Montagem e Validação do Payload JSON
//
//  Schema do payload (15 campos):
//
//  Chave  | Tipo       | Descrição
//  -------|------------|----------------------------------
//  "id"   | tstr       | ID do drone
//  "ts"   | tstr       | Timestamp ISO 8601 UTC (ms)
//  "f"    | tstr       | Fase de voo (ex: "MISSION")
//  "wp"   | uint       | Índice do waypoint atual
//  "lat"  | float64    | Latitude (6 decimais)
//  "lon"  | float64    | Longitude (6 decimais)
//  "alt"  | float32    | Altitude (metros, 1 decimal)
//  "hdg"  | float32    | Heading 0-360° (1 decimal)
//  "vel"  | float32    | Velocidade m/s (1 decimal)
//  "tmp"  | float32    | Temperatura °C (2 decimais)
//  "hum"  | float32    | Umidade % (1 decimal)
//  "bat"  | uint       | Bateria %
//  "bok"  | bool       | true = bateria saudável
//  "rssi" | int        | RSSI Wi-Fi dBm
//  "seq"  | uint       | Número de sequência
//
//  Tamanho CBOR esperado: ~90 bytes
//  Tamanho JSON esperado: ~200 bytes
// ============================================================

#include "payload_builder.h"

PayloadBuilder payloadBuilder;

// ------------------------------------------------------------
bool PayloadBuilder::build(uint8_t* buffer, size_t bufferSize,
                            const FlightState& fs,
                            const SensorData& sd,
                            uint32_t seq) {

    // Valida antes de montar
    if (!_validate(fs, sd)) {
        Serial.printf("[PAYLOAD] Validação falhou: %s\n", _lastError);
        return false;
    }

    #if USE_CBOR
        return _buildCbor(buffer, bufferSize, fs, sd, seq);
    #else
        return _buildJson(buffer, bufferSize, fs, sd, seq);
    #endif
}

// ------------------------------------------------------------
bool PayloadBuilder::_validate(const FlightState& fs, const SensorData& sd) {

    // Coordenadas GPS
    if (fs.getLat() < -90.0 || fs.getLat() > 90.0) {
        snprintf(_lastError, sizeof(_lastError),
                 "lat inválida: %.6f", fs.getLat());
        return false;
    }
    if (fs.getLon() < -180.0 || fs.getLon() > 180.0) {
        snprintf(_lastError, sizeof(_lastError),
                 "lon inválida: %.6f", fs.getLon());
        return false;
    }

    // Altitude
    if (fs.getAlt() < -10.0f || fs.getAlt() > 500.0f) {
        snprintf(_lastError, sizeof(_lastError),
                 "altitude inválida: %.1f m", fs.getAlt());
        return false;
    }

    // Temperatura
    if (sd.temp_c < -20.0f || sd.temp_c > 60.0f) {
        snprintf(_lastError, sizeof(_lastError),
                 "temperatura inválida: %.2f°C", sd.temp_c);
        return false;
    }

    // Umidade
    if (sd.hum_pct < 0.0f || sd.hum_pct > 100.0f) {
        snprintf(_lastError, sizeof(_lastError),
                 "umidade inválida: %.1f%%", sd.hum_pct);
        return false;
    }

    // Bateria
    if (fs.getBattery() > 100) {
        snprintf(_lastError, sizeof(_lastError),
                 "bateria inválida: %d%%", fs.getBattery());
        return false;
    }

    return true;
}

// ── Serialização CBOR ─────────────────────────────────────────

#if USE_CBOR

bool PayloadBuilder::_buildCbor(uint8_t* buffer, size_t bufferSize,
                                  const FlightState& fs,
                                  const SensorData& sd,
                                  uint32_t seq) {

    CborWriter w(buffer, bufferSize);

    // Map com CBOR_MAP_FIELDS pares chave/valor
    w.writeMapOpen(CBOR_MAP_FIELDS);

    // ── Identificação ──
    w.writeTextString("id");   w.writeTextString(DRONE_ID);
    w.writeTextString("ts");   w.writeTextString(ntpSync.getTimestamp().c_str());

    // ── Voo ──
    w.writeTextString("f");    w.writeTextString(fs.getPhaseName());
    w.writeTextString("wp");   w.writeUInt(fs.getWaypointIndex());

    // ── Posição ──
    // lat/lon usam double (float64) — precisão de 6 casas decimais
    // alt, hdg, vel usam float (float32) — precisão suficiente
    w.writeTextString("lat");  w.writeDouble(fs.getLat());
    w.writeTextString("lon");  w.writeDouble(fs.getLon());
    w.writeTextString("alt");  w.writeFloat(fs.getAlt());
    w.writeTextString("hdg");  w.writeFloat(fs.getHeading());
    w.writeTextString("vel");  w.writeFloat(fs.getVelocity());

    // ── Ambiente ──
    w.writeTextString("tmp");  w.writeFloat(sd.temp_c);
    w.writeTextString("hum");  w.writeFloat(sd.hum_pct);

    // ── Sistema ──
    w.writeTextString("bat");  w.writeUInt(fs.getBattery());
    w.writeTextString("bok");  w.writeBool(!fs.isBatteryAlert());
    w.writeTextString("rssi"); w.writeInt(sd.rssi_dbm);
    w.writeTextString("seq");  w.writeUInt(seq);

    if (!w.isOk()) {
        snprintf(_lastError, sizeof(_lastError),
                 "CBOR overflow (buf=%d)", bufferSize);
        Serial.printf("[PAYLOAD] %s\n", _lastError);
        return false;
    }

    _lastSize = w.length();

    // Loga tamanho para comparação com JSON no artigo
    Serial.printf("[PAYLOAD] CBOR | %d bytes | seq=%lu\n", _lastSize, seq);

    return true;
}

// ── Serialização JSON (modo debug) ───────────────────────────

#else

bool PayloadBuilder::_buildJson(uint8_t* buffer, size_t bufferSize,
                                 const FlightState& fs,
                                 const SensorData& sd,
                                 uint32_t seq) {

    StaticJsonDocument<JSON_DOC_SIZE> doc;

    doc["id"]   = DRONE_ID;
    doc["ts"]   = ntpSync.getTimestamp();
    doc["f"]    = fs.getPhaseName();
    doc["wp"]   = fs.getWaypointIndex();
    doc["lat"]  = serialized(String(fs.getLat(), 6));
    doc["lon"]  = serialized(String(fs.getLon(), 6));
    doc["alt"]  = serialized(String(fs.getAlt(), 1));
    doc["hdg"]  = serialized(String(fs.getHeading(), 1));
    doc["vel"]  = serialized(String(fs.getVelocity(), 1));
    doc["tmp"]  = serialized(String(sd.temp_c, 2));
    doc["hum"]  = serialized(String(sd.hum_pct, 1));
    doc["bat"]  = fs.getBattery();
    doc["bok"]  = !fs.isBatteryAlert();
    doc["rssi"] = sd.rssi_dbm;
    doc["seq"]  = seq;

    size_t written = serializeJson(doc, (char*)buffer, bufferSize);

    if (written == 0 || written >= bufferSize) {
        snprintf(_lastError, sizeof(_lastError),
                 "JSON overflow (written=%d, buf=%d)", written, bufferSize);
        Serial.printf("[PAYLOAD] %s\n", _lastError);
        return false;
    }

    _lastSize = written;
    Serial.printf("[PAYLOAD] JSON | %d bytes | seq=%lu | %s\n",
                  _lastSize, seq, (char*)buffer);

    return true;
}

#endif