package sensor;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SensorData(
        @JsonProperty("id")
        String  stationId,

        @JsonProperty("timestamp")
        String  timestamp,

        @JsonProperty("latitude")
        double  lat,

        @JsonProperty("longitude")
        double  lon,

        @JsonProperty("temperature_celcius")
        double  tempC,

        @JsonProperty("humidity_percent")
        double  humPct,

        @JsonProperty("pressure_hpa")
        double  pressureHpa,

        @JsonProperty("co2_ppm")
        double  co2Ppm,

        @JsonProperty("uv_index")
        double  uvIndex,

        @JsonProperty("rssi")
        int     rssi,

        @JsonProperty("seq")
        long    seq
) {}
