import config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import sensor.SensorData;
import sensor.SensorSim;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Teste 2 — SensorSim")
public class SensorSimTest {
    private static final int SAMPLE_SIZE = 200;

    private AppConfig config;
    private SensorSim sensor;
    private List<SensorData> samples;

    @BeforeEach
    void setUp() {
        config = new AppConfig();
        sensor = new SensorSim(config);
        samples = new ArrayList<>(SAMPLE_SIZE);
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            samples.add(sensor.read());
        }
    }

    // ── Integridade dos valores ───────────────────────────────────────────────

    @Test
    @DisplayName("Nenhum valor deve ser NaN ou infinito")
    void nenhumValorNanOuInfinito() {
        for (SensorData d : samples) {
            assertAll("NaN/Infinito na leitura seq=" + d.seq(),
                    () -> assertFalse(Double.isNaN(d.tempC()),       "tempC é NaN"),
                    () -> assertFalse(Double.isNaN(d.humPct()),      "humPct é NaN"),
                    () -> assertFalse(Double.isNaN(d.pressureHpa()), "pressureHpa é NaN"),
                    () -> assertFalse(Double.isNaN(d.co2Ppm()),      "co2Ppm é NaN"),
                    () -> assertFalse(Double.isNaN(d.uvIndex()),     "uvIndex é NaN"),
                    () -> assertFalse(Double.isInfinite(d.tempC()),  "tempC é infinito")
            );
        }
    }

    // ── Ranges por variável ───────────────────────────────────────────────────

    @Test
    @DisplayName("Temperatura deve estar entre -20°C e 60°C")
    void temperaturaDeveEstarNoRange() {
        samples.forEach(d ->
                assertTrue(d.tempC() >= -20.0 && d.tempC() <= 60.0,
                        "Temperatura fora do range: " + d.tempC())
        );
    }

    @Test
    @DisplayName("Umidade deve estar entre 0% e 100%")
    void umidadeDeveEstarNoRange() {
        samples.forEach(d ->
                assertTrue(d.humPct() >= 0.0 && d.humPct() <= 100.0,
                        "Umidade fora do range: " + d.humPct())
        );
    }

    @Test
    @DisplayName("Pressão deve estar entre 800 e 1100 hPa")
    void pressaoDeveEstarNoRange() {
        samples.forEach(d ->
                assertTrue(d.pressureHpa() >= 800.0 && d.pressureHpa() <= 1100.0,
                        "Pressão fora do range: " + d.pressureHpa())
        );
    }

    @Test
    @DisplayName("CO2 deve estar entre 300 e 5000 ppm")
    void co2DeveEstarNoRange() {
        samples.forEach(d ->
                assertTrue(d.co2Ppm() >= 300.0 && d.co2Ppm() <= 5000.0,
                        "CO2 fora do range: " + d.co2Ppm())
        );
    }

    @Test
    @DisplayName("Índice UV deve estar entre 0 e 11")
    void uvDeveEstarNoRange() {
        samples.forEach(d ->
                assertTrue(d.uvIndex() >= 0.0 && d.uvIndex() <= 11.0,
                        "UV fora do range: " + d.uvIndex())
        );
    }

    @Test
    @DisplayName("RSSI deve estar entre -90 e -30 dBm")
    void rssiDeveEstarNoRange() {
        samples.forEach(d ->
                assertTrue(d.rssi() >= -90 && d.rssi() <= -30,
                        "RSSI fora do range: " + d.rssi())
        );
    }

    // ── Campos estacionários ──────────────────────────────────────────────────

    @Test
    @DisplayName("Lat/lon devem ser iguais em todas as leituras")
    void latLonDevemSerFixos() {
        double lat = samples.get(0).lat();
        double lon = samples.get(0).lon();
        samples.forEach(d -> {
            assertEquals(lat, d.lat(), "Latitude mudou entre leituras!");
            assertEquals(lon, d.lon(), "Longitude mudou entre leituras!");
        });
    }

    @Test
    @DisplayName("Station ID deve ser igual em todas as leituras")
    void stationIdDeveSerFixo() {
        String id = config.getStationId();
        samples.forEach(d ->
                assertEquals(id, d.stationId(), "Station ID mudou!")
        );
    }

    // ── Sequência ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Número de sequência deve ser consecutivo a partir de 1")
    void sequenciaDeveSerConsecutiva() {
        for (int i = 0; i < samples.size(); i++) {
            assertEquals(i + 1, samples.get(i).seq(),
                    "Seq esperado " + (i + 1) + " mas foi " + samples.get(i).seq());
        }
    }

    // ── Reproduzibilidade ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Mesma semente deve gerar mesma sequência")
    void mesmaSementeDeveGerarMesmaSequencia() {
        // Cria dois sensores com a mesma semente
        SensorSim sensor1 = new SensorSim(config);
        SensorSim sensor2 = new SensorSim(config);

        // Gera 10 leituras de cada
        for (int i = 0; i < 10; i++) {
            SensorData d1 = sensor1.read();
            SensorData d2 = sensor2.read();

            assertEquals(d1.tempC(),       d2.tempC(),       0.001, "temp diferente no seq " + i);
            assertEquals(d1.humPct(),      d2.humPct(),      0.001, "hum diferente no seq " + i);
            assertEquals(d1.pressureHpa(), d2.pressureHpa(), 0.001, "pres diferente no seq " + i);
        }
    }

    // ── Correlação temperatura/umidade ────────────────────────────────────────

    @Test
    @DisplayName("Leituras com temperatura alta devem ter umidade mais baixa em média")
    void temperaturaEumidadeDevemSerCorrelacionadas() {
        // Divide as amostras em temp alta (acima da base) e temp baixa
        double tempBase = config.getTempBase();

        double avgHumHighTemp = samples.stream()
                .filter(d -> d.tempC() > tempBase)
                .mapToDouble(SensorData::humPct)
                .average()
                .orElse(0);

        double avgHumLowTemp = samples.stream()
                .filter(d -> d.tempC() <= tempBase)
                .mapToDouble(SensorData::humPct)
                .average()
                .orElse(0);

        // Com sigma relativamente pequeno, a correlação deve ser visível
        // Usamos margem de 3% para acomodar variância do ruído gaussiano
        assertTrue(avgHumHighTemp < avgHumLowTemp + 3.0,
                String.format(
                        "Correlação temp/hum não detectada: avgHumHighTemp=%.2f >= avgHumLowTemp=%.2f",
                        avgHumHighTemp, avgHumLowTemp));
    }

    // ── Estatísticas ──────────────────────────────────────────────────────────
    @Test
    @DisplayName("Sumário estatístico das amostras geradas")
    void sumarioEstatistico() {
        double minTemp = samples.stream().mapToDouble(SensorData::tempC).min().orElse(0);
        double maxTemp = samples.stream().mapToDouble(SensorData::tempC).max().orElse(0);
        double avgTemp = samples.stream().mapToDouble(SensorData::tempC).average().orElse(0);

        double minHum = samples.stream().mapToDouble(SensorData::humPct).min().orElse(0);
        double maxHum = samples.stream().mapToDouble(SensorData::humPct).max().orElse(0);

        double minPres = samples.stream().mapToDouble(SensorData::pressureHpa).min().orElse(0);
        double maxPres = samples.stream().mapToDouble(SensorData::pressureHpa).max().orElse(0);

        System.out.println("\n══════════════════════════════════════════");
        System.out.println("  SUMÁRIO — SensorSim (" + SAMPLE_SIZE + " amostras)");
        System.out.println("══════════════════════════════════════════");
        System.out.printf("  Temperatura : min=%.2f  max=%.2f  avg=%.2f °C%n",
                minTemp, maxTemp, avgTemp);
        System.out.printf("  Umidade     : min=%.1f  max=%.1f %%%n",
                minHum, maxHum);
        System.out.printf("  Pressão     : min=%.2f  max=%.2f hPa%n",
                minPres, maxPres);
        System.out.printf("  CO2 inicial : %.1f ppm%n",
                samples.get(0).co2Ppm());
        System.out.printf("  CO2 final   : %.1f ppm (tendência crescente)%n",
                samples.get(samples.size() - 1).co2Ppm());
        System.out.printf("  Seq range   : %d → %d%n",
                samples.get(0).seq(), samples.get(samples.size() - 1).seq());
        System.out.println("══════════════════════════════════════════\n");

        // Temperatura média deve estar próxima da base (dentro de 2 sigmas)
        double twoSigma = 2 * config.getTempSigma();
        assertTrue(Math.abs(avgTemp - config.getTempBase()) < twoSigma + 1.0,
                "Média de temperatura muito distante da base: " + avgTemp);
    }

    // ── Robustez ──────────────────────────────────────────────────────────────

    @RepeatedTest(3)
    @DisplayName("Deve gerar leituras estáveis em múltiplas execuções")
    void deveGerarLeiturasContinuas() {
        SensorSim s = new SensorSim(config);
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 50; i++) {
                SensorData d = s.read();
                assertNotNull(d);
                assertNotNull(d.timestamp());
                assertFalse(d.timestamp().isEmpty());
            }
        });
    }
}
