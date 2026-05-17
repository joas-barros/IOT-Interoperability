// ============================================================
//  flight_state.cpp — Máquina de Estados de Voo
// ============================================================

#include "flight_state.h"
#include <math.h>

FlightState flightState;

// Constantes de física de voo
static const float TAKEOFF_SPEED_MS   = 4.0f;   // velocidade de subida (m/s)
static const float LANDING_SPEED_MS   = 2.0f;   // velocidade de descida (m/s)
static const float MISSION_SPEED_MS   = 12.0f;  // velocidade horizontal em missão (m/s)
static const float RETURN_SPEED_MS    = 15.0f;  // velocidade de retorno (m/s)
static const float WAYPOINT_THRESH_M  = 5.0f;   // distância para considerar WP atingido (m)

// ------------------------------------------------------------
void FlightState::begin() {
    _phase        = PHASE_IDLE;
    _lat          = BASE_LAT;
    _lon          = BASE_LON;
    _alt          = 0.0f;
    _velocity     = 0.0f;
    _heading      = 0.0f;
    _battery      = BATTERY_INITIAL;
    _wpIndex      = 0;
    _missionDone  = false;
    _phaseStartMs = millis();
    _lastUpdateMs = millis();

    Serial.printf("[VÔOO] Iniciado em IDLE | Base: %.6f, %.6f\n",
                  BASE_LAT, BASE_LON);
}

// ------------------------------------------------------------
bool FlightState::update() {
    uint32_t now = millis();
    float dt = (now - _lastUpdateMs) / 1000.0f;  // delta tempo em segundos
    _lastUpdateMs = now;

    // Limita dt para evitar saltos grandes após pausas longas
    if (dt > 1.0f) dt = 1.0f;

    FlightPhase prevPhase = _phase;

    // Drena bateria baseada na fase atual
    _drainBattery(dt);

    // Retorno forçado por bateria crítica
    if (_battery <= BATTERY_ALERT_PCT &&
        _phase != PHASE_RETURN &&
        _phase != PHASE_LANDING &&
        _phase != PHASE_IDLE) {
        Serial.println("[VÔOO] ALERTA: Bateria crítica! Retorno forçado.");
        _transitionTo(PHASE_RETURN);
    }

    // Atualiza a fase atual
    switch (_phase) {
        case PHASE_IDLE:    _updateIdle();    break;
        case PHASE_TAKEOFF: _updateTakeoff(); break;
        case PHASE_MISSION: _updateMission(); break;
        case PHASE_HOVER:   _updateHover();   break;
        case PHASE_RETURN:  _updateReturn();  break;
        case PHASE_LANDING: _updateLanding(); break;
    }

    return (_phase != prevPhase);
}

// ------------------------------------------------------------
void FlightState::_transitionTo(FlightPhase next) {
    Serial.printf("[VÔOO] Transição: %s → %s | Bat: %d%% | Alt: %.1fm\n",
                  PHASE_NAMES[_phase], PHASE_NAMES[next],
                  (uint8_t)_battery, _alt);
    _phase = next;
    _phaseStartMs = millis();
}

// ------------------------------------------------------------
void FlightState::_updateIdle() {
    _velocity = 0.0f;
    // Aguarda PHASE_DURATION_MS[PHASE_IDLE] antes de decolar
    if (getPhaseElapsed() >= PHASE_DURATION_MS[PHASE_IDLE]) {
        _takeoffStartMs = millis();
        _transitionTo(PHASE_TAKEOFF);
    }
}

// ------------------------------------------------------------
void FlightState::_updateTakeoff() {
    // Sobe linearmente até MISSION_ALT_M
    float elapsed = getPhaseElapsed() / 1000.0f;  // segundos
    float targetAlt = MISSION_ALT_M;

    _alt = min(_alt + TAKEOFF_SPEED_MS * (millis() - _lastUpdateMs + 1) / 1000.0f,
               targetAlt);
    _velocity = TAKEOFF_SPEED_MS;
    _heading  = 0.0f;  // subindo verticalmente

    // Transição quando altitude alvo atingida (±2m)
    if (_alt >= targetAlt - 2.0f) {
        _alt = targetAlt;
        _wpIndex = 0;
        _transitionTo(PHASE_MISSION);
    }
}

