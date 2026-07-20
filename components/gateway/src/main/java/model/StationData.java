package model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class StationData {

    // ── Identificação ──────────────────────────────────────────────────────
    @JsonProperty("id")    public String  id;
    @JsonProperty("timestamp")    public String  sensorTs;

    // ── Posição (fixa) ─────────────────────────────────────────────────────
    @JsonProperty("latitude")   public Double  lat;
    @JsonProperty("longitude")   public Double  lon;

    // ── Ambiente ───────────────────────────────────────────────────────────
    @JsonProperty("temperature_celcius")   public Double  tempC;
    @JsonProperty("humidity_percent")   public Double  humPct;
    @JsonProperty("pressure_hpa")  public Double  pressureHpa;  // exclusivo da estação
    @JsonProperty("co2_ppm")   public Double  co2Ppm;       // exclusivo da estação
    @JsonProperty("uv_index")    public Double  uvIndex;      // exclusivo da estação

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
