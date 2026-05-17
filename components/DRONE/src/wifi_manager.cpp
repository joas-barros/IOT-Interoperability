// ============================================================
//  wifi_manager.cpp — Gerenciador de Conexão Wi-Fi
// ============================================================

#include "wifi_manager.h"
#include "config.h"

// Instância global
WiFiManager wifiManager;

// ------------------------------------------------------------
bool WiFiManager::connect() {
    Serial.println("[WiFi] Iniciando conexão...");
    Serial.printf("[WiFi] SSID: %s\n", WIFI_SSID);

    // Habilita reconexão automática do ESP32
    WiFi.setAutoReconnect(true);
    WiFi.persistent(false);   // não salva credenciais na flash

    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

    uint32_t startTime = millis();

    while (WiFi.status() != WL_CONNECTED) {
        if (millis() - startTime > WIFI_TIMEOUT_MS) {
            Serial.println("\n[WiFi] ERRO: Timeout na conexão!");
            Serial.println("[WiFi] Verifique SSID e senha em config.h");
            return false;
        }
        delay(500);
        Serial.print(".");
    }

    Serial.println();
    Serial.println("[WiFi] Conectado com sucesso!");
    Serial.printf("[WiFi] IP: %s\n", WiFi.localIP().toString().c_str());
    Serial.printf("[WiFi] RSSI: %d dBm\n", WiFi.RSSI());
    Serial.printf("[WiFi] Gateway: %s\n", WiFi.gatewayIP().toString().c_str());

    _reconnectDelay    = 2000;
    _reconnectAttempts = 0;

    return true;
}

// ------------------------------------------------------------
bool WiFiManager::check() {
    if (WiFi.status() == WL_CONNECTED) {
        return true;
    }

    // Só tenta reconectar após o delay de backoff
    uint32_t now = millis();
    if (now - _lastReconnectAttempt >= _reconnectDelay) {
        _reconnect();
        _lastReconnectAttempt = now;

        // Backoff exponencial: 2s → 4s → 8s → ... → WIFI_RECONNECT_MAX_MS
        _reconnectDelay = min((uint32_t)(_reconnectDelay * 2),
                              (uint32_t)WIFI_RECONNECT_MAX_MS);
        _reconnectAttempts++;
    }

    return false;
}

// ------------------------------------------------------------
void WiFiManager::_reconnect() {
    Serial.printf("[WiFi] Conexão perdida! Tentativa %d de reconexão...\n",
                  _reconnectAttempts + 1);

    WiFi.disconnect();
    delay(100);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

    // Aguarda até 5s por reconexão (não bloqueante para chamadas futuras,
    uint32_t t = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t < 5000) {
        delay(200);
    }

    if (WiFi.status() == WL_CONNECTED) {
        Serial.printf("[WiFi] Reconectado! IP: %s | RSSI: %d dBm\n",
                      WiFi.localIP().toString().c_str(), WiFi.RSSI());
        // Reseta backoff após sucesso
        _reconnectDelay    = 2000;
        _reconnectAttempts = 0;
    } else {
        Serial.printf("[WiFi] Falha na reconexão. Próxima tentativa em %lums\n",
                      _reconnectDelay * 2);
    }
}

// ------------------------------------------------------------
bool WiFiManager::isConnected() {
    return WiFi.status() == WL_CONNECTED;
}

// ------------------------------------------------------------
String WiFiManager::getIP() {
    return WiFi.localIP().toString();
}

// ------------------------------------------------------------
int8_t WiFiManager::getRSSI() {
    return (int8_t)WiFi.RSSI();
}