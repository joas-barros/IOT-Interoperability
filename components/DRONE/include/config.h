#pragma once
#include <cstdint>

#include "secrets.h"

// ------------------------------------------------------------
//  REDE Wi-Fi
// ------------------------------------------------------------

// Tempo máximo aguardando conexão Wi-Fi (ms)
#define WIFI_TIMEOUT_MS        15000

// Backoff máximo de reconexão (ms) — cresce: 2s, 4s, 8s... até este limite
#define WIFI_RECONNECT_MAX_MS  30000

// ------------------------------------------------------------
//  GATEWAY CoAP 
// ------------------------------------------------------------
#define GATEWAY_IP        "10.26.45.164"   
#define GATEWAY_PORT      5683              // porta padrão CoAP
#define COAP_ENDPOINT     "dados/drone"     // URI do recurso no servidor CoAP

// Timeout aguardando ACK (ms)
#define COAP_ACK_TIMEOUT_MS    5000

// Máximo de retransmissões antes de declarar falha
#define COAP_MAX_RETRIES       3

// Backoff multiplicador entre retransmissões (ms base)
#define COAP_RETRY_BASE_MS     1000

// ------------------------------------------------------------
//  NTP
// ------------------------------------------------------------
#define NTP_SERVER       "pool.ntp.org"
#define NTP_UPDATE_MS    60000   // atualiza a cada 60s
#define UTC_OFFSET_SEC   0       // usar UTC (sufixo Z no timestamp)

// ------------------------------------------------------------
//  IDENTIDADE DO DRONE
// ------------------------------------------------------------
#define DRONE_ID         "drone_01"

// Coordenadas do ponto de base (decolagem) 
#define BASE_LAT          -5.207645788981328
#define BASE_LON          -37.32216460818565

// Altitude alvo de missão (metros)
#define MISSION_ALT_M     5.0f

// Raio máximo de deslocamento horizontal em missão (metros)
#define MISSION_RADIUS_M  200.0f

// Bateria inicial (%)
#define BATTERY_INITIAL   100

// Limiar de bateria para alerta (%) — ativa flag alerta_bat no payload
#define BATTERY_ALERT_PCT  15

// ------------------------------------------------------------
//  FASES DE VOO — enum
// ------------------------------------------------------------
enum FlightPhase {
    PHASE_IDLE    = 0,
    PHASE_TAKEOFF = 1,
    PHASE_MISSION = 2,
    PHASE_HOVER   = 3,
    PHASE_RETURN  = 4,
    PHASE_LANDING = 5
};

// Nomes das fases para log no Serial
static const char* PHASE_NAMES[] = {
    "IDLE", "TAKEOFF", "MISSION", "HOVER", "RETURN", "LANDING"
};

// ------------------------------------------------------------
//  DURAÇÃO DE CADA FASE (ms)
// ------------------------------------------------------------
static const uint32_t PHASE_DURATION_MS[] = {
    10000,   // IDLE     — 10s aguardando antes da missão
    15000,   // TAKEOFF  — 15s subindo até altitude alvo
    60000,   // MISSION  — 60s percorrendo waypoints
    20000,   // HOVER    — 20s pairando em cada ponto de hover
    40000,   // RETURN   — 40s retornando à base
    12000    // LANDING  — 12s descendo até o solo
};

// ------------------------------------------------------------
//  INTERVALO DE PUBLICAÇÃO CoAP POR FASE (ms)
// ------------------------------------------------------------
static const uint32_t PHASE_PUBLISH_INTERVAL_MS[] = {
    30000,  // IDLE     — a cada 30s (heartbeat)
    5000,   // TAKEOFF  — a cada 5s
    5000,   // MISSION  — a cada 5s
    2000,   // HOVER    — a cada 2s (coleta intensiva)
    5000,   // RETURN   — a cada 5s
    2000    // LANDING  — a cada 2s (descida cuidadosa)
};

// ------------------------------------------------------------
//  CONSUMO DE BATERIA POR FASE (% por segundo)
// ------------------------------------------------------------
static const float PHASE_BATTERY_DRAIN[] = {
    0.00f,   // IDLE     — sem consumo
    0.08f,   // TAKEOFF  — alto consumo (subida)
    0.05f,   // MISSION  — consumo médio (cruzeiro)
    0.07f,   // HOVER    — consumo médio-alto (hover ineficiente)
    0.05f,   // RETURN   — consumo médio
    0.04f    // LANDING  — baixo consumo (descida)
};

// ------------------------------------------------------------
//  WAYPOINTS DA MISSÃO
// ------------------------------------------------------------
struct Waypoint {
    double   lat;
    double   lon;
    float    alt_m;
    bool     isHover;          // true = drone para neste ponto (HOVER)
    uint32_t hoverDuration_ms; // duração do hover (0 se isHover = false)
};

// Waypoints reais próximos ao BASE_LAT/BASE_LON
static const Waypoint WAYPOINTS[] = {
    // WP0 — Segue 100m para o Norte a partir da base
    { -5.206745, -37.322164, 80.0f, false, 0      },  
    
    // WP1 — Faz a curva 100m para o Leste (Primeiro ponto de inspeção)
    { -5.206745, -37.321264, 80.0f, true,  20000  },  
    // WP2 — Desce 100m para o Sul
    { -5.207645, -37.321264, 80.0f, false, 0      },  
    // WP3 — Retorna 100m para o Oeste, voltando para exatamente em cima da base (Segundo ponto de inspeção)
    { -5.207645, -37.322164, 80.0f, true,  20000  },
};
static const uint8_t NUM_WAYPOINTS = sizeof(WAYPOINTS) / sizeof(Waypoint);

// ------------------------------------------------------------
//  SIMULAÇÃO DE DADOS AMBIENTAIS
// ------------------------------------------------------------
#define SENSOR_TEMP_BASE      28.0f    // temperatura base (°C)
#define SENSOR_TEMP_AMPLITUDE  2.0f    // variação máxima (±°C)
#define SENSOR_HUM_BASE       68.0f    // umidade base (%)
#define SENSOR_HUM_AMPLITUDE   5.0f    // variação máxima (±%)

// Gradiente adiabático: -0.0065°C por metro de altitude
#define ADIABATIC_LAPSE_RATE  0.0065f

// Semente do gerador aleatório (fixar para experimentos reproduzíveis)
// Mude para 0 para semente aleatória real
#define RANDOM_SEED  42

// ------------------------------------------------------------
//  JITTER GPS (simulação de imprecisão do sensor)
// ------------------------------------------------------------
// ~2 metros em graus de latitude/longitude
#define GPS_JITTER_DEG  0.00002

// ------------------------------------------------------------
//  TEMPO PRÉ-MISSÃO (ms) — pausa no setup() para confirmar no Serial
// ------------------------------------------------------------
#define PRE_MISSION_DELAY_MS  5000

// ------------------------------------------------------------
//  JSON PAYLOAD
// ------------------------------------------------------------
// Tamanho do documento JSON estático (bytes)
// Calculado: campos + margem de segurança
#define JSON_DOC_SIZE   512

// Tamanho do buffer de serialização JSON (bytes)
#define JSON_BUFFER_SIZE  512