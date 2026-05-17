// ============================================================
//  payload_builder.cpp — Montagem e Validação do Payload JSON
// ============================================================

#include "payload_builder.h"

PayloadBuilder payloadBuilder;

// ------------------------------------------------------------
bool PayloadBuilder::build(char* buffer, size_t bufferSize,
                            const FlightState& fs,
                            const SensorData& sd,
                            uint32_t seqNumber) {

    // Valida antes de montar
    if (!_validate(fs, sd)) {
        Serial.printf("[PAYLOAD] Validação falhou: %s\n", _lastError);
        return false;
    }

    // Monta o documento JSON (Sintaxe atualizada ArduinoJson v7)
    JsonDocument doc;

    // ---- Identificação ----
    doc["id"] = DRONE_ID;
    doc["ts"] = ntpSync.getTimestamp();

    // ---- Dados de voo ----
    JsonObject voo = doc["voo"].to<JsonObject>();
    voo["fase"]      = fs.getPhaseName();
    voo["fase_cod"]  = (uint8_t)fs.getPhase();
    voo["waypoint"]  = fs.getWaypointIndex();
    voo["duracao_s"] = fs.getFlightDuration() / 1000;

    // ---- Posição ----
    JsonObject posicao = doc["posicao"].to<JsonObject>();
    posicao["lat"]     = serialized(String(fs.getLat(), 6));
    posicao["lon"]     = serialized(String(fs.getLon(), 6));
    posicao["alt_m"]   = serialized(String(fs.getAlt(), 1));
    posicao["heading"] = serialized(String(fs.getHeading(), 1));
    posicao["vel_ms"]  = serialized(String(fs.getVelocity(), 1));

    // ---- Dados ambientais ----
    JsonObject ambiente = doc["ambiente"].to<JsonObject>();
    ambiente["temp_c"]  = serialized(String(sd.temp_c, 2));
    ambiente["hum_pct"] = serialized(String(sd.hum_pct, 1));

    // ---- Sistema ----
    JsonObject sistema = doc["sistema"].to<JsonObject>();
    sistema["bateria_pct"] = fs.getBattery();
    sistema["alerta_bat"]  = fs.isBatteryAlert();
    sistema["rssi_dbm"]    = sd.rssi_dbm;
    sistema["seq"]         = seqNumber;

    // Serializa para buffer
    size_t written = serializeJson(doc, buffer, bufferSize);

    if (written == 0 || written >= bufferSize) {
        snprintf(_lastError, sizeof(_lastError),
                 "Erro de serialização (escrito=%d, buf=%d)", written, bufferSize);
        Serial.printf("[PAYLOAD] %s\n", _lastError);
        return false;
    }

    return true;
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