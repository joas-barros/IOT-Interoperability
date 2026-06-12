package model;

public class NormalizedData {

    // ── Metadados do gateway ───────────────────────────────────────────────
    public String  gatewayId;
    public String  sourceId;          // id original do dispositivo
    public String  sourceType;        // "DRONE" ou "STATION"
    public String  sourceProtocol;    // "CoAP" ou "MQTT"
    public String  payloadFormat;     // "CBOR" ou "JSON" (só para drone)

    // ── Timestamps ─────────────────────────────────────────────────────────
    public String  sensorTs;          // gerado no dispositivo
    public String  gatewayTs;         // adicionado no recebimento

    // ── Posição ────────────────────────────────────────────────────────────
    public Double  lat;
    public Double  lon;
    public Double  altM;              // null para estação
    public Double  headingDeg;        // null para estação
    public Double  velocityMs;        // null para estação

    // ── Ambiente (comuns) ──────────────────────────────────────────────────
    public Double  tempC;
    public Double  humPct;

    // ── Ambiente (exclusivos da estação) ───────────────────────────────────
    public Double  pressureHpa;       // null para drone
    public Double  co2Ppm;            // null para drone
    public Double  uvIndex;           // null para drone

    // ── Sistema ────────────────────────────────────────────────────────────
    public Integer batteryPct;        // null para estação
    public Boolean batteryOk;         // null para estação
    public Integer rssiDbm;
    public Long    seq;

    // ── Voo (exclusivos do drone) ──────────────────────────────────────────
    public String  flightPhase;       // null para estação
    public Integer waypointIndex;     // null para estação

    @Override
    public String toString() {
        return String.format(
                "NormalizedData{src=%s/%s/%s, seq=%s, lat=%.5f, lon=%.5f, " +
                        "tmp=%.2f, hum=%.1f, alt=%s, pres=%s, co2=%s}",
                sourceId, sourceType, sourceProtocol, seq,
                lat != null ? lat : 0, lon != null ? lon : 0,
                tempC != null ? tempC : 0, humPct != null ? humPct : 0,
                altM, pressureHpa, co2Ppm);
    }
}
