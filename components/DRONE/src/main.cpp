#include <Arduino.h>
#include <math.h>
#include "config.h"
#include "ntp_sync.h"
#include "flight_state.h"
#include "sensor_sim.h"
#include "payload_builder.h"

char jsonBuffer[JSON_BUFFER_SIZE];

void setup() {
    Serial.begin(115200);
    delay(1000);

    Serial.println("========================================");
    Serial.println("  TESTE 5 — Payload Builder");
    Serial.println("  (NTP usará fallback sem Wi-Fi)");
    Serial.println("========================================");

    sensorSim.begin();
    flightState.begin();

    // Avança manualmente para MISSION para ter dados mais ricos
    // (simula estado após decolagem)
    Serial.println("[TESTE] Simulando estado MISSION para teste...\n");

    // --- TESTE 1: Payload válido ---
    Serial.println("=== TESTE 1: Payload válido ===");

    // Gera dados simulados
    SensorData sd = sensorSim.read(MISSION_ALT_M, -68);

    bool ok = payloadBuilder.build(jsonBuffer, sizeof(jsonBuffer),
                                   flightState, sd, 1);

    if (ok) {
        int jsonLen = strlen(jsonBuffer);
        Serial.println("[PASSOU] Payload gerado com sucesso!");
        Serial.printf("[INFO]   Tamanho: %d bytes (limite: %d)\n",
                      jsonLen, JSON_BUFFER_SIZE);
        Serial.println("[JSON]");
        Serial.println(jsonBuffer);

        if (jsonLen >= JSON_BUFFER_SIZE) {
            Serial.println("[AVISO] JSON próximo do limite do buffer!");
        }
    } else {
        Serial.printf("[FALHOU] Erro: %s\n", payloadBuilder.lastError());
    }

    Serial.println();

    // --- TESTE 2: Temperatura inválida ---
    Serial.println("=== TESTE 2: Temperatura inválida (esperado: FALHA) ===");
    SensorData sdBad = sd;
    sdBad.temp_c = 99.0f;  // fora do range [-20, 60]
    bool ok2 = payloadBuilder.build(jsonBuffer, sizeof(jsonBuffer),
                                    flightState, sdBad, 2);
    if (!ok2) {
        Serial.printf("[PASSOU] Rejeitado corretamente: %s\n",
                      payloadBuilder.lastError());
    } else {
        Serial.println("[FALHOU] Deveria ter rejeitado temperatura 99°C!");
    }

    Serial.println();

    // --- TESTE 3: Umidade inválida ---
    Serial.println("=== TESTE 3: Umidade inválida (esperado: FALHA) ===");
    SensorData sdBad2 = sd;
    sdBad2.hum_pct = 150.0f;
    bool ok3 = payloadBuilder.build(jsonBuffer, sizeof(jsonBuffer),
                                    flightState, sdBad2, 3);
    if (!ok3) {
        Serial.printf("[PASSOU] Rejeitado corretamente: %s\n",
                      payloadBuilder.lastError());
    } else {
        Serial.println("[FALHOU] Deveria ter rejeitado umidade 150%!");
    }

    Serial.println();

    // --- TESTE 4: Número de sequência incrementando ---
    Serial.println("=== TESTE 4: Sequência incrementando ===");
    for (uint32_t seq = 1; seq <= 5; seq++) {
        payloadBuilder.build(jsonBuffer, sizeof(jsonBuffer),
                             flightState, sd, seq);
        // Extrai o campo seq do JSON impresso
        Serial.printf("[SEQ %lu] %s\n", seq,
                      strstr(jsonBuffer, "\"seq\"") ? "campo seq presente" : "ERRO");
    }

    Serial.println("\n========================================");
    Serial.println("  TESTES CONCLUÍDOS");
    Serial.println("  Verifique os resultados acima.");
    Serial.println("========================================");
}

void loop() {
    delay(5000);
}