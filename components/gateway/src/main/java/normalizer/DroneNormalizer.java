package normalizer;

import config.AppConfig;
import exceptions.ValidationException;
import model.DroneData;
import model.NormalizedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class DroneNormalizer {

    private static final Logger log = LoggerFactory.getLogger(DroneNormalizer.class);

    private final AppConfig config;

    public DroneNormalizer(AppConfig config) {
        this.config = config;
    }

    public NormalizedData normalize(DroneData drone) throws ValidationException {
        List<String> errors = validate(drone);

        if (!errors.isEmpty()) {
            throw new ValidationException("DroneData inválido", errors);
        }

        NormalizedData nd = new NormalizedData();

        // ── Metadados ──
        nd.gatewayId      = config.getGatewayId();
        nd.sourceId       = drone.id;
        nd.sourceType     = "DRONE";
        nd.sourceProtocol = "CoAP";
        nd.payloadFormat  = drone.contentFormat;  // "CBOR" ou "JSON"

        // ── Timestamps ──
        nd.sensorTs  = drone.sensorTs;
        nd.gatewayTs = drone.gatewayTs;

        // ── Posição ──
        nd.lat        = drone.lat;
        nd.lon        = drone.lon;
        nd.altM       = drone.altM;
        nd.headingDeg = drone.headingDeg;
        nd.velocityMs = drone.velocityMs;

        // ── Ambiente ──
        nd.tempC  = drone.tempC;
        nd.humPct = drone.humPct;

        // ── Campos exclusivos da estação → null ──
        nd.pressureHpa = null;
        nd.co2Ppm      = null;
        nd.uvIndex     = null;

        // ── Sistema ──
        nd.batteryPct = drone.batteryPct;
        nd.batteryOk  = drone.batteryOk;
        nd.rssiDbm    = drone.rssiDbm;
        nd.seq        = drone.seq;

        // ── Voo ──
        nd.flightPhase    = drone.flightPhase;
        nd.waypointIndex  = drone.waypointIndex;

        log.debug("[DroneNorm] {} → {}", drone, nd);
        return nd;
    }

    public List<String> validate(DroneData d) {
        List<String> errors = new ArrayList<>();

        if (d == null) { errors.add("DroneData é null"); return errors; }

        // Campos obrigatórios
        if (isBlank(d.id))       errors.add("id ausente");
        if (isBlank(d.sensorTs)) errors.add("ts ausente");
        if (d.seq == null)       errors.add("seq ausente");

        // Coordenadas
        if (d.lat == null)                          errors.add("lat ausente");
        else if (d.lat < -90.0 || d.lat > 90.0)    errors.add("lat fora de [-90,90]: " + d.lat);

        if (d.lon == null)                          errors.add("lon ausente");
        else if (d.lon < -180.0 || d.lon > 180.0)  errors.add("lon fora de [-180,180]: " + d.lon);

        // Altitude
        if (d.altM != null && (d.altM < -10.0 || d.altM > 500.0))
            errors.add("alt fora de [-10,500]: " + d.altM);

        // Temperatura
        if (d.tempC == null)                           errors.add("tmp ausente");
        else if (d.tempC < -20.0 || d.tempC > 60.0)   errors.add("tmp fora de [-20,60]: " + d.tempC);

        // Umidade
        if (d.humPct == null)                          errors.add("hum ausente");
        else if (d.humPct < 0.0 || d.humPct > 100.0)  errors.add("hum fora de [0,100]: " + d.humPct);

        // Bateria
        if (d.batteryPct != null && (d.batteryPct < 0 || d.batteryPct > 100))
            errors.add("bat fora de [0,100]: " + d.batteryPct);

        // Sequência
        if (d.seq != null && d.seq <= 0)
            errors.add("seq deve ser > 0: " + d.seq);

        return errors;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
