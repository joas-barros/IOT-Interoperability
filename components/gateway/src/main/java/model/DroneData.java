package model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DroneData {
    // ── Identificação ──────────────────────────────────────────────────────
    @JsonProperty("id")    public String  id;
    @JsonProperty("ts")    public String  sensorTs;     // ISO 8601 UTC gerado no ESP32

    // ── Voo ────────────────────────────────────────────────────────────────
    @JsonProperty("f")     public String  flightPhase;  // "IDLE", "MISSION", etc.
    @JsonProperty("wp")    public Integer waypointIndex;

    // ── Posição ────────────────────────────────────────────────────────────
    @JsonProperty("lat")   public Double  lat;
    @JsonProperty("lon")   public Double  lon;
    @JsonProperty("alt")   public Double  altM;
    @JsonProperty("hdg")   public Double  headingDeg;
    @JsonProperty("vel")   public Double  velocityMs;

    // ── Ambiente ───────────────────────────────────────────────────────────
    @JsonProperty("tmp")   public Double  tempC;
    @JsonProperty("hum")   public Double  humPct;

    // ── Sistema ────────────────────────────────────────────────────────────
    @JsonProperty("bat")   public Integer batteryPct;
    @JsonProperty("bok")   public Boolean batteryOk;    // true = acima do limiar
    @JsonProperty("rssi")  public Integer rssiDbm;
    @JsonProperty("seq")   public Long    seq;

    // ── Adicionado pelo gateway no momento do recebimento ─────────────────
    // Não vem do ESP32 — preenchido pelo CoapServer antes de normalizar
    public String gatewayTs;
    public String contentFormat;   // "CBOR" ou "JSON"

    @Override
    public String toString() {
        return String.format("DroneData{id=%s, seq=%s, phase=%s, lat=%.5f, lon=%.5f, " +
                        "alt=%.1f, tmp=%.2f, bat=%d, fmt=%s}",
                id, seq, flightPhase, lat != null ? lat : 0,
                lon != null ? lon : 0, altM != null ? altM : 0,
                tempC != null ? tempC : 0, batteryPct != null ? batteryPct : 0,
                contentFormat);
    }
}
