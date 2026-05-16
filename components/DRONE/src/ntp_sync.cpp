// ============================================================
//  ntp_sync.cpp — Sincronização de Tempo via NTP
// ============================================================

#include "ntp_sync.h"
#include "config.h"

NTPSync ntpSync;

// ------------------------------------------------------------
bool NTPSync::begin() {
    Serial.println("[NTP] Iniciando sincronização...");

    _client = new NTPClient(_udp, NTP_SERVER, UTC_OFFSET_SEC, NTP_UPDATE_MS);
    _client->begin();

    // Tenta sincronizar até 5 vezes
    for (int i = 0; i < 5; i++) {
        if (_client->update()) {
            _synced          = true;
            _lastUpdate      = millis();
            // Guarda o epoch no momento do boot para calcular ms precisos
            _bootEpochOffset = _client->getEpochTime();

            Serial.printf("[NTP] Sincronizado! Epoch: %lu\n", _bootEpochOffset);
            Serial.printf("[NTP] Timestamp: %s\n", getTimestamp().c_str());
            return true;
        }
        Serial.printf("[NTP] Tentativa %d falhou, aguardando...\n", i + 1);
        delay(2000);
    }

    Serial.println("[NTP] AVISO: Não foi possível sincronizar. Usando millis() como fallback.");
    return false;
}

// ------------------------------------------------------------
void NTPSync::update() {
    if (!_client) return;

    if (millis() - _lastUpdate >= NTP_UPDATE_MS) {
        if (_client->update()) {
            _lastUpdate = millis();
            Serial.println("[NTP] Tempo atualizado.");
        }
    }
}

// ------------------------------------------------------------
String NTPSync::getTimestamp() {
    char buf[30];

    if (_synced && _client) {
        unsigned long epoch = _client->getEpochTime();
        uint16_t ms = _getMillis();

        // Converte epoch para struct tm (UTC)
        time_t t = (time_t)epoch;
        struct tm* tm_info = gmtime(&t);

        snprintf(buf, sizeof(buf),
                 "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
                 tm_info->tm_year + 1900,
                 tm_info->tm_mon  + 1,
                 tm_info->tm_mday,
                 tm_info->tm_hour,
                 tm_info->tm_min,
                 tm_info->tm_sec,
                 ms);
    } else {
        // Fallback: tempo relativo ao boot (para testes sem Wi-Fi)
        unsigned long total_ms = millis();
        unsigned long s  = total_ms / 1000;
        unsigned long ms = total_ms % 1000;
        snprintf(buf, sizeof(buf), "1970-01-01T00:%02lu:%02lu.%03luZ",
                 (s / 60) % 60, s % 60, ms);
    }

    return String(buf);
}

// ------------------------------------------------------------
unsigned long NTPSync::getEpoch() {
    if (_synced && _client) {
        return _client->getEpochTime();
    }
    return millis() / 1000;
}

// ------------------------------------------------------------
bool NTPSync::isSynced() {
    return _synced;
}

// ------------------------------------------------------------
uint16_t NTPSync::_getMillis() {
    // millis() % 1000 dá os ms dentro do segundo atual
    // (aproximação suficiente para experimentos de latência em ms)
    return (uint16_t)(millis() % 1000);
}