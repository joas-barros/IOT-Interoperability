#pragma once

// ============================================================
//  sensor_sim.h — Simulação de Dados dos Sensores do Drone
// ============================================================

#include <Arduino.h>
#include "config.h"

struct SensorData {
    float   temp_c;      // Temperatura (°C) com gradiente de altitude
    float   hum_pct;     // Umidade relativa (%)
    int8_t  rssi_dbm;    // RSSI Wi-Fi real (dBm)
};

class SensorSim {
public:
    // Inicializa o gerador com a semente de RANDOM_SEED
    void begin();

    // Gera uma nova leitura baseada no estado atual de voo
    // alt_m  : altitude atual (afeta temperatura)
    // rssi   : RSSI real do Wi-Fi (passado pelo WiFiManager)
    SensorData read(float alt_m, int8_t rssi);

private:
    // Ruído gaussiano aproximado via soma de uniformes (Box-Muller simplificado)
    // Retorna valor em [-amplitude, +amplitude]
    float _noise(float amplitude);
};

extern SensorSim sensorSim;