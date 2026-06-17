package coap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import config.AppConfig;
import model.DroneData;
import org.eclipse.californium.core.CoapExchange;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.coap.CoAP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pipeline.DataPipeline;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

public class GatewayCoapServer {

    private static final Logger log = LoggerFactory.getLogger(GatewayCoapServer.class);

    // Content-Format codes (RFC 7252 §12.3)
    private static final int CONTENT_FORMAT_JSON = 50;
    private static final int CONTENT_FORMAT_CBOR = 60;

    private final AppConfig config;
    private final DataPipeline pipeline;
    private final CoapServer server;

    // ObjectMappers — um por formato, criados uma vez e reutilizados
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper cborMapper = new ObjectMapper(new CBORFactory());

    // ── Métricas ──────────────────────────────────────────────────────────
    private final AtomicLong totalReceived   = new AtomicLong(0);
    private final AtomicLong receivedCbor    = new AtomicLong(0);
    private final AtomicLong receivedJson    = new AtomicLong(0);
    private final AtomicLong deserialErrors  = new AtomicLong(0);

    // ─────────────────────────────────────────────────────────────────────

    public GatewayCoapServer(AppConfig config, DataPipeline pipeline) {
        this.config   = config;
        this.pipeline = pipeline;

        // Cria o servidor Californium na porta UDP configurada
        this.server = new CoapServer(config.getCoapPort());

        // Registra o recurso do drone
        server.add(new DroneResource());

        log.info("[CoAP] Servidor criado | porta udp/{} | recurso: /{}",
                config.getCoapPort(), config.getCoapResourceDrone());
    }

    /**
     * Inicia o servidor CoAP. Deve ser chamado no {@code Main.start()}.
     */
    public void start() {
        server.start();
        log.info("[CoAP] Servidor iniciado. Aguardando conexões...");
    }

    /**
     * Encerra o servidor graciosamente.
     */
    public void stop() {
        server.stop();
        server.destroy();
        log.info("[CoAP] Servidor encerrado.");
        printMetrics();
    }

    private class DroneResource extends CoapResource {

        DroneResource() {
            super(config.getCoapResourceDrone().contains("/")
                    ? config.getCoapResourceDrone().split("/")[0]
                    : config.getCoapResourceDrone());

            // Registra sub-recurso se o path tiver mais de um segmento
            // Ex: "dados/drone" → recurso raiz "dados" com filho "drone"
            if (config.getCoapResourceDrone().contains("/")) {
                String[] parts = config.getCoapResourceDrone().split("/", 2);
                this.add(new LeafResource(parts[1]));
            }

            setObservable(false);
        }

        @Override
        public void handlePOST(CoapExchange exchange) {
            // Delegado ao leaf se houver sub-recurso
            // (este handlePOST só seria chamado se o path for simples, ex: "drone")
            processPost(exchange);
        }

        // Sub-recurso folha para paths compostos (ex: "dados/drone")
        private class LeafResource extends CoapResource {
            LeafResource(String name) {
                super(name);
                setObservable(false);
            }

            @Override
            public void handlePOST(CoapExchange exchange) {
                processPost(exchange);
            }
        }
    }

    /**
     * Processa uma requisição POST recebida do drone.
     * Detecta o Content-Format, desserializa, enfileira e responde.
     */
    private void processPost(CoapExchange exchange) {
        totalReceived.incrementAndGet();

        // Registra o timestamp de chegada IMEDIATAMENTE
        // Este é o gateway_ts que permite calcular latência CoAP
        String gatewayTs = Instant.now()
                .truncatedTo(ChronoUnit.MILLIS)
                .toString();

        byte[] payload       = exchange.getRequestPayload();
        int    contentFormat = exchange.getRequestOptions().getContentFormat();

        if (payload == null || payload.length == 0) {
            log.warn("[CoAP] Requisição recebida com payload vazio.");
            exchange.respond(CoAP.ResponseCode.BAD_REQUEST);
            return;
        }

        log.debug("[CoAP] Recebido | {} bytes | Content-Format={} | de={}",
                payload.length, contentFormat,
                exchange.getSourceAddress());

        try {
            DroneData drone;
            String fmt;

            if (contentFormat == CONTENT_FORMAT_CBOR) {
                // Payload binário CBOR — usa ObjectMapper com CBORFactory
                drone = cborMapper.readValue(payload, DroneData.class);
                fmt   = "CBOR";
                receivedCbor.incrementAndGet();
            }  else {
                // Payload texto JSON — fallback para qualquer outro Content-Format
                // ou quando o ESP32 está em modo debug (USE_CBOR=0)
                drone = jsonMapper.readValue(payload, DroneData.class);
                fmt   = "JSON";
                receivedJson.incrementAndGet();

                if (contentFormat != CONTENT_FORMAT_JSON) {
                    log.warn("[CoAP] Content-Format={} desconhecido. Tratando como JSON.",
                            contentFormat);
                }
            }

            // Adiciona metadados do gateway
            drone.gatewayTs     = gatewayTs;
            drone.contentFormat = fmt;

            log.debug("[CoAP] Desserializado | {} | seq={} | phase={} | lat={} | tmp={}",
                    fmt, drone.seq, drone.flightPhase, drone.lat, drone.tempC);

            // Enfileira no pipeline — retorna IMEDIATAMENTE
            // O ACK é enviado ANTES do dado ser normalizado e encaminhado
            pipeline.submitDrone(drone);

            // Responde com 2.04 CHANGED — ESP32 vai registrar o RTT aqui
            exchange.respond(CoAP.ResponseCode.CHANGED);
        } catch (Exception e) {
            deserialErrors.incrementAndGet();
            log.error("[CoAP] Erro ao desserializar payload ({} bytes, fmt={}): {}",
                    payload.length, contentFormat, e.getMessage());

            // Responde com 4.00 BAD REQUEST — ESP32 vai logar o erro
            exchange.respond(CoAP.ResponseCode.BAD_REQUEST);
        }
    }

    // ── Métricas ──────────────────────────────────────────────────────────

    public long getTotalReceived()  { return totalReceived.get(); }
    public long getReceivedCbor()   { return receivedCbor.get(); }
    public long getReceivedJson()   { return receivedJson.get(); }
    public long getDeserialErrors() { return deserialErrors.get(); }

    public void printMetrics() {
        log.info("╔══════════════════════════════════════════╗");
        log.info("║       Métricas CoAP Server               ║");
        log.info("╠══════════════════════════════════════════╣");
        log.info("║ Total recebidos : {}", totalReceived.get());
        log.info("║ CBOR            : {}", receivedCbor.get());
        log.info("║ JSON            : {}", receivedJson.get());
        log.info("║ Erros deserial. : {}", deserialErrors.get());
        log.info("╚══════════════════════════════════════════╝");
    }

}
