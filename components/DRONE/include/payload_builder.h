#pragma once

// ============================================================
//  payload_builder.h — Montagem e Validação do Payload JSON
// ============================================================

#include <Arduino.h>
#include "config.h"
#include "flight_state.h"
#include "sensor_sim.h"
#include "ntp_sync.h"

#if USE_CBOR
  #include "../lib/tinycbor/tinycbor_arduino.h"
#else
  #include <ArduinoJson.h>
#endif

class PayloadBuilder {
public:
    // Constrói o payload JSON a partir do estado atual.
    // Preenche 'buffer' com o JSON serializado.
    // Retorna true se todas as validações passaram.
    bool build(uint8_t* buffer, size_t bufferSize,
               const FlightState& fs,
               const SensorData& sd,
               uint32_t seq);

    // Último erro de validação (string descritiva)
    const char* lastError() const { return _lastError; }

    /** Tamanho em bytes do último payload gerado. */
    size_t lastSize() const { return _lastSize; }
 
    /** Content-Format CoAP correto para o modo atual. */
    uint16_t contentFormat() const {
        #if USE_CBOR
                return COAP_CONTENT_FORMAT_CBOR;
        #else
                return COAP_CONTENT_FORMAT_JSON;
        #endif
    }

    /** Descrição do modo ativo — para log. */
    const char* modeName() const {
        #if USE_CBOR
            return "CBOR";
        #else
            return "JSON";
        #endif
    }

private:
    size_t _lastSize    = 0;

    char _lastError[64] = "";

    // Retorna false e preenche _lastError se alguma validação falhar
    bool _validate(const FlightState& fs, const SensorData& sd);

    #if USE_CBOR
        bool _buildCbor(uint8_t* buffer, size_t bufferSize,
                        const FlightState& fs,
                        const SensorData& sd,
                        uint32_t seq);
    #else
        bool _buildJson(uint8_t* buffer, size_t bufferSize,
                        const FlightState& fs,
                        const SensorData& sd,
                        uint32_t seq);
    #endif
};

extern PayloadBuilder payloadBuilder;