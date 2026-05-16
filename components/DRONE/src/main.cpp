#include <Arduino.h>
#include "config.h"
#include "sensor_sim.h"

void setup() {
    Serial.begin(115200);
    delay(1000);

    Serial.println("========================================");
    Serial.println("  TESTE 4 — Sensor Simulation");
    Serial.println("  (Sem Wi-Fi necessário)");
    Serial.println("========================================");

    sensorSim.begin();

    // Estatísticas
    float minTemp = 999, maxTemp = -999;
    float minHum  = 999, maxHum  = -999;
    float sumTemp = 0,   sumHum  = 0;
    int   outliers = 0;

    Serial.println("--- Teste com altitude fixa (0m) ---");
    for (int i = 0; i < 20; i++) {
        SensorData d = sensorSim.read(0.0f, -65);
        Serial.printf("[%02d] alt=  0m | temp=%5.2f°C | hum=%5.2f%%\n",
                      i + 1, d.temp_c, d.hum_pct);
        minTemp = min(minTemp, d.temp_c);
        maxTemp = max(maxTemp, d.temp_c);
        minHum  = min(minHum,  d.hum_pct);
        maxHum  = max(maxHum,  d.hum_pct);
        sumTemp += d.temp_c;
        sumHum  += d.hum_pct;
        if (d.temp_c < -20 || d.temp_c > 60) outliers++;
        if (d.hum_pct < 0  || d.hum_pct > 100) outliers++;
        delay(100);
    }

    Serial.println("\n--- Teste com altitude de missão (80m) ---");
    float expectedTempDrop = 80.0f * ADIABATIC_LAPSE_RATE;
    Serial.printf("Queda esperada por altitude: %.2f°C\n", expectedTempDrop);

    for (int i = 0; i < 10; i++) {
        SensorData d = sensorSim.read(MISSION_ALT_M, -72);
        Serial.printf("[%02d] alt=%3.0fm | temp=%5.2f°C | hum=%5.2f%% | rssi=%d\n",
                      i + 1, MISSION_ALT_M, d.temp_c, d.hum_pct, d.rssi_dbm);
        delay(100);
    }

    Serial.println("\n--- Resumo Estatístico (altitude 0m) ---");
    Serial.printf("Temp: min=%.2f max=%.2f media=%.2f°C\n",
                  minTemp, maxTemp, sumTemp / 20.0f);
    Serial.printf("Hum:  min=%.2f max=%.2f media=%.2f%%\n",
                  minHum, maxHum, sumHum / 20.0f);
    Serial.printf("Outliers (valores absurdos): %d\n", outliers);

    Serial.println("\n--- Resultado ---");
    if (outliers == 0) {
        Serial.println("PASSOU: Nenhum outlier detectado.");
    } else {
        Serial.printf("FALHOU: %d outlier(s) detectado(s)!\n", outliers);
    }

    Serial.println("\n[TESTE] Concluído. Resetando para teste de reproduzibilidade...");
    delay(3000);

    // Testa reproduzibilidade: reinicializa com mesma semente
    sensorSim.begin();
    Serial.println("\n--- Reproduzibilidade (mesma semente, mesmos valores?) ---");
    for (int i = 0; i < 5; i++) {
        SensorData d = sensorSim.read(0.0f, -65);
        Serial.printf("[R%02d] temp=%5.2f°C | hum=%5.2f%%\n",
                      i + 1, d.temp_c, d.hum_pct);
        delay(100);
    }
    Serial.println("Compare com os primeiros 5 valores acima — devem ser idênticos.");
}

void loop() {
    delay(5000);
}