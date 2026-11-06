#include "dtls_client.h"

DtlsClient dtlsClient;

// ------------------------------------------------------------
//  Helpers internos
// ------------------------------------------------------------

static void _logMbedError(const char * label, int ret) {
    char buf[128];
    mbedtls_strerror(ret, buf, sizeof(buf));
    Serial.printf("[DTLS] %s: -0x%04X — %s\n", label, (unsigned)(-ret), buf);
}

// ------------------------------------------------------------
//  connect() — realiza o handshake DTLS/PSK
// ------------------------------------------------------------
DtlsConnectResult DtlsClient::connect(const char* host, uint16_t port) {
    close();  // fecha qualquer sessão anterior

    // Resolve IP do host
    _serverIp.fromString(host);
    _serverPort = port;

    // Inicia UDP local em porta efêmera
    if (!_udp.begin(0)) {
        Serial.println("[DTLS] Falha ao iniciar UDP.");
        return DTLS_ERR_UDP;
    }

    // ── Inicializa contextos mbedTLS ─────────────────────────
    mbedtls_ssl_init(&_ssl);
    mbedtls_ssl_config_init(&_conf);
    mbedtls_entropy_init(&_entropy);
    mbedtls_ctr_drbg_init(&_ctrDrbg);

    int ret;

    // Semente do gerador pseudoaleatório (PRNG)
    const char* pers = "dtls_esp32_drone";
    ret = mbedtls_ctr_drbg_seed(&_ctrDrbg, mbedtls_entropy_func,
                                  &_entropy,
                                  (const unsigned char*)pers, strlen(pers));
    if (ret != 0) {
        _logMbedError("ctr_drbg_seed", ret);
        return DTLS_ERR_CONFIG;
    }

    // Configuração DTLS: cliente, PSK, DTLS 1.2
    ret = mbedtls_ssl_config_defaults(&_conf,
                                       MBEDTLS_SSL_IS_CLIENT,
                                       MBEDTLS_SSL_TRANSPORT_DATAGRAM,  // UDP → DTLS
                                       MBEDTLS_SSL_PRESET_DEFAULT);
    if (ret != 0) {
        _logMbedError("ssl_config_defaults", ret);
        return DTLS_ERR_CONFIG;
    }

     // Desativa verificação de certificado — modo PSK puro
    mbedtls_ssl_conf_authmode(&_conf, MBEDTLS_SSL_VERIFY_NONE);

    // Registra o gerador aleatório
    mbedtls_ssl_conf_rng(&_conf, mbedtls_ctr_drbg_random, &_ctrDrbg);

    // Configura a PSK: ID do cliente + chave compartilhada
    ret = mbedtls_ssl_conf_psk(
        &_conf,
        (const unsigned char*)DTLS_PSK_KEY, DTLS_PSK_KEY_LEN,
        (const unsigned char*)DTLS_PSK_ID,  strlen(DTLS_PSK_ID));
    if (ret != 0) {
        _logMbedError("ssl_conf_psk", ret);
        return DTLS_ERR_CONFIG;
    }

    // Timeout do handshake DTLS: mín 1s, máx 10s (dobra a cada retry)
    mbedtls_ssl_conf_handshake_timeout(&_conf, 1000, 10000);

    // Aplica configuração ao contexto SSL
    ret = mbedtls_ssl_setup(&_ssl, &_conf);
    if (ret != 0) {
        _logMbedError("ssl_setup", ret);
        return DTLS_ERR_CONFIG;
    }

    // Registra o timer (obrigatório para DTLS — controla retransmissões do handshake)
    mbedtls_ssl_set_timer_cb(&_ssl, &_timer,
                              mbedtls_timing_set_delay,
                              mbedtls_timing_get_delay);
    
    // Registra callbacks de I/O: send e recv passam `this` como contexto
    mbedtls_ssl_set_bio(&_ssl, this,
                         _mbedSend,
                         _mbedRecv,
                         _mbedRecvTimeout);
    
    // ── Handshake ────────────────────────────────────────────
    Serial.printf("[DTLS] Iniciando handshake PSK com %s:%d ...\n", host, port);
    uint32_t t0 = millis();

    do {
        ret = mbedtls_ssl_handshake(&_ssl);
    } while (ret == MBEDTLS_ERR_SSL_WANT_READ ||
             ret == MBEDTLS_ERR_SSL_WANT_WRITE);

    _handshakeMs = millis() - t0;
    
    if (ret != 0) {
        _logMbedError("ssl_handshake", ret);
        close();
        return DTLS_ERR_HANDSHAKE;
    }

    _connected = true;
    Serial.printf("[DTLS] Handshake concluído em %lums | cipher: %s\n",
                  _handshakeMs,
                  mbedtls_ssl_get_ciphersuite(&_ssl));
    return DTLS_OK;
}

