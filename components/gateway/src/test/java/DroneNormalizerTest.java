
import config.AppConfig;
import exceptions.ValidationException;
import model.DroneData;
import model.NormalizedData;
import normalizer.DroneNormalizer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste 2 — DroneNormalizer
 * Sem rede necessária.
 *
 * COMO EXECUTAR: mvn test -Dtest=DroneNormalizerTest
 */
@DisplayName("Teste 2 — DroneNormalizer")
class DroneNormalizerTest {

    private DroneNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new DroneNormalizer(new AppConfig());
    }

    // ── Dado válido ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Deve normalizar DroneData CBOR válido corretamente")
    void deveNormalizarDroneCbor() throws Exception {
        DroneData d = validDrone();
        d.contentFormat = "CBOR";

        NormalizedData nd = normalizer.normalize(d);

        assertAll("Normalização CBOR",
                () -> assertEquals("DRONE",   nd.sourceType),
                () -> assertEquals("CoAP",    nd.sourceProtocol),
                () -> assertEquals("CBOR",    nd.payloadFormat),
                () -> assertEquals(d.id,      nd.sourceId),
                () -> assertEquals(d.lat,     nd.lat),
                () -> assertEquals(d.lon,     nd.lon),
                () -> assertEquals(d.altM,    nd.altM),
                () -> assertEquals(d.tempC,   nd.tempC),
                () -> assertEquals(d.humPct,  nd.humPct),
                () -> assertEquals(d.seq,     nd.seq)
        );
    }

    @Test
    @DisplayName("Campos exclusivos da estação devem ser null no drone normalizado")
    void camposEstacaoDevemSerNull() throws Exception {
        NormalizedData nd = normalizer.normalize(validDrone());

        assertAll("Campos exclusivos da estação",
                () -> assertNull(nd.pressureHpa, "pressureHpa deve ser null para drone"),
                () -> assertNull(nd.co2Ppm,      "co2Ppm deve ser null para drone"),
                () -> assertNull(nd.uvIndex,     "uvIndex deve ser null para drone")
        );
    }

    @Test
    @DisplayName("GatewayTs deve ser preservado do DroneData")
    void gatewayTsDeveSerPreservado() throws Exception {
        DroneData d = validDrone();
        d.gatewayTs = "2026-05-21T10:00:00.123Z";

        NormalizedData nd = normalizer.normalize(d);
        assertEquals("2026-05-21T10:00:00.123Z", nd.gatewayTs);
    }

    // ── Dados inválidos ───────────────────────────────────────────────────

    static Stream<Object[]> dadosInvalidos() {
        return Stream.of(
                new Object[]{ "id null",        droneWith(d -> d.id = null)            },
                new Object[]{ "seq null",       droneWith(d -> d.seq = null)           },
                new Object[]{ "lat null",       droneWith(d -> d.lat = null)           },
                new Object[]{ "lat > 90",       droneWith(d -> d.lat = 91.0)           },
                new Object[]{ "lat < -90",      droneWith(d -> d.lat = -91.0)          },
                new Object[]{ "lon > 180",      droneWith(d -> d.lon = 181.0)          },
                new Object[]{ "tmp null",       droneWith(d -> d.tempC = null)         },
                new Object[]{ "tmp > 60",       droneWith(d -> d.tempC = 61.0)         },
                new Object[]{ "tmp < -20",      droneWith(d -> d.tempC = -21.0)        },
                new Object[]{ "hum null",       droneWith(d -> d.humPct = null)        },
                new Object[]{ "hum > 100",      droneWith(d -> d.humPct = 101.0)       },
                new Object[]{ "bat > 100",      droneWith(d -> d.batteryPct = 101)     },
                new Object[]{ "seq <= 0",       droneWith(d -> d.seq = 0L)             },
                new Object[]{ "alt > 500",      droneWith(d -> d.altM = 501.0)         }
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dadosInvalidos")
    @DisplayName("Deve rejeitar dados inválidos com ValidationException")
    void deveRejeitarInvalidos(String descricao, DroneData drone) {
        assertThrows(ValidationException.class,
                () -> normalizer.normalize(drone),
                "Deveria lançar ValidationException para: " + descricao);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static DroneData validDrone() {
        DroneData d      = new DroneData();
        d.id             = "drone_01";
        d.sensorTs       = "2026-05-21T10:00:00.000Z";
        d.gatewayTs      = "2026-05-21T10:00:00.150Z";
        d.contentFormat  = "CBOR";
        d.flightPhase    = "MISSION";
        d.waypointIndex  = 1;
        d.lat            = -5.7923;
        d.lon            = -35.2128;
        d.altM           = 80.0;
        d.headingDeg     = 127.0;
        d.velocityMs     = 11.2;
        d.tempC          = 29.5;
        d.humPct         = 68.0;
        d.batteryPct     = 74;
        d.batteryOk      = true;
        d.rssiDbm        = -61;
        d.seq            = 42L;
        return d;
    }

    @FunctionalInterface
    interface DroneModifier { void modify(DroneData d); }

    private static DroneData droneWith(DroneModifier modifier) {
        DroneData d = validDrone();
        modifier.modify(d);
        return d;
    }
}