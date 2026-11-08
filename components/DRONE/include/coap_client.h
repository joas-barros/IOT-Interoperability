#pragma once

// ============================================================
//  coap_client.h — Cliente CoAP com ACK, Timeout e Métricas
// ============================================================

#include <Arduino.h>
#include "dtls_client.h"
#include "config.h"

// Estado interno de uma mensagem em trânsito
enum CoapSendState
{
    COAP_STATE_IDLE,        // nenhuma mensagem em trânsito
    COAP_STATE_WAITING_ACK, // mensagem enviada, aguardando ACK
    COAP_STATE_SUCCESS,     // ACK recebido com sucesso
    COAP_STATE_FAILED       // máximo de retransmissões esgotado
};

// Métricas acumuladas da sessão
struct CoapMetrics
{
    uint32_t totalSent = 0;
    uint32_t totalAcked = 0;
    uint32_t totalTimeout = 0;
    uint32_t totalRetries = 0;
    uint32_t rttMin = UINT32_MAX;
    uint32_t rttMax = 0;
    uint64_t rttSum = 0; // para calcular média

    float deliveryRate() const; // % de mensagens com ACK
    float avgRtt() const;       // RTT médio em ms
    void print() const;         // imprime resumo no Serial
};

class CoapClient
{
public:
    // Inicializa o cliente CoAP e registra o callback de resposta
    // Deve ser chamado no setup(), após conectar ao Wi-Fi
    bool begin();

    // Envia payload via CoAP POST (CON) para o gateway.
    // Não bloqueante — registra o envio e retorna imediatamente.
    // Retorna o messageId da mensagem enviada (0 em caso de erro).
    uint16_t send(const uint8_t *payload,
                  size_t payloadLen,
                  uint8_t contentFormat);

    // Processa pacotes CoAP recebidos e gerencia timeout/retry.
    // DEVE ser chamado em todo loop() — é a engine do cliente.
    void loop();

    // Estado atual do último envio
    CoapSendState getState() const { return _state; }

    // RTT do último ACK recebido (ms)
    uint32_t getLastRTT() const { return _lastRTT; }

    // Métricas da sessão completa
    const CoapMetrics &getMetrics() const { return _metrics; }

    // Imprime resumo de métricas no Serial
    void printMetrics() const { _metrics.print(); }

    // True se está livre para enviar (não há mensagem pendente)
    bool isReady() const { return _state == COAP_STATE_IDLE ||
                                  _state == COAP_STATE_SUCCESS ||
                                  _state == COAP_STATE_FAILED; }

    void resetMetrics()
    {
        _metrics = CoapMetrics(); // Zera os contadores
        _state = COAP_STATE_IDLE;
    }

private:
    CoapSendState _state        = COAP_STATE_IDLE;
    uint16_t      _pendingMsgId = 0;
    uint32_t      _sendTime     = 0;
    uint8_t       _retryCount   = 0;
    uint32_t      _lastRTT      = 0;
    uint16_t      _msgIdCounter = 1; // gerador sequencial de messageId

    CoapMetrics _metrics;

    // Buffer interno para retransmissões adapta o tamanho automaticamente
    uint8_t  _payloadBuf[JSON_BUFFER_SIZE] = {};
    size_t   _payloadLen    = 0;
    uint8_t  _contentFormat = 50; // application/json

    // Monta e envia um pacote CoAP POST manualmente sobre DTLS
    uint16_t _doSend();

    // Tenta ler e processar um ACK CoAP do canal DTLS
    void _pollAck();

    // Verifica timeout e retransmite se necessário
    void _checkTimeout();

    // Processa bytes recebidos como pacote CoAP
    void _handlePacket(const uint8_t* buf, int len);
};

extern CoapClient coapClient;