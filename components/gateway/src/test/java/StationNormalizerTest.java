import config.AppConfig;
import exceptions.ValidationException;
import model.NormalizedData;
import model.StationData;
import normalizer.StationNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Teste 3 — StationNormalizer")
class StationNormalizerTest {

    private StationNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new StationNormalizer(new AppConfig());
    }

    // ── Dado válido ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Deve normalizar StationData válido corretamente")
    void deveNormalizarEstacao() throws Exception {
        StationData s  = validStation();
        NormalizedData nd = normalizer.normalize(s);

        assertAll("Normalização estação",
                () -> assertEquals("STATION", nd.sourceType),
                () -> assertEquals("MQTT",    nd.sourceProtocol),
                () -> assertEquals("JSON",    nd.payloadFormat),
                () -> assertEquals(s.id,      nd.sourceId),
                () -> assertEquals(s.lat,     nd.lat),
                () -> assertEquals(s.lon,     nd.lon),
                () -> assertEquals(s.tempC,   nd.tempC),
                () -> assertEquals(s.humPct,  nd.humPct),
                () -> assertEquals(s.pressureHpa, nd.pressureHpa),
                () -> assertEquals(s.co2Ppm,  nd.co2Ppm),
                () -> assertEquals(s.uvIndex, nd.uvIndex),
                () -> assertEquals(s.seq,     nd.seq)
        );
    }

    @Test
    @DisplayName("Campos exclusivos do drone devem ser null na estação normalizada")
    void camposDroneDevemSerNull() throws Exception {
        NormalizedData nd = normalizer.normalize(validStation());

        assertAll("Campos exclusivos do drone",
                () -> assertNull(nd.altM,          "altM deve ser null para estação"),
                () -> assertNull(nd.headingDeg,    "headingDeg deve ser null"),
                () -> assertNull(nd.velocityMs,    "velocityMs deve ser null"),
                () -> assertNull(nd.batteryPct,    "batteryPct deve ser null"),
                () -> assertNull(nd.batteryOk,     "batteryOk deve ser null"),
                () -> assertNull(nd.flightPhase,   "flightPhase deve ser null"),
                () -> assertNull(nd.waypointIndex, "waypointIndex deve ser null")
        );
    }

    @Test
    @DisplayName("Campos exclusivos da estação devem ser preservados")
    void camposExclusivosEstacaoPreservados() throws Exception {
        StationData s  = validStation();
        NormalizedData nd = normalizer.normalize(s);

        assertNotNull(nd.pressureHpa, "pressureHpa não deve ser null");
        assertNotNull(nd.co2Ppm,      "co2Ppm não deve ser null");
        assertNotNull(nd.uvIndex,     "uvIndex não deve ser null");
    }

    // ── Dados inválidos ───────────────────────────────────────────────────

    static Stream<Object[]> dadosInvalidos() {
        return Stream.of(
                new Object[]{ "id null",       stationWith(s -> s.id = null)              },
                new Object[]{ "seq null",      stationWith(s -> s.seq = null)             },
                new Object[]{ "lat null",      stationWith(s -> s.lat = null)             },
                new Object[]{ "lat > 90",      stationWith(s -> s.lat = 91.0)             },
                new Object[]{ "lon < -180",    stationWith(s -> s.lon = -181.0)           },
                new Object[]{ "tmp null",      stationWith(s -> s.tempC = null)           },
                new Object[]{ "tmp > 60",      stationWith(s -> s.tempC = 61.0)           },
                new Object[]{ "hum null",      stationWith(s -> s.humPct = null)          },
                new Object[]{ "hum < 0",       stationWith(s -> s.humPct = -1.0)          },
                new Object[]{ "pres > 1100",   stationWith(s -> s.pressureHpa = 1101.0)   },
                new Object[]{ "pres < 800",    stationWith(s -> s.pressureHpa = 799.0)    },
                new Object[]{ "co2 > 5000",    stationWith(s -> s.co2Ppm = 5001.0)        },
                new Object[]{ "uv > 11",       stationWith(s -> s.uvIndex = 12.0)         },
                new Object[]{ "seq <= 0",      stationWith(s -> s.seq = 0L)               }
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dadosInvalidos")
    @DisplayName("Deve rejeitar dados inválidos com ValidationException")
    void deveRejeitarInvalidos(String descricao, StationData station) {
        assertThrows(ValidationException.class,
                () -> normalizer.normalize(station),
                "Deveria lançar ValidationException para: " + descricao);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static StationData validStation() {
        StationData s   = new StationData();
        s.id            = "estacao_01";
        s.sensorTs      = "2026-05-21T10:00:05.000Z";
        s.gatewayTs     = "2026-05-21T10:00:05.080Z";
        s.lat           = -5.7901;
        s.lon           = -35.2098;
        s.tempC         = 29.8;
        s.humPct        = 71.3;
        s.pressureHpa   = 1012.4;
        s.co2Ppm        = 418.2;
        s.uvIndex       = 6.1;
        s.rssiDbm       = -48;
        s.seq           = 87L;
        return s;
    }

    @FunctionalInterface
    interface StationModifier { void modify(StationData s); }

    private static StationData stationWith(StationModifier modifier) {
        StationData s = validStation();
        modifier.modify(s);
        return s;
    }
}
 