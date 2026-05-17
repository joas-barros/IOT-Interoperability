#pragma once

// ============================================================
//  flight_state.h — Máquina de Estados de Voo do Drone
// ============================================================

#include <Arduino.h>
#include "config.h"

class FlightState {
public:
    // Inicializa no estado IDLE com posição de base
    void begin();

    // Avança a máquina de estados.
    // Deve ser chamado em cada ciclo do loop().
    // Retorna true se houve transição de fase neste ciclo.
    bool update();

    // ---- Getters de estado ----
    FlightPhase getPhase()         const { return _phase; }
    const char* getPhaseName()     const { return PHASE_NAMES[_phase]; }
    uint8_t     getWaypointIndex() const { return _wpIndex; }

    // ---- Getters de posição e movimento ----
    double  getLat() const;
    double  getLon() const;
    float   getAlt()      const { return _alt; }
    float   getVelocity() const { return _velocity; }
    float   getHeading()  const { return _heading; }

    // ---- Getters de sistema ----
    uint8_t getBattery()     const { return (uint8_t)_battery; }
    bool    isBatteryAlert() const { return _battery <= BATTERY_ALERT_PCT; }
    bool    isMissionDone()  const { return _missionDone; }

    // Tempo decorrido na fase atual (ms)
    uint32_t getPhaseElapsed() const { return millis() - _phaseStartMs; }

    // Tempo total de voo (ms) — começa na decolagem
    uint32_t getFlightDuration() const;

private:
    FlightPhase _phase       = PHASE_IDLE;
    uint8_t     _wpIndex     = 0;
    bool        _missionDone = false;

    // Posição atual
    double _lat = BASE_LAT;
    double _lon = BASE_LON;
    float  _alt = 0.0f;

    // Movimento
    float _velocity = 0.0f;
    float _heading  = 0.0f;

    // Sistema
    float    _battery       = BATTERY_INITIAL;
    uint32_t _phaseStartMs  = 0;
    uint32_t _takeoffStartMs = 0;
    uint32_t _lastUpdateMs  = 0;

    // ---- Métodos de transição ----
    void _transitionTo(FlightPhase next);

    // ---- Métodos de atualização por fase ----
    void _updateIdle();
    void _updateTakeoff();
    void _updateMission();
    void _updateHover();
    void _updateReturn();
    void _updateLanding();

    // ---- Helpers ----
    // Avança posição em direção a um alvo (lat/lon) com velocidade dada
    // Retorna true se chegou ao destino (distância < thresholdM)
    bool _moveToward(double targetLat, double targetLon,
                     float speedMs, float thresholdM, float dt);

    // Calcula distância em metros entre dois pontos (Haversine simplificado)
    float _distanceM(double lat1, double lon1,
                     double lat2, double lon2) const;

    // Calcula heading (0-360°) de (lat1,lon1) para (lat2,lon2)
    float _calcHeading(double lat1, double lon1,
                       double lat2, double lon2) const;

    // Drena bateria baseado na fase e tempo decorrido
    void _drainBattery(float dt);
};

extern FlightState flightState;