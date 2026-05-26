import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import payload.PayloadBuilder;
import sensor.SensorData;
import sensor.SensorSim;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Teste 3 — PayloadBuilder")
public class PayloadBuilderTest {

    private static final int MAX_JSON_BYTES = 256;

    private AppConfig config;
    private SensorSim sensor;
    private PayloadBuilder builder;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        config  = new AppConfig();
        sensor  = new SensorSim(config);
        builder = new PayloadBuilder();
        mapper  = new ObjectMapper();
    }

    // ── Payload válido ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Deve gerar JSON para dados válidos")
    void deveGerarJsonParaDadosValidos() {
        SensorData data = sensor.read();
        Optional<String> result = builder.build(data);
        assertTrue(result.isPresent(), "build() retornou empty para dados válidos");
    }

    @Test
    @DisplayName("JSON deve ser parseável")
    void jsonDeveSerParseavel() throws Exception {
        SensorData data   = sensor.read();
        String     json   = builder.build(data).orElseThrow();
        JsonNode parsed = mapper.readTree(json);
        assertNotNull(parsed, "JSON não parseável");
    }

    @Test
    @DisplayName("JSON deve conter todos os campos obrigatórios")
    void jsonDeveConterTodosOsCampos() throws Exception {
        SensorData data   = sensor.read();
        String     json   = builder.build(data).orElseThrow();
        JsonNode   parsed = mapper.readTree(json);

        assertAll("Campos obrigatórios",
                () -> assertTrue(parsed.has("id"),   "campo 'id' ausente"),
                () -> assertTrue(parsed.has("ts"),   "campo 'ts' ausente"),
                () -> assertTrue(parsed.has("lat"),  "campo 'lat' ausente"),
                () -> assertTrue(parsed.has("lon"),  "campo 'lon' ausente"),
                () -> assertTrue(parsed.has("tmp"),  "campo 'tmp' ausente"),
                () -> assertTrue(parsed.has("hum"),  "campo 'hum' ausente"),
                () -> assertTrue(parsed.has("pres"), "campo 'pres' ausente"),
                () -> assertTrue(parsed.has("co2"),  "campo 'co2' ausente"),
                () -> assertTrue(parsed.has("uv"),   "campo 'uv' ausente"),
                () -> assertTrue(parsed.has("rssi"), "campo 'rssi' ausente"),
                () -> assertTrue(parsed.has("seq"),  "campo 'seq' ausente")
        );
    }

    @Test
    @DisplayName("Valores do JSON devem corresponder ao SensorData")
    void valoresDevemCorresponder() throws Exception {
        SensorData data   = sensor.read();
        String     json   = builder.build(data).orElseThrow();
        JsonNode   parsed = mapper.readTree(json);

        assertAll("Valores correspondentes",
                () -> assertEquals(data.stationId(), parsed.get("id").asText()),
                () -> assertEquals(data.timestamp(), parsed.get("ts").asText()),
                () -> assertEquals(data.tempC(),     parsed.get("tmp").asDouble(), 0.001),
                () -> assertEquals(data.humPct(),    parsed.get("hum").asDouble(), 0.001),
                () -> assertEquals(data.seq(),       parsed.get("seq").asLong())
        );
    }

    @Test
    @DisplayName("Tamanho do JSON deve ser menor que " + MAX_JSON_BYTES + " bytes")
    void tamanhoDoJsonDeveEstarNoLimite() {
        SensorData data = sensor.read();
        String     json = builder.build(data).orElseThrow();

        System.out.printf("%n  JSON gerado (%d bytes): %s%n", json.length(), json);

        assertTrue(json.length() < MAX_JSON_BYTES,
                String.format("JSON muito grande: %d bytes (limite: %d)",
                        json.length(), MAX_JSON_BYTES));
    }

    // ── Sequência ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Seq deve incrementar a cada leitura gerada")
    void seqDeveIncrementar() throws Exception {
        for (int i = 1; i <= 5; i++) {
            SensorData data = sensor.read();
            String     json = builder.build(data).orElseThrow();
            JsonNode   node = mapper.readTree(json);
            assertEquals(i, node.get("seq").asLong(),
                    "seq esperado " + i + " mas foi " + node.get("seq").asLong());
        }
    }

    // ── Sumário ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Sumário — imprime exemplos de payloads gerados")
    void sumarioPayloads() {
        System.out.println("\n══════════════════════════════════════════");
        System.out.println("  SUMÁRIO — PayloadBuilder");
        System.out.println("══════════════════════════════════════════");

        for (int i = 1; i <= 3; i++) {
            SensorData data = sensor.read();
            Optional<String> result = builder.build(data);
            if (result.isPresent()) {
                System.out.printf("  [%d] %d bytes: %s%n",
                        i, result.get().length(), result.get());
            }
        }
        System.out.println("══════════════════════════════════════════\n");
    }
}
