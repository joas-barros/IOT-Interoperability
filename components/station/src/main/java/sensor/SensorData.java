package sensor;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SensorData(
        @JsonProperty("id")
        String  stationId,

        @JsonProperty("ts")
        String  timestamp,

        @JsonProperty("lat")
        double  lat,

        @JsonProperty("lon")
        double  lon,

        @JsonProperty("tmp")
        double  tempC,

        @JsonProperty("hum")
        double  humPct,

        @JsonProperty("pres")
        double  pressureHpa,

        @JsonProperty("co2")
        double  co2Ppm,

        @JsonProperty("uv")
        double  uvIndex,

        @JsonProperty("rssi")
        int     rssi,

        @JsonProperty("seq")
        long    seq
) {}
