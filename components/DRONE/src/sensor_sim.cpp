// ============================================================
//  sensor_sim.cpp — Simulação de Dados dos Sensores
// ============================================================

#include "sensor_sim.h"
#include <math.h>

SensorSim sensorSim;

// ------------------------------------------------------------
void SensorSim::begin() {
    randomSeed(RANDOM_SEED == 0 ? esp_random() : RANDOM_SEED);
    Serial.printf("[SENSOR] Inicializado | Semente: %d | "
                  "Temp base: %.1f°C | Hum base: %.1f%%\n",
                  RANDOM_SEED, SENSOR_TEMP_BASE, SENSOR_HUM_BASE);
}

// ------------------------------------------------------------
SensorData SensorSim::read(float alt_m, int8_t rssi) {
    SensorData d;

    // Temperatura: base + gradiente adiabático + ruído
    // Gradiente: -0.0065°C por metro → menos quente em altitude
    float altEffect = alt_m * ADIABATIC_LAPSE_RATE;
    d.temp_c = SENSOR_TEMP_BASE - altEffect + _noise(SENSOR_TEMP_AMPLITUDE);

    // Umidade: inversamente correlacionada com temperatura
    // +1°C acima da base → -0.5% de umidade
    float tempDeviation = d.temp_c - SENSOR_TEMP_BASE;
    d.hum_pct = SENSOR_HUM_BASE - (tempDeviation * 0.5f) +
                _noise(SENSOR_HUM_AMPLITUDE);

    // Limita umidade entre 0% e 100%
    d.hum_pct = constrain(d.hum_pct, 0.0f, 100.0f);

    // RSSI real do Wi-Fi (passado pelo chamador)
    d.rssi_dbm = rssi;

    return d;
}

// ------------------------------------------------------------
float SensorSim::_noise(float amplitude) {
    // Aproximação gaussiana: soma de 4 uniformes normalizados
    // Resulta em distribuição aproximadamente normal centrada em 0
    float sum = 0.0f;
    for (int i = 0; i < 4; i++) {
        sum += (float)random(-1000, 1001) / 1000.0f;
    }
    // sum em [-4, 4], dividido por 4 → [-1, 1], multiplicado por amplitude
    return (sum / 4.0f) * amplitude;
}