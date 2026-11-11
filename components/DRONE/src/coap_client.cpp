// ============================================================
//  coap_client.cpp — Cliente CoAP
// ============================================================

#include "coap_client.h"

CoapClient  coapClient;

// ── Constantes do protocolo CoAP (RFC 7252) ──────────────────
static constexpr uint8_t  COAP_VERSION        = 1;
static constexpr uint8_t  COAP_TYPE_CON       = 0;  // Confirmable
static constexpr uint8_t  COAP_TYPE_ACK       = 2;  // Acknowledgement
static constexpr uint8_t  COAP_CODE_POST      = 0x02; // 0.02 POST
static constexpr uint8_t  COAP_CODE_CHANGED   = 0x44; // 2.04 Changed
static constexpr uint8_t  COAP_CODE_BAD_REQ   = 0x80; // 4.00 Bad Request
static constexpr uint8_t  COAP_OPT_URI_PATH   = 11;
static constexpr uint8_t  COAP_OPT_CONTENT    = 12;

// ------------------------------------------------------------
bool CoapClient::begin() {
#if COAP_USE_DTLS
    DtlsConnectResult res = dtlsClient.connect(GATEWAY_IP, GATEWAY_PORT);
    if (res != DTLS_OK) {
        Serial.printf("[CoAP] Falha no handshake DTLS (err=%d). "
                      "Verifique PSK e conectividade.\n", res);
        return false;
    }
    Serial.printf("[CoAP] DTLS OK | Gateway: %s:%d | Handshake: %lums\n",
                  GATEWAY_IP, GATEWAY_PORT, dtlsClient.lastHandshakeMs());
#else
    _udp.begin(0);  // porta efêmera local
    Serial.printf("[CoAP] UDP puro | Gateway: %s:%d | Endpoint: %s\n",
                  GATEWAY_IP, GATEWAY_PORT, COAP_ENDPOINT);
#endif
    return true;
}

