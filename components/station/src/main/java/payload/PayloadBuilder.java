package payload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sensor.SensorData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PayloadBuilder {

    private static final Logger log = LoggerFactory.getLogger(PayloadBuilder.class);

    // Alerta se o JSON ultrapassar este tamanho (bytes)
    private static final int JSON_SIZE_WARN = 220;

    private final ObjectMapper mapper;

    public PayloadBuilder() {
        this.mapper = new ObjectMapper();
        // Desabilita indentação — payload compacto para IoT
        this.mapper.disable(SerializationFeature.INDENT_OUTPUT);
    }

    public Optional<String> build(SensorData data) {
        List<String> errors = validate(data);

        if (!errors.isEmpty()) {
            log.warn("[Payload] Validação falhou (seq={}):", data.seq());
            errors.forEach(e -> log.warn("[Payload]   → {}", e));
            return Optional.empty();
        }

        try {
            String json = mapper.writeValueAsString(data);

            if (json.length() > JSON_SIZE_WARN) {
                log.warn("[Payload] JSON grande: {} bytes (limite sugerido: {})",
                        json.length(), JSON_SIZE_WARN);
            }

            log.debug("[Payload] seq={} | {} bytes | {}",
                    data.seq(), json.length(), json);

            return Optional.of(json);
        } catch (Exception e) {
            log.error("[Payload] Erro de serialização (seq={}): {}",
                    data.seq(), e.getMessage());
            return Optional.empty();
        }
    }

    public List<String> validate(SensorData data) {
        List<String> errors = new ArrayList<>();

        if (data == null) {
            errors.add("SensorData é null");
            return errors;
        }

        // Identificação
        if (data.stationId() == null || data.stationId().isBlank()) {
            errors.add("stationId vazio ou null");
        }
        if (data.timestamp() == null || data.timestamp().isBlank()) {
            errors.add("timestamp vazio ou null");
        }

        // Coordenadas
        if (data.lat() < -90.0 || data.lat() > 90.0) {
            errors.add(String.format("lat=%.6f fora de [-90, 90]", data.lat()));
        }
        if (data.lon() < -180.0 || data.lon() > 180.0) {
            errors.add(String.format("lon=%.6f fora de [-180, 180]", data.lon()));
        }

        // Temperatura
        if (data.tempC() < -20.0 || data.tempC() > 60.0) {
            errors.add(String.format("tmp=%.2f fora de [-20, 60]°C", data.tempC()));
        }

        // Umidade
        if (data.humPct() < 0.0 || data.humPct() > 100.0) {
            errors.add(String.format("hum=%.1f fora de [0, 100]%%", data.humPct()));
        }

        // Pressão
        if (data.pressureHpa() < 800.0 || data.pressureHpa() > 1100.0) {
            errors.add(String.format("pres=%.2f fora de [800, 1100] hPa",
                    data.pressureHpa()));
        }

        // CO2
        if (data.co2Ppm() < 300.0 || data.co2Ppm() > 5000.0) {
            errors.add(String.format("co2=%.1f fora de [300, 5000] ppm",
                    data.co2Ppm()));
        }

        // UV
        if (data.uvIndex() < 0.0 || data.uvIndex() > 11.0) {
            errors.add(String.format("uv=%.1f fora de [0, 11]", data.uvIndex()));
        }

        // RSSI
        if (data.rssi() < -120 || data.rssi() > 0) {
            errors.add(String.format("rssi=%d fora de [-120, 0] dBm", data.rssi()));
        }

        // Sequência
        if (data.seq() <= 0) {
            errors.add("seq deve ser > 0, recebido: " + data.seq());
        }

        return errors;
    }
}
