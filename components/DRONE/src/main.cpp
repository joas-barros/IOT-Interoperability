#include <Arduino.h>
#include "config.h"
#include "wifi_manager.h"
#include "ntp_sync.h"

void setup() {
    Serial.begin(115200);
    delay(1000);

    Serial.println("========================================");
    Serial.println("  TESTE 2 — NTP Sync");
    Serial.println("========================================");

    if (!wifiManager.connect()) {
        Serial.println("[TESTE] FALHOU: Sem Wi-Fi.");
        while (true) delay(5000);
    }

    bool ntpOk = ntpSync.begin();

    if (!ntpOk) {
        Serial.println("[TESTE] AVISO: NTP falhou, usando fallback.");
    } else {
        Serial.println("[TESTE] NTP sincronizado: OK");
    }

    Serial.println("[TESTE] Imprimindo timestamps a cada 1s por 2 minutos...");
    Serial.println("[TESTE] Compare com horário UTC real para validar.");
    Serial.println("----------------------------------------");
}

uint32_t testStart = 0;
uint8_t  count     = 0;

void loop() {
    static uint32_t lastPrint = 0;

    if (testStart == 0) testStart = millis();

    // Encerra após 2 minutos
    if (millis() - testStart > 120000) {
        Serial.println("----------------------------------------");
        Serial.printf("[TESTE] Concluído. %d timestamps gerados.\n", count);
        Serial.println("[TESTE] RESULTADO: Se os segundos incrementaram corretamente => PASSOU");
        while (true) delay(5000);
    }

    ntpSync.update();

    if (millis() - lastPrint >= 1000) {
        lastPrint = millis();
        count++;

        String ts    = ntpSync.getTimestamp();
        unsigned long epoch = ntpSync.getEpoch();
        bool synced  = ntpSync.isSynced();

        Serial.printf("[%03d] ts=%s | epoch=%lu | synced=%s\n",
                      count, ts.c_str(), epoch, synced ? "SIM" : "NAO");
    }

    delay(10);
}