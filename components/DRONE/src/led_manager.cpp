// ============================================================
//  led_manager.cpp
// ============================================================

#include "led_manager.h"

LedManager ledManager;

void LedManager::begin() {
    _pixels.begin();
    _pixels.setBrightness(20); 
    _pixels.clear();
    _pixels.show();
}

void LedManager::loop() {
    // Se o LED estiver aceso e o tempo de brilho já passou, apaga.
    if (_isOn && (millis() - _turnOnTime >= BLINK_DURATION_MS)) {
        _pixels.clear();
        _pixels.show();
        _isOn = false;
    }
}

void LedManager::flash(FlightPhase phase) {
    uint32_t color = _getColorForPhase(phase);
    
    _pixels.setPixelColor(0, color);
    _pixels.show();
    
    _turnOnTime = millis();
    _isOn = true;
}

uint32_t LedManager::_getColorForPhase(FlightPhase phase) {
    // Definindo as cores com o método .Color(Red, Green, Blue) (0 a 255)
    switch(phase) {
        case PHASE_IDLE:    return _pixels.Color(255, 255, 255); // Branco (Aguardando)
        case PHASE_TAKEOFF: return _pixels.Color(0, 255, 255);   // Ciano (Subindo)
        case PHASE_MISSION: return _pixels.Color(0, 255, 0);     // Verde (Tudo OK)
        case PHASE_HOVER:   return _pixels.Color(255, 0, 255);   // Magenta/Roxo (Inspecionando)
        case PHASE_RETURN:  return _pixels.Color(255, 128, 0);   // Laranja (Voltando pra base)
        case PHASE_LANDING: return _pixels.Color(255, 0, 0);     // Vermelho (Pousando/Cuidado)
        default:            return _pixels.Color(0, 0, 0);       // Apagado
    }
}