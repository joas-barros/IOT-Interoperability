// ============================================================
//  coap_client.cpp — Cliente CoAP
// ============================================================

#include "coap_client.h"

CoapClient  coapClient;
CoapClient* CoapClient::_instance = nullptr;

// ------------------------------------------------------------
void CoapClient::begin() {
    _instance = this;

    // Registra callback de resposta do servidor CoAP
    _coap.response(_onResponse);

    // Inicia o stack UDP/CoAP
    _coap.start();

    Serial.printf("[CoAP] Cliente iniciado | Gateway: %s:%d | Endpoint: %s\n",
                  GATEWAY_IP, GATEWAY_PORT, COAP_ENDPOINT);
}

// ------------------------------------------------------------
uint16_t CoapClient::send(const uint8_t* payload,
                           size_t         payloadLen,
                           COAP_CONTENT_TYPE       contentFormat) {
    if (!isReady()) {
        Serial.println("[CoAP] AVISO: Mensagem pendente. Ignorado.");
        return 0;
    }
    if (payloadLen == 0 || payloadLen > sizeof(_payloadBuf)) {
        Serial.printf("[CoAP] ERRO: payloadLen=%d inválido.\n", payloadLen);
        return 0;
    }
 
    // Copia payload para buffer interno (necessário para retransmissões)
    memcpy(_payloadBuf, payload, payloadLen);
    _payloadLen    = payloadLen;
    _contentFormat = contentFormat;
    _retryCount    = 0;
    _state         = COAP_STATE_WAITING_ACK;
 
    return _doSend();
}

// ------------------------------------------------------------
uint16_t CoapClient::_doSend() {
    IPAddress gatewayIP;
    gatewayIP.fromString(GATEWAY_IP);

    _sendTime = millis();
    _metrics.totalSent++;

    uint16_t msgId = _coap.send(
        gatewayIP,
        GATEWAY_PORT,
        COAP_ENDPOINT,
        COAP_CON,                     // Tipo: Confirmable (Exige que o servidor responda com ACK)
        COAP_POST,                    // Método: POST
        NULL,                         // Token (não precisamos gerenciar token manual aqui)
        0,                            // Tamanho do token
        (const uint8_t*)_payloadBuf,  // Cast obrigatório do payload para ponteiro de bytes
        _payloadLen,           // Tamanho do payload
        _contentFormat
    );

    _pendingMsgId = msgId;

    Serial.printf("[CoAP] Enviado | msgId=%d | seq=%lu | retry=%d | payload=%d bytes\n",
                  msgId, _metrics.totalSent, _retryCount, _payloadLen);

    return msgId;
}

// ------------------------------------------------------------
void CoapClient::loop() {
    // Processa pacotes UDP recebidos (ACKs do servidor)
    _coap.loop();

    // Verifica timeout e retransmite se necessário
    if (_state == COAP_STATE_WAITING_ACK) {
        _checkTimeout();
    }
}

// ------------------------------------------------------------
void CoapClient::_checkTimeout() {
    uint32_t elapsed = millis() - _sendTime;

    // Backoff: timeout cresce com cada retry (1x, 2x, 3x o base)
    uint32_t currentTimeout = COAP_ACK_TIMEOUT_MS * (_retryCount + 1);

    if (elapsed >= currentTimeout) {
        _metrics.totalTimeout++;

        if (_retryCount < COAP_MAX_RETRIES) {
            _retryCount++;
            _metrics.totalRetries++;

            Serial.printf("[CoAP] Timeout #%d (após %lums). Retransmitindo...\n",
                          _retryCount, elapsed);

            _doSend();
        } else {
            // Esgotou retransmissões
            _state = COAP_STATE_FAILED;
            Serial.printf("[CoAP] FALHA: %d retransmissões sem ACK. Mensagem perdida.\n",
                          COAP_MAX_RETRIES);
            Serial.printf("[CoAP] Stats: enviadas=%lu acked=%lu perdidas=%lu RTT_med=%.0fms\n",
                          _metrics.totalSent, _metrics.totalAcked,
                          _metrics.totalTimeout, _metrics.avgRtt());
        }
    }
}

// ------------------------------------------------------------
void CoapClient::_handleAck(uint16_t msgId, uint8_t responseCode) {
    if (_state != COAP_STATE_WAITING_ACK) return;

    uint32_t rtt = millis() - _sendTime;
    _lastRTT     = rtt;

    // Atualiza métricas
    _metrics.totalAcked++;
    _metrics.rttSum += rtt;
    if (rtt < _metrics.rttMin) _metrics.rttMin = rtt;
    if (rtt > _metrics.rttMax) _metrics.rttMax = rtt;

    _state = COAP_STATE_SUCCESS;

    Serial.printf("[CoAP] ACK recebido | msgId=%d | code=0x%02X | RTT=%lums\n",
                  msgId, responseCode, rtt);
}

// ------------------------------------------------------------
// Callback estático — chamado pela biblioteca quando um pacote chega
void CoapClient::_onResponse(CoapPacket& packet, IPAddress ip, int port) {
    if (_instance == nullptr) return;

    // Verifica se é um ACK (type == COAP_ACK = 2)
    if (packet.type == COAP_ACK || packet.type == COAP_CON) {
        _instance->_handleAck(packet.messageid, packet.code);
    }
}

// ------------------------------------------------------------
//  CoapMetrics
// ------------------------------------------------------------
float CoapMetrics::deliveryRate() const {
    if (totalSent == 0) return 0.0f;
    return (float)totalAcked / (float)totalSent * 100.0f;
}

float CoapMetrics::avgRtt() const {
    if (totalAcked == 0) return 0.0f;
    return (float)rttSum / (float)totalAcked;
}

void CoapMetrics::print() const {
    Serial.println("=== Métricas CoAP da Sessão ===");
    Serial.printf("  Enviadas:        %lu\n",  totalSent);
    Serial.printf("  ACKs recebidos:  %lu\n",  totalAcked);
    Serial.printf("  Timeouts:        %lu\n",  totalTimeout);
    Serial.printf("  Retransmissões:  %lu\n",  totalRetries);
    Serial.printf("  Taxa de entrega: %.1f%%\n", deliveryRate());
    Serial.printf("  RTT mínimo:      %lums\n", rttMin == UINT32_MAX ? 0 : rttMin);
    Serial.printf("  RTT máximo:      %lums\n", rttMax);
    Serial.printf("  RTT médio:       %.1fms\n", avgRtt());
    Serial.println("================================");
}