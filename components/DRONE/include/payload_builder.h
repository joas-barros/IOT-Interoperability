#pragma once

// ============================================================
//  payload_builder.h — Montagem e Validação do Payload JSON
// ============================================================

#include <Arduino.h>
#include "config.h"
#include "flight_state.h"
#include "sensor_sim.h"
#include "ntp_sync.h"
#include "coap_client.h"
#include "../lib/tinycbor/tinycbor_arduino.h"
#include <ArduinoJson.h>

class PayloadBuilder {
public:
    // Função para inverter o formato (JSON -> CBOR -> JSON)
    void toggleFormat() { _useCbor = !_useCbor; }

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
    COAP_CONTENT_TYPE contentFormat() const {
        return _useCbor ? COAP_APPLICATION_CBOR : COAP_APPLICATION_JSON;
    }

    /** Descrição do modo ativo — para log. */
    const char* modeName() const {
            return _useCbor ? "CBOR" : "JSON";
        }

private:
    bool   _useCbor     = true;
    size_t _lastSize    = 0;

    char _lastError[64] = "";

    // Retorna false e preenche _lastError se alguma validação falhar
    bool _validate(const FlightState& fs, const SensorData& sd);

    bool _buildCbor(uint8_t* buffer, size_t bufferSize, const FlightState& fs, const SensorData& sd, uint32_t seq);
    bool _buildJson(uint8_t* buffer, size_t bufferSize, const FlightState& fs, const SensorData& sd, uint32_t seq);
};

extern PayloadBuilder payloadBuilder;