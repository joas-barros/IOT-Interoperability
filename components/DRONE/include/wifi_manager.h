#pragma once

// ============================================================
//  wifi_manager.h — Gerenciador de Conexão Wi-Fi
// ============================================================

#include <Arduino.h>
#include <WiFi.h>

class WiFiManager {
public:
    // Conecta ao Wi-Fi usando credenciais do config.h
    // Trava com mensagem de erro se não conseguir conectar
    // no tempo definido por WIFI_TIMEOUT_MS
    bool connect();

    // Verifica se ainda está conectado.
    // Se não estiver, tenta reconectar com backoff exponencial.
    // Deve ser chamada no início de cada loop().
    bool check();

    // Retorna o IP atual como String 
    String getIP();

    // Retorna true se atualmente conectado
    bool isConnected();

    // Retorna o RSSI atual em dBm
    int8_t getRSSI();

private:
    uint32_t _lastReconnectAttempt = 0;
    uint32_t _reconnectDelay       = 2000;  // começa em 2s, dobra a cada falha
    uint8_t  _reconnectAttempts    = 0;

    void _reconnect();
};

// Instância global — usada por todos os módulos
extern WiFiManager wifiManager;