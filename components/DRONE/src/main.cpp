// ============================================================
//  main.cpp — Orquestrador Principal do Drone Simulado
//
//  Projeto: Arquitetura IoT Interoperável
//  Componente: ESP32 — Drone Simulado (cliente CoAP)
//
//  Fluxo de execução:
//    setup():
//      1. Serial
//      2. Wi-Fi (wifiManager.connect)
//      3. NTP   (ntpSync.begin)
//      4. Sensor (sensorSim.begin)
//      5. Máquina de estados (flightState.begin)
//      6. CoAP  (coapClient.begin)
//      7. Pausa pré-missão (PRE_MISSION_DELAY_MS)
//
//    loop() — não bloqueante, baseado em millis():
//      1. wifiManager.check()   — mantém Wi-Fi ativo
//      2. ntpSync.update()      — mantém tempo sincronizado
//      3. coapClient.loop()     — processa ACKs e timeouts
//      4. flightState.update()  — avança máquina de estados
//      5. Se intervalo da fase decorrido e CoAP livre:
//           a. sensorSim.read()
//           b. payloadBuilder.build()
//           c. coapClient.send()
//      6. Se missão concluída: imprime relatório final
// ============================================================

#include <Arduino.h>
#include <math.h>

#include "config.h"
#include "wifi_manager.h"
#include "ntp_sync.h"
#include "flight_state.h"
#include "sensor_sim.h"
#include "payload_builder.h"
#include "coap_client.h"

// Buffer de serialização do payload JSON
static char jsonBuffer[JSON_BUFFER_SIZE];

// Número de sequência global — incrementado a cada envio tentado
static uint32_t seqNumber = 0;

// Timestamp do último envio (para controle de intervalo por fase)
static uint32_t lastPublishMs = 0;

// Flag de missão finalizada
static bool missionReported = false;

// Contadores de ciclo do loop para diagnóstico
static uint32_t loopCount = 0;

// ------------------------------------------------------------
//  Funções auxiliares
// ------------------------------------------------------------

// Retorna o intervalo de publicação da fase atual
static uint32_t currentInterval() {
    return PHASE_PUBLISH_INTERVAL_MS[flightState.getPhase()];
}

// Imprime cabeçalho de inicialização no Serial
static void printBanner() {
    Serial.println();
    Serial.println("############################################");
    Serial.println("#  DRONE SIMULADO — ESP32 + CoAP          #");
    Serial.println("#  Projeto: Interoperabilidade IoT         #");
    Serial.println("############################################");
    Serial.printf( "#  ID:      %s\n", DRONE_ID);
    Serial.printf( "#  Gateway: %s:%d/%s\n",
                   GATEWAY_IP, GATEWAY_PORT, COAP_ENDPOINT);
    Serial.printf( "#  Base:    %.6f, %.6f\n", BASE_LAT, BASE_LON);
    Serial.printf( "#  Alt alvo: %.0fm\n", MISSION_ALT_M);
    Serial.printf( "#  Waypoints: %d\n", NUM_WAYPOINTS);
    Serial.println("############################################");
    Serial.println();
}

// Imprime estado atual da missão de forma compacta
static void printStatus() {
    static uint32_t lastStatusPrint = 0;
    if (millis() - lastStatusPrint < 5000) return;
    lastStatusPrint = millis();

    Serial.printf(
        "[STATUS] Fase=%-8s | WP=%d/%d | Lat=%.5f Lon=%.5f Alt=%.1fm | "
        "Vel=%.1fm/s | Bat=%d%% | Seq=%lu | RTT_ult=%lums\n",
        flightState.getPhaseName(),
        flightState.getWaypointIndex(), NUM_WAYPOINTS,
        flightState.getLat(), flightState.getLon(),
        flightState.getAlt(),
        flightState.getVelocity(),
        flightState.getBattery(),
        seqNumber,
        coapClient.getLastRTT()
    );
}

// Imprime relatório final de missão
static void printMissionReport() {
    Serial.println();
    Serial.println("############################################");
    Serial.println("#           RELATÓRIO DE MISSÃO           #");
    Serial.println("############################################");
    Serial.printf( "#  Drone ID:      %s\n", DRONE_ID);
    Serial.printf( "#  Duração total: %lu s\n",
                   flightState.getFlightDuration() / 1000);
    Serial.printf( "#  Waypoints:     %d\n", NUM_WAYPOINTS);
    Serial.printf( "#  Seq. final:    %lu\n", seqNumber);
    Serial.println("#                                          #");
    Serial.println("#  --- Métricas CoAP ---                  #");

    const CoapMetrics& m = coapClient.getMetrics();
    Serial.printf( "#  Enviadas:      %lu\n",  m.totalSent);
    Serial.printf( "#  ACKs:          %lu\n",  m.totalAcked);
    Serial.printf( "#  Timeouts:      %lu\n",  m.totalTimeout);
    Serial.printf( "#  Retransmit.:   %lu\n",  m.totalRetries);
    Serial.printf( "#  Taxa entrega:  %.1f%%\n", m.deliveryRate());
    Serial.printf( "#  RTT min:       %lu ms\n",
                   m.rttMin == UINT32_MAX ? 0 : m.rttMin);
    Serial.printf( "#  RTT max:       %lu ms\n", m.rttMax);
    Serial.printf( "#  RTT médio:     %.1f ms\n", m.avgRtt());
    Serial.println("############################################");
    Serial.println();
    Serial.println("[MAIN] Sistema em modo IDLE. Reinicie para nova missão.");
}

