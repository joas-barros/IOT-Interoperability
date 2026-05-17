#include <Arduino.h>
#include <math.h>

#include "config.h"
#include "wifi_manager.h"
#include "ntp_sync.h"
#include "flight_state.h"
#include "sensor_sim.h"
#include "payload_builder.h"
#include "coap_client.h"

static char    jsonBuffer[JSON_BUFFER_SIZE];
static uint32_t seqNumber    = 0;
static uint32_t lastPublish  = 0;
static bool    missionDone   = false;
static uint32_t payloadsSent = 0;
static uint32_t payloadsOk   = 0;

// Imprime o JSON de forma legível no Serial
void printJson(const char* json, uint32_t seq) {
    Serial.printf("\n--- Payload seq=%lu (%d bytes) ---\n",
                  seq, strlen(json));
    // Imprime o JSON completo para verificação manual
    Serial.println(json);
    Serial.println("---");
}

void setup() {
    Serial.begin(115200);
    delay(1000);

    Serial.println("========================================");
    Serial.println("  TESTE 7 — Integração Completa");
    Serial.println("  (Missão completa com todos os módulos)");
    Serial.println("========================================");

    // Inicialização
    if (!wifiManager.connect()) {
        Serial.println("[TESTE] FALHOU: Sem Wi-Fi.");
        while (true) delay(5000);
    }

    ntpSync.begin();
    sensorSim.begin();
    flightState.begin();
    coapClient.begin();

    Serial.println("\n[TESTE] Todos os módulos inicializados.");
    Serial.println("[TESTE] Iniciando missão em 5s...");
    Serial.println("[TESTE] Verifique o log do RPi B em paralelo.");
    Serial.println("========================================\n");

    delay(5000);
    lastPublish = millis();
}

void loop() {
    if (missionDone) return;

    // Manutenção
    wifiManager.check();
    ntpSync.update();
    coapClient.loop();

    // Avança voo
    bool transitioned = flightState.update();
    if (transitioned) {
        Serial.printf("\n[VÔOO] >> Fase: %s | Bat: %d%% | Alt: %.1fm\n",
                      flightState.getPhaseName(),
                      flightState.getBattery(),
                      flightState.getAlt());
    }

    // Publica se é hora
    uint32_t interval = PHASE_PUBLISH_INTERVAL_MS[flightState.getPhase()];
    if (millis() - lastPublish >= interval && coapClient.isReady()) {
        lastPublish = millis();
        seqNumber++;
        payloadsSent++;

        SensorData sd = sensorSim.read(
            flightState.getAlt(),
            wifiManager.getRSSI()
        );

        bool ok = payloadBuilder.build(jsonBuffer, sizeof(jsonBuffer),
                                        flightState, sd, seqNumber);

        if (ok) {
            payloadsOk++;
            // No teste 7: imprime cada payload para verificação
            printJson(jsonBuffer, seqNumber);

            uint16_t msgId = coapClient.send(jsonBuffer);
            Serial.printf("[CoAP] Enviado msgId=%d (seq=%lu)\n", msgId, seqNumber);
        } else {
            Serial.printf("[PAYLOAD] ERRO seq=%lu: %s\n",
                          seqNumber, payloadBuilder.lastError());
        }
    }

    // Fim de missão
    if (flightState.isMissionDone() && coapClient.isReady()) {
        missionDone = true;

        Serial.println("\n========================================");
        Serial.println("  MISSÃO CONCLUÍDA — RELATÓRIO FINAL");
        Serial.println("========================================");
        Serial.printf("  Payloads tentados:  %lu\n", payloadsSent);
        Serial.printf("  Payloads válidos:   %lu\n", payloadsOk);
        Serial.printf("  Payloads inválidos: %lu\n",
                      payloadsSent - payloadsOk);

        coapClient.printMetrics();

        Serial.println("\n  VERIFICAÇÃO NO RPi B:");
        Serial.println("  - Quantos JSONs foram recebidos?");
        Serial.println("  - A estrutura está correta?");
        Serial.println("  - Qual é a diferença gateway_ts - ts?");
        Serial.println("    (essa é a latência CoAP que você vai analisar)");
        Serial.println("========================================");
    }

    delay(20);
}