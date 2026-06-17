package normalizer;

import config.AppConfig;
import exceptions.ValidationException;
import model.NormalizedData;
import model.StationData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class StationNormalizer {

    private static final Logger log = LoggerFactory.getLogger(StationNormalizer.class);

    private final AppConfig config;

    public StationNormalizer(AppConfig config) {
        this.config = config;
    }

    public NormalizedData normalize(StationData station) throws ValidationException {
        List<String> errors = validate(station);
        if (!errors.isEmpty()) {
            throw new ValidationException("StationData inválido", errors);
        }

        NormalizedData nd = new NormalizedData();

        // ── Metadados ──
        nd.gatewayId      = config.getGatewayId();
        nd.sourceId       = station.id;
        nd.sourceType     = "STATION";
        nd.sourceProtocol = "MQTT";
        nd.payloadFormat  = "JSON";

        // ── Timestamps ──
        nd.sensorTs  = station.sensorTs;
        nd.gatewayTs = station.gatewayTs;

        // ── Posição (fixa) ──
        nd.lat        = station.lat;
        nd.lon        = station.lon;
        nd.altM       = null;        // estação não tem altitude variável
        nd.headingDeg = null;
        nd.velocityMs = null;

        // ── Ambiente comuns ──
        nd.tempC  = station.tempC;
        nd.humPct = station.humPct;

        // ── Ambiente exclusivos da estação ──
        nd.pressureHpa = station.pressureHpa;
        nd.co2Ppm      = station.co2Ppm;
        nd.uvIndex     = station.uvIndex;

        // ── Sistema ──
        nd.batteryPct = null;   // estação ligada na rede — sem bateria
        nd.batteryOk  = null;
        nd.rssiDbm    = station.rssiDbm;
        nd.seq        = station.seq;

        // ── Voo — null para estação ──
        nd.flightPhase   = null;
        nd.waypointIndex = null;

        log.debug("[StationNorm] {} → {}", station, nd);
        return nd;
    }

    public List<String> validate(StationData s) {
        List<String> errors = new ArrayList<>();

        if (s == null) { errors.add("StationData é null"); return errors; }

        if (isBlank(s.id))       errors.add("id ausente");
        if (isBlank(s.sensorTs)) errors.add("ts ausente");
        if (s.seq == null)       errors.add("seq ausente");

        if (s.lat == null)                         errors.add("lat ausente");
        else if (s.lat < -90.0 || s.lat > 90.0)   errors.add("lat fora de [-90,90]: " + s.lat);

        if (s.lon == null)                          errors.add("lon ausente");
        else if (s.lon < -180.0 || s.lon > 180.0)  errors.add("lon fora de [-180,180]: " + s.lon);

        if (s.tempC == null)                           errors.add("tmp ausente");
        else if (s.tempC < -20.0 || s.tempC > 60.0)   errors.add("tmp fora de [-20,60]: " + s.tempC);

        if (s.humPct == null)                          errors.add("hum ausente");
        else if (s.humPct < 0.0 || s.humPct > 100.0)  errors.add("hum fora de [0,100]: " + s.humPct);

        if (s.pressureHpa != null && (s.pressureHpa < 800.0 || s.pressureHpa > 1100.0))
            errors.add("pres fora de [800,1100]: " + s.pressureHpa);

        if (s.co2Ppm != null && (s.co2Ppm < 300.0 || s.co2Ppm > 5000.0))
            errors.add("co2 fora de [300,5000]: " + s.co2Ppm);

        if (s.uvIndex != null && (s.uvIndex < 0.0 || s.uvIndex > 11.0))
            errors.add("uv fora de [0,11]: " + s.uvIndex);

        if (s.seq != null && s.seq <= 0)
            errors.add("seq deve ser > 0: " + s.seq);

        return errors;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