// ------------------------------------------------------------
//  send() — envia dados pelo canal DTLS
// ------------------------------------------------------------
int DtlsClient::send(const uint8_t* buf, size_t len) {
    if (!_connected) return -1;

    int ret;
    do {
        ret = mbedtls_ssl_write(&_ssl, buf, len);
    } while (ret == MBEDTLS_ERR_SSL_WANT_WRITE);

    if (ret < 0) {
        _logMbedError("ssl_write", ret);
        _connected = false;
    }
    return ret;
}

// ------------------------------------------------------------
//  recv() — lê dados do canal DTLS (não bloqueante)
// ------------------------------------------------------------
int DtlsClient::recv(uint8_t* buf, size_t maxLen) {
    if (!_connected) return -1;

    int ret = mbedtls_ssl_read(&_ssl, buf, maxLen);

    if (ret == MBEDTLS_ERR_SSL_WANT_READ ||
        ret == MBEDTLS_ERR_SSL_TIMEOUT) {
        return 0; // sem dados disponíveis agora
    }
    if (ret < 0) {
        _logMbedError("ssl_read", ret);
        _connected = false;
        return -1;
    }
    return ret;
}

// ------------------------------------------------------------
//  close() — encerra sessão e libera recursos
// ------------------------------------------------------------
void DtlsClient::close() {
    if (_connected) {
        mbedtls_ssl_close_notify(&_ssl);
    }

    // Libera contextos mbedTLS
    mbedtls_ssl_free(&_ssl);
    mbedtls_ssl_config_free(&_conf);
    mbedtls_ctr_drbg_free(&_ctrDrbg);
    mbedtls_entropy_free(&_entropy);

    // Fecha UDP
    _udp.stop();

    _connected = false;
    _handshakeMs = 0;
}

// ------------------------------------------------------------
//  Callbacks de I/O para o mbedTLS
//  mbedTLS chama estas funções para enviar/receber bytes UDP
// ------------------------------------------------------------

int DtlsClient::_mbedSend(void* ctx, const unsigned char* buf, size_t len) {
    DtlsClient* self = static_cast<DtlsClient*>(ctx);
    self->_udp.beginPacket(self->_serverIp, self->_serverPort);
    self->_udp.write(buf, len);
    return self->_udp.endPacket() ? (int)len : MBEDTLS_ERR_SSL_WANT_WRITE;
}

int DtlsClient::_mbedRecv(void* ctx, unsigned char* buf, size_t len) {
    DtlsClient* self = static_cast<DtlsClient*>(ctx);
    int available = self->_udp.parsePacket();
    if (available <= 0) return MBEDTLS_ERR_SSL_WANT_READ;
    int n = self->_udp.read(buf, len);
    return n > 0 ? n : MBEDTLS_ERR_SSL_WANT_READ;
}

int DtlsClient::_mbedRecvTimeout(void* ctx, unsigned char* buf,
                                   size_t len, uint32_t timeout) {
    DtlsClient* self = static_cast<DtlsClient*>(ctx);
    uint32_t deadline = millis() + timeout;

    while (millis() < deadline) {
        int available = self->_udp.parsePacket();
        if (available > 0) {
            int n = self->_udp.read(buf, len);
            return n > 0 ? n : MBEDTLS_ERR_SSL_WANT_READ;
        }
        delay(5); // yield para o watchdog do ESP32
    }
    return MBEDTLS_ERR_SSL_TIMEOUT;
}