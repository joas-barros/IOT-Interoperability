#pragma once

// ============================================================
//  tinycbor_arduino.h — TinyCBOR portado para Arduino/ESP32
//
//  Implementação mínima de escrita CBOR (RFC 7049) suficiente
//  para serializar o payload do drone.
//
//  Tipos CBOR usados neste projeto:
//    Major type 0 → unsigned integer
//    Major type 1 → negative integer
//    Major type 2 → byte string
//    Major type 3 → text string
//    Major type 4 → array
//    Major type 5 → map (objeto chave/valor)
//    Major type 7 → float / bool / null
//
//  Referência: https://www.rfc-editor.org/rfc/rfc7049
// ============================================================

#include <Arduino.h>
#include <stdint.h>
#include <string.h>
#include <math.h>

// Tamanho máximo do buffer CBOR (bytes)
// Payload do drone em CBOR: ~90 bytes
// Margem de segurança: 2x
#define CBOR_BUF_SIZE 192

// ── Códigos de erro ───────────────────────────────────────────
#define CBOR_OK            0
#define CBOR_ERR_OVERFLOW -1   // buffer cheio
#define CBOR_ERR_PARAM    -2   // parâmetro inválido

// ── Major types (bits 7-5 do byte inicial) ────────────────────
#define CBOR_UINT    0x00
#define CBOR_NEGINT  0x20
#define CBOR_BSTR    0x40
#define CBOR_TSTR    0x60
#define CBOR_ARRAY   0x80
#define CBOR_MAP     0xA0
#define CBOR_FLOAT16 0xF9
#define CBOR_FLOAT32 0xFA
#define CBOR_FLOAT64 0xFB
#define CBOR_FALSE   0xF4
#define CBOR_TRUE    0xF5
#define CBOR_NULL    0xF6

/**
 * CborWriter — Escreve dados CBOR em um buffer fixo.
 *
 * Uso típico:
 *   uint8_t buf[CBOR_BUF_SIZE];
 *   CborWriter w(buf, sizeof(buf));
 *   w.writeMapOpen(3);
 *   w.writeTextString("id");   w.writeTextString("drone_01");
 *   w.writeTextString("tmp");  w.writeFloat(28.5f);
 *   w.writeTextString("seq");  w.writeUInt(42);
 *   size_t len = w.length();   // bytes escritos
 */
class CborWriter {
public:
    CborWriter(uint8_t* buf, size_t capacity)
        : _buf(buf), _cap(capacity), _pos(0), _err(CBOR_OK) {}

    // ── Map / Array ───────────────────────────────────────────

    /** Abre um map CBOR com N pares chave/valor. */
    void writeMapOpen(uint8_t count) {
        writeByte(CBOR_MAP | (count & 0x1F));
    }

    /** Abre um array CBOR com N elementos. */
    void writeArrayOpen(uint8_t count) {
        writeByte(CBOR_ARRAY | (count & 0x1F));
    }

    // ── Strings ───────────────────────────────────────────────

    /** Escreve uma string de texto UTF-8. */
    void writeTextString(const char* str) {
        if (!str) { writeNull(); return; }
        size_t len = strlen(str);
        writeHead(CBOR_TSTR, len);
        writeBytes((const uint8_t*)str, len);
    }

    // ── Inteiros ──────────────────────────────────────────────

    /** Escreve um inteiro sem sinal. */
    void writeUInt(uint32_t val) {
        writeHead(CBOR_UINT, val);
    }

    /** Escreve um inteiro com sinal (positivo ou negativo). */
    void writeInt(int32_t val) {
        if (val >= 0) {
            writeHead(CBOR_UINT, (uint32_t)val);
        } else {
            writeHead(CBOR_NEGINT, (uint32_t)(-1 - val));
        }
    }

    // ── Ponto flutuante ───────────────────────────────────────

    /**
     * Escreve um float de 32 bits (IEEE 754).
     * 5 bytes: 0xFA + 4 bytes big-endian.
     *
     * Preferimos float32 a float16 para manter precisão de
     * lat/lon (6 casas decimais requerem ~23 bits de mantissa).
     */
    void writeFloat(float val) {
        if (_pos + 5 > _cap) { _err = CBOR_ERR_OVERFLOW; return; }
        _buf[_pos++] = CBOR_FLOAT32;
        // Copia os 4 bytes do float em big-endian
        uint32_t bits;
        memcpy(&bits, &val, 4);
        _buf[_pos++] = (bits >> 24) & 0xFF;
        _buf[_pos++] = (bits >> 16) & 0xFF;
        _buf[_pos++] = (bits >>  8) & 0xFF;
        _buf[_pos++] = (bits >>  0) & 0xFF;
    }

    /**
     * Escreve um double de 64 bits — usado para lat/lon
     * que precisam de 6 casas decimais (~0.1m de precisão).
     * 9 bytes: 0xFB + 8 bytes big-endian.
     */
    void writeDouble(double val) {
        if (_pos + 9 > _cap) { _err = CBOR_ERR_OVERFLOW; return; }
        _buf[_pos++] = CBOR_FLOAT64;
        uint64_t bits;
        memcpy(&bits, &val, 8);
        for (int i = 7; i >= 0; i--) {
            _buf[_pos++] = (bits >> (i * 8)) & 0xFF;
        }
    }

    // ── Bool / Null ───────────────────────────────────────────

    void writeBool(bool val) {
        writeByte(val ? CBOR_TRUE : CBOR_FALSE);
    }

    void writeNull() {
        writeByte(CBOR_NULL);
    }

    // ── Estado ────────────────────────────────────────────────

    size_t length()  const { return _pos; }
    int    error()   const { return _err; }
    bool   isOk()    const { return _err == CBOR_OK; }

    /** Reseta o writer para reutilizar o buffer. */
    void reset() { _pos = 0; _err = CBOR_OK; }

private:
    uint8_t* _buf;
    size_t   _cap;
    size_t   _pos;
    int      _err;

    void writeByte(uint8_t b) {
        if (_pos >= _cap) { _err = CBOR_ERR_OVERFLOW; return; }
        _buf[_pos++] = b;
    }

    void writeBytes(const uint8_t* data, size_t len) {
        if (_pos + len > _cap) { _err = CBOR_ERR_OVERFLOW; return; }
        memcpy(_buf + _pos, data, len);
        _pos += len;
    }

    /**
     * Escreve o cabeçalho CBOR (major type + argument).
     * Argument < 24    → 1 byte
     * Argument < 256   → 2 bytes (0x18 + uint8)
     * Argument < 65536 → 3 bytes (0x19 + uint16 BE)
     * Argument >= 65536→ 5 bytes (0x1A + uint32 BE)
     */
    void writeHead(uint8_t majorType, uint32_t arg) {
        if (arg < 24) {
            writeByte(majorType | (uint8_t)arg);
        } else if (arg <= 0xFF) {
            writeByte(majorType | 0x18);
            writeByte((uint8_t)arg);
        } else if (arg <= 0xFFFF) {
            writeByte(majorType | 0x19);
            writeByte((arg >> 8) & 0xFF);
            writeByte(arg & 0xFF);
        } else {
            writeByte(majorType | 0x1A);
            writeByte((arg >> 24) & 0xFF);
            writeByte((arg >> 16) & 0xFF);
            writeByte((arg >>  8) & 0xFF);
            writeByte(arg & 0xFF);
        }
    }
};
