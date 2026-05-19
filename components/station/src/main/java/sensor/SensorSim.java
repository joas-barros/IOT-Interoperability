package sensor;

import config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Random;

public class SensorSim {

    private static final Logger log = LoggerFactory.getLogger(SensorSim.class);

    private final AppConfig config;
    private final Random    random;
    private long            seqCounter = 0;

    // Simula RSSI Wi-Fi estacionário (-40 a -75 dBm é faixa típica)
    private static final int RSSI_BASE  = -52;
    private static final int RSSI_RANGE = 8;

    public SensorSim(AppConfig config) {
        this.config = config;
        long seed = config.getRandomSeed();
        this.random = (seed == 0) ? new Random() : new Random(seed);

        log.info("[Sensor] Inicializado | seed={} | temp_base={}°C | hum_base={}%",
                seed == 0 ? "aleatória" : seed,
                config.getTempBase(),
                config.getHumBase());
    }

    public SensorData read() {
        seqCounter++;

        String timestamp = Instant.now()
                .truncatedTo(ChronoUnit.MILLIS)
                .toString();

        double tempC       = generateTemperature();
        double humPct      = generateHumidity(tempC);
        double pressureHpa = generatePressure();
        double co2Ppm      = generateCo2();
        double uvIndex     = generateUvIndex();
        int    rssi        = generateRssi();

        SensorData data = new SensorData(
                config.getStationId(),
                timestamp,
                config.getStationLat(),
                config.getStationLon(),
                round2(tempC),
                round1(humPct),
                round2(pressureHpa),
                round1(co2Ppm),
                round1(uvIndex),
                rssi,
                seqCounter
        );

        log.debug("[Sensor] seq={} | tmp={} | hum={} | pres={} | co2={} | uv={} | rssi={}",
                data.seq(), data.tempC(), data.humPct(),
                data.pressureHpa(), data.co2Ppm(), data.uvIndex(), data.rssi());

        return data;
    }

    // ── Geradores por variável ────────────────────────────────────────────────

    private double generateTemperature() {
        return config.getTempBase() + random.nextGaussian() * config.getTempSigma();
    }

    private double generateHumidity(double currentTemp) {
        double tempDeviation = currentTemp - config.getTempBase();
        double hum = config.getHumBase()
                - (tempDeviation * 0.5)
                + random.nextGaussian() * config.getHumSigma();
        return Math.max(0.0, Math.min(100.0, hum));
    }

    private double generatePressure() {
        double hourOfDay = LocalTime.now(ZoneOffset.UTC).getHour()
                + LocalTime.now(ZoneOffset.UTC).getMinute() / 60.0;

        // Variação diurna: ±0.8 hPa com pico às 10h e vale às 22h (aproximado)
        double diurnal = 0.8 * Math.sin(2 * Math.PI * (hourOfDay - 4.0) / 12.0);

        return config.getPressureBase()
                + diurnal
                + random.nextGaussian() * config.getPressureSigma();
    }

    private double generateCo2() {
        double trend = seqCounter * 0.002;
        return config.getCo2Base()
                + trend
                + random.nextGaussian() * config.getCo2Sigma();
    }

    private double generateUvIndex() {
        int hour = LocalTime.now(ZoneOffset.UTC).getHour();

        // Fora do período de luz solar: UV = 0
        if (hour < 6 || hour >= 18) {
            return 0.0;
        }

        // Curva senoidal: pico ao meio-dia (hora 12)
        // sin(π * (hora - 6) / 12) vai de 0 (às 6h) a 1 (às 12h) a 0 (às 18h)
        double solarFactor = Math.sin(Math.PI * (hour - 6.0) / 12.0);
        double uv = config.getUvBase() * solarFactor
                + random.nextGaussian() * config.getUvSigma();

        return Math.max(0.0, Math.min(11.0, uv));
    }

    private int generateRssi() {
        int variation = (int)(random.nextGaussian() * RSSI_RANGE);
        return Math.max(-90, Math.min(-30, RSSI_BASE + variation));
    }

    // ── Helpers de arredondamento ─────────────────────────────────────────────

    /** Arredonda para 2 casas decimais. */
    private double round2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }

    /** Arredonda para 1 casa decimal. */
    private double round1(double val) {
        return Math.round(val * 10.0) / 10.0;
    }

    /** Retorna o número de leituras geradas nesta sessão. */
    public long getSeqCounter() {
        return seqCounter;
    }
}
