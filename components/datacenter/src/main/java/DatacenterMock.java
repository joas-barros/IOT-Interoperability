import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DatacenterMock {

    // A porta 8000 é a mesma configurada no seu gateway.properties
    private static final int PORT = 8000;

    public static void main(String[] args) throws IOException {
        // Cria o servidor escutando na porta 8000
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Cria os endpoints baseados no seu gateway.properties
        server.createContext("/ingestao", new IngestaoHandler());
        server.createContext("/gateway/metrics", new MetricsHandler());

        server.setExecutor(null); // Usa o executor padrão da JDK
        server.start();

        System.out.println("=================================================");
        System.out.println("📡 Datacenter Mock iniciado e escutando na porta " + PORT);
        System.out.println("Endpoints disponíveis:");
        System.out.println("  -> POST http://localhost:" + PORT + "/ingestao");
        System.out.println("  -> POST http://localhost:" + PORT + "/gateway/metrics");
        System.out.println("=================================================\n");
    }

    // ── Handler para receber os dados normalizados (Drone e Estação) ──
    static class IngestaoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));

                // Extrai os headers customizados que o seu HttpForwarder envia
                String gatewayId = exchange.getRequestHeaders().getFirst("X-Gateway-Id");
                String sourceType = exchange.getRequestHeaders().getFirst("X-Source-Type");

                // Lê o corpo da requisição (JSON)
                InputStream is = exchange.getRequestBody();
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                System.out.printf("[%s] 📥 NOVA LEITURA RECEBIDA (/ingestao)%n", timestamp);
                System.out.printf("   Gateway ID  : %s%n", gatewayId);
                System.out.printf("   Source Type : %s%n", sourceType);
                System.out.printf("   Payload JSON: %s%n", body);
                System.out.println("-------------------------------------------------");

                responder(exchange, 200, "OK");
            } else {
                responder(exchange, 405, "Method Not Allowed");
            }
        }
    }

    // ── Handler para receber as métricas do Gateway ──
    static class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));

                InputStream is = exchange.getRequestBody();
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                System.out.printf("[%s] 📊 MÉTRICAS RECEBIDAS (/gateway/metrics)%n", timestamp);
                System.out.printf("   Payload JSON: %s%n", body);
                System.out.println("-------------------------------------------------");

                responder(exchange, 200, "OK");
            } else {
                responder(exchange, 405, "Method Not Allowed");
            }
        }
    }

    // Método auxiliar para enviar a resposta HTTP de volta ao Gateway
    private static void responder(HttpExchange exchange, int statusCode, String responseText) throws IOException {
        byte[] responseBytes = responseText.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
}