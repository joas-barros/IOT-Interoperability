#include <Arduino.h>
#include <math.h>
#include "config.h"
#include "flight_state.h"

void setup() {
    Serial.begin(115200);
    delay(1000);

    Serial.println("========================================");
    Serial.println("  TESTE 3 — Flight State Machine");
    Serial.println("  (Sem Wi-Fi necessário)");
    Serial.println("========================================");
    Serial.println("Fases esperadas:");
    Serial.println("  IDLE(10s) → TAKEOFF → MISSION/HOVER → RETURN → LANDING → IDLE");
    Serial.println("----------------------------------------");

    flightState.begin();
}

void loop() {
    static uint32_t lastPrint    = 0;
    static uint32_t lastPhaseLog = 0;
    static FlightPhase lastPhase = PHASE_IDLE;
    static uint32_t ciclo        = 0;

    // Atualiza a máquina de estados
    bool transitioned = flightState.update();

    // Loga transição de fase imediatamente
    if (transitioned) {
        Serial.println("========================================");
        Serial.printf("  >> TRANSIÇÃO → %s <<\n", flightState.getPhaseName());
        Serial.println("========================================");
    }

    // Imprime estado detalhado a cada 1s
    if (millis() - lastPrint >= 1000) {
        lastPrint = millis();
        ciclo++;

        FlightPhase p = flightState.getPhase();

        Serial.printf("[%04lu] Fase=%-8s | WP=%d | "
                      "Lat=%.6f Lon=%.6f Alt=%.1fm | "
                      "Vel=%.1fm/s Hdg=%.0f° | "
                      "Bat=%d%% %s\n",
                      ciclo,
                      flightState.getPhaseName(),
                      flightState.getWaypointIndex(),
                      flightState.getLat(),
                      flightState.getLon(),
                      flightState.getAlt(),
                      flightState.getVelocity(),
                      flightState.getHeading(),
                      flightState.getBattery(),
                      flightState.isBatteryAlert() ? "[ALERTA BAT!]" : "");

        // Detecta fim de missão
        if (flightState.isMissionDone()) {
            Serial.println("========================================");
            Serial.println("  MISSÃO CONCLUÍDA!");
            Serial.printf("  Duração total: %lums\n",
                          flightState.getFlightDuration());
            Serial.println("  RESULTADO: Verifique se todas as fases");
            Serial.println("             foram percorridas => PASSOU");
            Serial.println("========================================");
            while (true) delay(5000);
        }
    }

    delay(50);  // 20Hz de atualização da máquina de estados
}