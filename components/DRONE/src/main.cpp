// ============================================================
//  SKETCH DE TESTE 6 — CoAP Client
//
//  O QUE TESTA:
//    1. Envio de mensagem CoAP POST ao gateway
//    2. Recepção de ACK e cálculo de RTT
//    3. Timeout e retransmissão automática
//    4. Métricas acumuladas (taxa de entrega, RTT min/max/med)
//
//  PRÉ-REQUISITO:
//    O Raspberry Pi B (gateway) deve estar rodando um servidor
//    CoAP simples que responda com ACK ao endpoint /dados/drone.
//
//    Servidor de teste mínimo para o RPi B (Python):
//    -------------------------------------------------
//    pip install aiocoap
//
//    # salve como coap_test_server.py e execute:
//    # python3 coap_test_server.py
//    import asyncio
//    import aiocoap
//    import aiocoap.resource as resource
//
//    class DroneResource(resource.Resource):
//        async def render_post(self, request):
//            print(f"[CoAP] Recebido: {request.payload.decode()}")
//            return aiocoap.Message(code=aiocoap.CHANGED,
//                                   payload=b"OK")
//
//    async def main():
//        root = resource.Site()
//        root.add_resource(['dados', 'drone'], DroneResource())
//        await aiocoap.Context.create_server_context(root)
//        await asyncio.get_event_loop().run_forever()
//
//    asyncio.run(main())
//    -------------------------------------------------
//
//  COMO TESTAR:
//    1. Inicie o servidor CoAP no Raspberry Pi B
//    2. Configure GATEWAY_IP em config.h com o IP do RPi B
//    3. Grave este sketch no ESP32
//    4. Abra o Serial Monitor (115200 baud)
//    5. Observe os envios, ACKs e RTTs
//    6. Para testar timeout: desligue o RPi B e observe retransmissões
//
//  RESULTADO ESPERADO:
//    - ACK recebido para cada mensagem (quando gateway online)
//    - RTT < 200ms em rede local
//    - Após COAP_MAX_RETRIES sem ACK: mensagem marcada como perdida
//    - Métricas impressas ao final
// ============================================================

#include <Arduino.h>
#include <math.h>
#include "config.h"
#include "wifi_manager.h"
#include "ntp_sync.h"
#include "coap_client.h"

// Número de mensagens de teste para enviar
#define TEST_MESSAGES 20

// Intervalo entre envios (ms) — aguarda resolução da anterior
#define TEST_INTERVAL_MS 6000

uint32_t lastSend    = 0;
uint32_t msgCount    = 0;
bool     testDone    = false;

void setup() {
    Serial.begin(115200);
    delay(1000);

    Serial.println("========================================");
    Serial.println("  TESTE 6 — CoAP Client");
    Serial.println("========================================");
    Serial.printf("  Gateway: %s:%d\n", GATEWAY_IP, GATEWAY_PORT);
    Serial.printf("  Endpoint: coap://%s/%s\n", GATEWAY_IP, COAP_ENDPOINT);
    Serial.printf("  Mensagens de teste: %d\n", TEST_MESSAGES);
    Serial.printf("  Timeout por ACK: %dms\n", COAP_ACK_TIMEOUT_MS);
    Serial.printf("  Max retransmissões: %d\n", COAP_MAX_RETRIES);
    Serial.println("----------------------------------------");
    Serial.println("PRÉ-REQUISITO: Servidor CoAP rodando no RPi B!");
    Serial.println("Ver comentário no topo deste arquivo.");
    Serial.println("========================================");

    if (!wifiManager.connect()) {
        Serial.println("[TESTE] FALHOU: Sem Wi-Fi.");
        while (true) delay(5000);
    }

    ntpSync.begin();
    coapClient.begin();

    Serial.println("[TESTE] Iniciando envios em 3s...");
    delay(3000);
}

void loop() {
    if (testDone) return;

    // Engine do CoAP — SEMPRE chamar no loop
    wifiManager.check();
    ntpSync.update();
    coapClient.loop();

    // Envia próxima mensagem quando pronto e intervalo decorrido
    bool timeOk = (millis() - lastSend >= TEST_INTERVAL_MS);
    bool ready  = coapClient.isReady();

    if (timeOk && ready && msgCount < TEST_MESSAGES) {
        msgCount++;
        lastSend = millis();

        // Payload de teste simples
        char payload[200];
        snprintf(payload, sizeof(payload),
                 "{\"id\":\"%s\",\"seq\":%lu,\"ts\":\"%s\","
                 "\"teste\":true,\"msg\":\"CoAP test #%lu\"}",
                 DRONE_ID, msgCount,
                 ntpSync.getTimestamp().c_str(),
                 msgCount);

        Serial.printf("\n[TESTE] Enviando mensagem %lu/%d...\n",
                      msgCount, TEST_MESSAGES);

        uint16_t msgId = coapClient.send(payload);

        if (msgId == 0) {
            Serial.println("[TESTE] Falha ao iniciar envio CoAP.");
        }
    }

    // Aguarda resolução da última mensagem antes de concluir
    if (msgCount >= TEST_MESSAGES && coapClient.isReady()) {
        testDone = true;
        Serial.println("\n========================================");
        Serial.println("  TESTE 6 CONCLUÍDO");
        coapClient.printMetrics();

        const CoapMetrics& m = coapClient.getMetrics();
        Serial.println("\n  RESULTADO:");
        if (m.deliveryRate() >= 95.0f) {
            Serial.printf("  PASSOU: Taxa de entrega %.1f%% >= 95%%\n",
                          m.deliveryRate());
        } else {
            Serial.printf("  ATENÇÃO: Taxa de entrega %.1f%% < 95%%\n",
                          m.deliveryRate());
            Serial.println("  Verifique a rede e o servidor CoAP no RPi B.");
        }
        Serial.println("========================================");
    }

    delay(10);
}