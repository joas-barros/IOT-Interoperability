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

    // Documento JSON flat (sem sub-objetos) — menor overhead de chaves
    JsonDocument doc;

    doc["id"]     = DRONE_ID;
    doc["ts"]     = ntpSync.getTimestamp();

    // Voo
    doc["f"]      = fs.getPhaseName();
    doc["wp"]     = fs.getWaypointIndex();

    // Posição — lat/lon precisam de 6 decimais (~11cm precisão)
    doc["lat"]    = serialized(String(fs.getLat(), 6));
    doc["lon"]    = serialized(String(fs.getLon(), 6));
    doc["alt"]    = serialized(String(fs.getAlt(), 1));
    doc["hdg"]    = serialized(String(fs.getHeading(), 1));
    doc["vel"]    = serialized(String(fs.getVelocity(), 1));

    // Ambiente
    doc["tmp"]    = serialized(String(sd.temp_c, 2));
    doc["hum"]    = serialized(String(sd.hum_pct, 1));

    // Sistema
    doc["bat"]    = fs.getBattery();
    doc["bat_ok"] = !fs.isBatteryAlert();   // true = bateria saudável
    doc["rssi"]   = sd.rssi_dbm;
    doc["seq"]    = seqNumber;

    // Serializa para buffer
    size_t written = serializeJson(doc, buffer, bufferSize);

    if (written == 0 || written >= bufferSize) {
        snprintf(_lastError, sizeof(_lastError),
                 "Serialização falhou (written=%d, bufSize=%d)",
                 (int)written, (int)bufferSize);
        Serial.printf("[PAYLOAD] %s\n", _lastError);
        return false;
    }

    // Avisa se estiver perto do limite do buffer CoAP
    if (written > 180) {
        Serial.printf("[PAYLOAD] AVISO: payload=%d bytes (limite CoAP ~490)\n",
                      (int)written);
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