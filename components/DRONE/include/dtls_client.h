#pragma once

// ============================================================
//  dtls_client.h — Canal DTLS/PSK sobre UDP via mbedTLS
//
//  Encapsula o handshake DTLS e expõe send()/recv() com a
//  mesma assinatura que WiFiUDP usa internamente na coap-simple,
//  permitindo que o CoapClient troque o transporte sem mudar
//  sua lógica de retransmissão e métricas.
//
//  Modo de segurança: PSK (Pre-Shared Key) — RFC 4279
//  Versão DTLS negociada: 1.2 (mbedTLS padrão no ESP32)
// ============================================================

#include <Arduino.h>
#include <WiFiUdp.h>
#include <mbedtls/ssl.h>
#include <mbedtls/entropy.h>
#include <mbedtls/ctr_drbg.h>
#include <mbedtls/timing.h>
#include <mbedtls/error.h>
#include "config.h"
#include "secrets.h"

enum DtlsConnectResult{
    DTLS_OK = 0,
    DTLS_ERR_UDP,        // falha ao iniciar UDP
    DTLS_ERR_HANDSHAKE,  // handshake falhou
    DTLS_ERR_CONFIG,     // erro de configuração mbedTLS
};

class DtlsClient {
public:
    DtlsClient() = default;
    ~DtlsClient() { close(); }

    DtlsConnectResult connect(const char* host, uint16_t port);

    // Envia bytes pelo canal DTLS estabelecido.
    // Retorna número de bytes enviados ou -1 em erro.
    int send(const uint8_t* buf, size_t len);

    // Lê bytes do canal DTLS (não bloqueante).
    // Retorna número de bytes lidos, 0 se não há dados, -1 em erro.
    int recv(uint8_t* buf, size_t maxLen);

    // Encerra a sessão DTLS e libera recursos.
    void close();

    // True se o handshake foi concluído com sucesso.
    bool isConnected() const { return _connected; }

    // Tempo do último handshake em ms (útil para medir overhead DTLS).
    uint32_t lastHandshakeMs() const { return _handshakeMs; }

private:
    // ── Contextos mbedTLS ─────────────────────────────────────
    mbedtls_ssl_context      _ssl;
    mbedtls_ssl_config       _conf;
    mbedtls_entropy_context  _entropy;
    mbedtls_ctr_drbg_context _ctrDrbg;
    mbedtls_timing_delay_context _timer;

    // ── Transporte UDP subjacente ─────────────────────────────
    WiFiUDP  _udp;
    IPAddress _serverIp;
    uint16_t  _serverPort = 0;

    bool     _connected    = false;
    bool     _initialized  = false;   // true após mbedtls_ssl_init() completar
    uint32_t _handshakeMs  = 0;

    // ── Callbacks de I/O exigidos pelo mbedTLS ────────────────
    // mbedTLS exige funções estáticas para send/recv do BIO
    static int _mbedSend(void* ctx, const unsigned char* buf, size_t len);
    static int _mbedRecv(void* ctx, unsigned char* buf, size_t len);
    static int _mbedRecvTimeout(void* ctx, unsigned char* buf,
                                size_t len, uint32_t timeout);
};

extern DtlsClient dtlsClient;