// ------------------------------------------------------------
//  setup()
// ------------------------------------------------------------
void setup() {
    Serial.begin(115200);
    delay(1000);

    printBanner();

    // --- 1. Wi-Fi ---
    Serial.println("[MAIN] [1/6] Conectando ao Wi-Fi...");
    if (!wifiManager.connect()) {
        Serial.println("[MAIN] ERRO FATAL: Sem Wi-Fi. Verifique config.h.");
        Serial.println("[MAIN] Sistema travado. Resetar manualmente.");
        while (true) delay(5000);
    }

    // --- 2. NTP ---
    Serial.println("[MAIN] [2/6] Sincronizando NTP...");
    if (!ntpSync.begin()) {
        Serial.println("[MAIN] AVISO: NTP falhou. Timestamps serão relativos ao boot.");
    }

    // --- 3. Sensor ---
    Serial.println("[MAIN] [3/6] Inicializando simulação de sensores...");
    sensorSim.begin();

    // --- 4. Máquina de estados ---
    Serial.println("[MAIN] [4/6] Inicializando máquina de estados de voo...");
    flightState.begin();

    // --- 5. CoAP ---
    Serial.println("[MAIN] [5/6] Inicializando cliente CoAP...");
    coapClient.begin();

    // --- 6. Pausa pré-missão ---
    Serial.println("[MAIN] [6/6] Sistema pronto!");
    Serial.println();
    Serial.printf("[MAIN] Iniciando missão em %d segundos...\n",
                  PRE_MISSION_DELAY_MS / 1000);
    Serial.println("[MAIN] Verifique o servidor CoAP no gateway.");
    Serial.println("[MAIN] Abra o log do Raspberry Pi B para confirmar.");
    Serial.println();

    for (int i = PRE_MISSION_DELAY_MS / 1000; i > 0; i--) {
        Serial.printf("[MAIN] %d...\n", i);
        delay(1000);
    }

    Serial.println("[MAIN] MISSÃO INICIADA!");
    lastPublishMs = millis();
}

// ------------------------------------------------------------
//  loop()
// ------------------------------------------------------------
void loop() {
    loopCount++;

    // ---- 1. Manutenção de conexões (sempre, não bloqueante) ----
    wifiManager.check();
    ntpSync.update();
    coapClient.loop();   // processa ACKs e gerencia timeouts/retransmissões

    // ---- 2. Missão já concluída ----
    if (missionReported) {
        delay(5000);
        return;
    }

    // ---- 3. Avança máquina de estados de voo ----
    bool transitioned = flightState.update();

    if (transitioned) {
        Serial.printf("\n[MAIN] >>> Transição: fase=%s | bat=%d%% | alt=%.1fm\n",
                      flightState.getPhaseName(),
                      flightState.getBattery(),
                      flightState.getAlt());
    }

    // ---- 4. Verifica se é hora de publicar ----
    bool intervalOk = (millis() - lastPublishMs >= currentInterval());
    bool coapReady  = coapClient.isReady();

    if (intervalOk && coapReady) {
        lastPublishMs = millis();
        seqNumber++;

        // 4a. Lê dados do sensor
        SensorData sd = sensorSim.read(
            flightState.getAlt(),
            wifiManager.getRSSI()
        );

        // 4b. Monta e valida payload
        bool payloadOk = payloadBuilder.build(
            jsonBuffer, sizeof(jsonBuffer),
            flightState, sd, seqNumber
        );

        if (payloadOk) {
            // 4c. Envia via CoAP
            uint16_t msgId = coapClient.send(jsonBuffer);

            if (msgId == 0) {
                Serial.printf("[MAIN] AVISO: Falha ao enviar seq=%lu\n", seqNumber);
            }
            // Não bloqueia — ACK processado assincronamente em coapClient.loop()

        } else {
            Serial.printf("[MAIN] AVISO: Payload inválido (seq=%lu): %s\n",
                          seqNumber, payloadBuilder.lastError());
        }
    }

    // ---- 5. Imprime status periódico ----
    printStatus();

    // ---- 6. Verifica fim de missão ----
    if (flightState.isMissionDone() && !missionReported) {
        // Aguarda resolução do último CoAP pendente
        if (coapClient.isReady()) {
            missionReported = true;
            printMissionReport();
        }
    }

    // Pequena pausa para evitar busy-loop — não usa delay longo
    delay(20);  // 50Hz de loop
}