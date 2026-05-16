#pragma once

// ============================================================
//  ntp_sync.h — Sincronização de Tempo via NTP
// ============================================================

#include <Arduino.h>
#include <WiFiUdp.h>
#include <NTPClient.h>

class NTPSync {
public:
    // Inicializa e faz a primeira sincronização NTP.
    // Deve ser chamado no setup(), após conectar ao Wi-Fi.
    // Retorna true se sincronizou com sucesso.
    bool begin();

    // Atualiza o tempo NTP periodicamente.
    // Deve ser chamado no loop() — só executa a cada NTP_UPDATE_MS.
    void update();

    // Retorna timestamp ISO 8601 com milissegundos em UTC.
    // Formato: "2026-04-05T14:30:00.123Z"
    String getTimestamp();

    // Retorna Unix epoch em segundos
    unsigned long getEpoch();

    // Retorna true se já sincronizou ao menos uma vez
    bool isSynced();

private:
    WiFiUDP   _udp;
    NTPClient* _client = nullptr;

    bool     _synced          = false;
    uint32_t _lastUpdate      = 0;
    uint32_t _bootEpochOffset = 0;  // segundos desde epoch no momento do boot

    // Retorna milissegundos "dentro do segundo" atual
    // Combina NTP (segundos) com millis() (ms desde boot)
    uint16_t _getMillis();
};

extern NTPSync ntpSync;