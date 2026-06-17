package model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class StationData {

    // ── Identificação ──────────────────────────────────────────────────────
    @JsonProperty("id")    public String  id;
    @JsonProperty("ts")    public String  sensorTs;

    // ── Posição (fixa) ─────────────────────────────────────────────────────
    @JsonProperty("lat")   public Double  lat;
    @JsonProperty("lon")   public Double  lon;

    // ── Ambiente ───────────────────────────────────────────────────────────
    @JsonProperty("tmp")   public Double  tempC;
    @JsonProperty("hum")   public Double  humPct;
    @JsonProperty("pres")  public Double  pressureHpa;  // exclusivo da estação
    @JsonProperty("co2")   public Double  co2Ppm;       // exclusivo da estação
    @JsonProperty("uv")    public Double  uvIndex;      // exclusivo da estação

    // ── Sistema ────────────────────────────────────────────────────────────
    @JsonProperty("rssi")  public Integer rssiDbm;
    @JsonProperty("seq")   public Long    seq;

    // ── Adicionado pelo gateway ────────────────────────────────────────────
    public String gatewayTs;

    @Override
    public String toString() {
        return String.format("StationData{id=%s, seq=%s, lat=%.5f, lon=%.5f, " +
                        "tmp=%.2f, hum=%.1f, pres=%.2f, co2=%.1f, uv=%.1f}",
                id, seq,
                lat != null ? lat : 0, lon != null ? lon : 0,
                tempC != null ? tempC : 0, humPct != null ? humPct : 0,
                pressureHpa != null ? pressureHpa : 0,
                co2Ppm != null ? co2Ppm : 0,
                uvIndex != null ? uvIndex : 0);
    }
}
