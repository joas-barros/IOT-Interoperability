#include <Arduino.h>
#include "../include/wifi_manager.h"
#include "../include/config.h"

void setup() {
    Serial.begin(115200);
    delay(1000);  // aguarda Serial estabilizar

    Serial.println("========================================");
    Serial.println("  TESTE 1 — Wi-Fi Manager");
    Serial.println("========================================");

    bool ok = wifiManager.connect();

    if (!ok) {
        Serial.println("[TESTE] FALHOU: Não conectou ao Wi-Fi.");
        Serial.println("[TESTE] Verifique WIFI_SSID e WIFI_PASSWORD em config.h");
        while (true) { delay(5000); }  // trava aqui
    }

    Serial.println("[TESTE] Conexão inicial: OK");
    Serial.println("[TESTE] Monitorando RSSI a cada 5s...");
    Serial.println("[TESTE] Desligue o roteador para testar reconexão.");
    Serial.println("----------------------------------------");
}

void loop() {
    static uint32_t lastPrint = 0;
    static uint32_t ciclo     = 0;

    // Verifica e mantém conexão (chama a cada loop)
    bool connected = wifiManager.check();

    // Imprime status a cada 5s
    if (millis() - lastPrint >= 5000) {
        lastPrint = millis();
        ciclo++;

        if (connected) {
            Serial.printf("[TESTE] Ciclo %lu | IP: %s | RSSI: %d dBm | Status: CONECTADO\n",
                          ciclo,
                          wifiManager.getIP().c_str(),
                          wifiManager.getRSSI());
        } else {
            Serial.printf("[TESTE] Ciclo %lu | Status: DESCONECTADO — aguardando reconexão...\n",
                          ciclo);
        }
    }

    delay(100);  // pequena pausa para não sobrecarregar o loop
}