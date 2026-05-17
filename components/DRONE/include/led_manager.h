#pragma once

// ============================================================
//  led_manager.h — Gerenciador do LED RGB (NeoPixel)
// ============================================================

#include <Arduino.h>
#include <Adafruit_NeoPixel.h>
#include "flight_state.h"

// O pino 48 é o padrão para o LED RGB embutido no ESP32-S3 DevKit
#define PIN_NEOPIXEL 48
#define NUM_PIXELS    1

class LedManager {
public:
    // Inicializa o LED
    void begin();

    // Chamado no loop principal para apagar o LED automaticamente após o flash
    void loop();

    // Acende o LED com a cor específica da fase de voo atual
    void flash(FlightPhase phase);

private:
    Adafruit_NeoPixel _pixels{NUM_PIXELS, PIN_NEOPIXEL, NEO_GRB + NEO_KHZ800};
    
    bool     _isOn = false;
    uint32_t _turnOnTime = 0;
    
    // Tempo que o LED fica aceso a cada flash (em milissegundos)
    const uint32_t BLINK_DURATION_MS = 500; 

    // Mapeia a fase de voo para uma cor hexadecimal RGB
    uint32_t _getColorForPhase(FlightPhase phase);
};

// Instância global
extern LedManager ledManager;