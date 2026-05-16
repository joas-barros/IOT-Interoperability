#pragma once

// ============================================================
//  payload_builder.h — Montagem e Validação do Payload JSON
// ============================================================

#include <Arduino.h>
#include <ArduinoJson.h>
#include "config.h"
#include "flight_state.h"
#include "sensor_sim.h"
#include "ntp_sync.h"

class PayloadBuilder {
public:
    // Constrói o payload JSON a partir do estado atual.
    // Preenche 'buffer' com o JSON serializado.
    // Retorna true se todas as validações passaram.
    bool build(char* buffer, size_t bufferSize,
               const FlightState& fs,
               const SensorData& sd,
               uint32_t seqNumber);

    // Último erro de validação (string descritiva)
    const char* lastError() const { return _lastError; }

private:
    char _lastError[64] = "";

    // Retorna false e preenche _lastError se alguma validação falhar
    bool _validate(const FlightState& fs, const SensorData& sd);
};

extern PayloadBuilder payloadBuilder;