// ------------------------------------------------------------
void FlightState::_updateMission() {
    if (_wpIndex >= NUM_WAYPOINTS) {
        // Todos os waypoints visitados → retornar
        _transitionTo(PHASE_RETURN);
        return;
    }

    const Waypoint& wp = WAYPOINTS[_wpIndex];
    float dt = (millis() - _lastUpdateMs + 1) / 1000.0f;

    bool arrived = _moveToward(wp.lat, wp.lon,
                               MISSION_SPEED_MS, WAYPOINT_THRESH_M, dt);

    if (arrived) {
        Serial.printf("[VÔOO] Waypoint %d atingido | %.6f, %.6f\n",
                      _wpIndex, wp.lat, wp.lon);

        if (wp.isHover) {
            _transitionTo(PHASE_HOVER);
        } else {
            _wpIndex++;
            if (_wpIndex >= NUM_WAYPOINTS) {
                _transitionTo(PHASE_RETURN);
            }
        }
    }
}

// ------------------------------------------------------------
void FlightState::_updateHover() {
    _velocity = 0.0f;

    const Waypoint& wp = WAYPOINTS[_wpIndex];
    uint32_t hoverDur = wp.hoverDuration_ms > 0
                        ? wp.hoverDuration_ms
                        : PHASE_DURATION_MS[PHASE_HOVER];

    if (getPhaseElapsed() >= hoverDur) {
        _wpIndex++;
        if (_wpIndex >= NUM_WAYPOINTS) {
            _transitionTo(PHASE_RETURN);
        } else {
            _transitionTo(PHASE_MISSION);
        }
    }
}

// ------------------------------------------------------------
void FlightState::_updateReturn() {
    float dt = (millis() - _lastUpdateMs + 1) / 1000.0f;

    bool arrived = _moveToward(BASE_LAT, BASE_LON,
                               RETURN_SPEED_MS, WAYPOINT_THRESH_M, dt);

    if (arrived) {
        _lat = BASE_LAT;
        _lon = BASE_LON;
        _transitionTo(PHASE_LANDING);
    }
}

// ------------------------------------------------------------
void FlightState::_updateLanding() {
    // Desce linearmente até 0
    float dt = (millis() - _lastUpdateMs + 1) / 1000.0f;
    _alt = max(0.0f, _alt - LANDING_SPEED_MS * dt);
    _velocity = LANDING_SPEED_MS;

    if (_alt <= 0.1f) {
        _alt         = 0.0f;
        _velocity    = 0.0f;
        _missionDone = true;
        _transitionTo(PHASE_IDLE);
        Serial.println("[VÔOO] Missão concluída. Drone em solo.");
    }
}

// ------------------------------------------------------------
bool FlightState::_moveToward(double targetLat, double targetLon,
                               float speedMs, float thresholdM, float dt) {
    float dist = _distanceM(_lat, _lon, targetLat, targetLon);

    if (dist <= thresholdM) {
        _lat      = targetLat;
        _lon      = targetLon;
        _velocity = 0.0f;
        return true;
    }

    _heading  = _calcHeading(_lat, _lon, targetLat, targetLon);
    _velocity = min(speedMs, dist / dt);  // não ultrapassa o destino

    // Converte velocidade (m/s) para delta de graus
    float moveDist = speedMs * dt;
    float fraction = moveDist / dist;
    fraction = min(fraction, 1.0f);

    _lat += (targetLat - _lat) * fraction;
    _lon += (targetLon - _lon) * fraction;

    return false;
}

// ------------------------------------------------------------
float FlightState::_distanceM(double lat1, double lon1,
                               double lat2, double lon2) const {
    // Aproximação plana (válida para distâncias < 1km)
    const float LAT_TO_M = 111000.0f;
    float dLat = (float)(lat2 - lat1) * LAT_TO_M;
    float dLon = (float)(lon2 - lon1) * LAT_TO_M * cos(lat1 * M_PI / 180.0f);
    return sqrt(dLat * dLat + dLon * dLon);
}

// ------------------------------------------------------------
float FlightState::_calcHeading(double lat1, double lon1,
                                 double lat2, double lon2) const {
    float dLon = (float)(lon2 - lon1);
    float dLat = (float)(lat2 - lat1);
    float angle = atan2(dLon, dLat) * 180.0f / M_PI;
    return fmod(angle + 360.0f, 360.0f);
}

// ------------------------------------------------------------
void FlightState::_drainBattery(float dt) {
    if (_phase == PHASE_IDLE && _battery >= BATTERY_INITIAL) return;
    _battery = max(0.0f, _battery - PHASE_BATTERY_DRAIN[_phase] * dt);
}

// ------------------------------------------------------------
uint32_t FlightState::getFlightDuration() const {
    if (_phase == PHASE_IDLE) return 0;
    return millis() - _takeoffStartMs;
}