// ------------------------------------------------------------
uint16_t CoapClient::send(const uint8_t* payload,
                           size_t         payloadLen,
                           uint8_t       contentFormat) {

    #if COAP_USE_DTLS
        // Reconexão automática após falha ou sessão caída
        if (_state == COAP_STATE_FAILED || !dtlsClient.isConnected()) {
            Serial.println("[CoAP] Sessão DTLS inativa — reconectando...");
            DtlsConnectResult res = dtlsClient.connect(GATEWAY_IP, GATEWAY_PORT);
            if (res != DTLS_OK) {
                Serial.printf("[CoAP] Reconexão falhou (err=%d). Descartando seq.\n", res);
                return 0;
            }
            _state = COAP_STATE_IDLE;
            Serial.println("[CoAP] Reconexão DTLS bem-sucedida.");
        }
    #endif

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
//  _doSend() — monta pacote CoAP binário e envia via DTLS
// ------------------------------------------------------------
uint16_t CoapClient::_doSend() {
    uint8_t  pkt[512];
    uint16_t pos = 0;

    // ── Gera novo msgId apenas na primeira tentativa ──────────
    uint16_t msgId;
    if (_retryCount == 0) {
        msgId = _msgIdCounter++;
        _pendingMsgId = msgId;   // salva para validar ACK
    } else {
        msgId = _pendingMsgId;   // retransmissão: mesmo msgId (RFC 7252 §4.2)
    }

    // ── Header (4 bytes) ─────────────────────────────────────
    // Byte 0: Ver=1, T=CON(0), TKL=0
    pkt[pos++] = (COAP_VERSION << 6) | (COAP_TYPE_CON << 4) | 0x00;
    pkt[pos++] = COAP_CODE_POST;
    pkt[pos++] = (msgId >> 8) & 0xFF;
    pkt[pos++] =  msgId       & 0xFF;

    // ── Options (delta-encoded) ───────────────────────────────
    // Option 11 (Uri-Path): segmentos do endpoint "dados/drone"
    // Cada segmento é uma opção separada com delta acumulado
    const char* endpoint = COAP_ENDPOINT; // ex: "dados/drone"
    uint8_t lastOptNum = 0;

    char seg[32];
    const char* p = endpoint;

    while (*p) {
        const char* slash = strchr(p, '/');
        size_t segLen = slash ? (size_t)(slash - p) : strlen(p);
        memcpy(seg, p, segLen);
        seg[segLen] = '\0';

        uint8_t delta = COAP_OPT_URI_PATH - lastOptNum;
        // Delta e length em 1 byte cada (ambos < 13 — sem extensão)
        pkt[pos++] = (delta << 4) | (segLen & 0x0F);
        memcpy(pkt + pos, seg, segLen);
        pos += segLen;
        lastOptNum = COAP_OPT_URI_PATH;

        p = slash ? slash + 1 : p + segLen;
    }

    // Option 12 (Content-Format): delta = 12 - 11 = 1
    pkt[pos++] = (1 << 4) | 0x01;  // delta=1, length=1
    pkt[pos++] = _contentFormat;

    // ── Payload marker + payload ──────────────────────────────
    pkt[pos++] = 0xFF; // payload marker obrigatório (RFC 7252 §3.1)
    memcpy(pkt + pos, _payloadBuf, _payloadLen);
    pos += _payloadLen;

    // ── Envia pelo canal DTLS ─────────────────────────────────
    _sendTime = millis();
    _metrics.totalSent++;
    _pendingMsgId = msgId;

    #if COAP_USE_DTLS
        int sent = dtlsClient.send(pkt, pos);
        if (sent < 0) {
            Serial.println("[CoAP] Erro ao enviar pelo canal DTLS.");
            _state = COAP_STATE_FAILED;
            return 0;
        }
    #else
        _udp.beginPacket(GATEWAY_IP, GATEWAY_PORT);
        _udp.write(pkt, pos);
        if (!_udp.endPacket()) {
            Serial.println("[CoAP] Erro ao enviar UDP.");
            _state = COAP_STATE_FAILED;
            return 0;
        }
        int sent = pos;
    #endif

    Serial.printf("[CoAP] Enviado | msgId=%d | seq=%lu | retry=%d | %d bytes\n",
                  msgId, (unsigned long)_metrics.totalSent,
                  _retryCount, pos);
    return msgId;
}

// ------------------------------------------------------------
//  loop() — processa ACKs e verifica timeout
// ------------------------------------------------------------
void CoapClient::loop() {

    #if COAP_USE_DTLS
        if (_state == COAP_STATE_WAITING_ACK && !dtlsClient.isConnected()) {
            Serial.println("[CoAP] Sessão DTLS caiu durante espera de ACK. Resetando.");
            _state = COAP_STATE_FAILED;
        }
    #endif
    
    if (_state == COAP_STATE_WAITING_ACK) {
        _pollAck();
        _checkTimeout();
    }
}

// ------------------------------------------------------------
//  _checkTimeout() — retransmissão com backoff
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
//  _pollAck() — tenta ler um pacote do canal DTLS
// ------------------------------------------------------------
void CoapClient::_pollAck() {
    uint8_t buf[64];
    int len = 0;

#if COAP_USE_DTLS
    // Tenta ler por até 1000ms (ACK chega em ~30-50ms normalmente)
    uint32_t deadline = millis() + 1000;
    while (millis() < deadline) {
        len = dtlsClient.recv(buf, sizeof(buf));
        if (len > 0) break;
        if (len < 0) return;  // sessão caiu
        delay(5);
    }
#else
    int available = _udp.parsePacket();
    if (available > 0) {
        len = _udp.read(buf, sizeof(buf));
    }
#endif

    if (len > 0) {
        _handlePacket(buf, len);
    }
}

// ------------------------------------------------------------
//  _handlePacket() — parseia header CoAP e processa ACK
// ------------------------------------------------------------
void CoapClient::_handlePacket(const uint8_t* buf, int len) {
    if (len < 4) {
        Serial.println("[CoAP] Pacote inválido (tamanho < 4 bytes). Ignorado.");
        return;
    }

    uint8_t  type  = (buf[0] >> 4) & 0x03;
    uint8_t  code  =  buf[1];
    uint16_t msgId = ((uint16_t)buf[2] << 8) | buf[3];

    if ((type == COAP_TYPE_ACK) && (msgId == _pendingMsgId)) {
        uint32_t rtt = millis() - _sendTime;
        _lastRTT     = rtt;

        _metrics.totalAcked++;
        _metrics.rttSum += rtt;
        if (rtt < _metrics.rttMin) _metrics.rttMin = rtt;
        if (rtt > _metrics.rttMax) _metrics.rttMax = rtt;

        _state = COAP_STATE_SUCCESS;

        Serial.printf("[CoAP] ACK recebido | msgId=%d | code=0x%02X | RTT=%lums\n",
                      msgId, code, rtt);
    } else {
        Serial.printf("[CoAP] Pacote inesperado | type=%d | code=0x%02X | msgId=%d\n",
                      type, code, msgId);
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