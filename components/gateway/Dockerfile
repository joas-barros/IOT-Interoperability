# ============================================================
#  Dockerfile — Gateway IoT (multi-stage build)
#
#  Estágio 1 (build): Maven + JDK 25 → compila o fat JAR
#  Estágio 2 (run):   JRE 25 apenas  → executa o JAR
# ============================================================

# ── Estágio 1: Build ──────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /app

# Copia o pom.xml e baixa dependências primeiro (cache)
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copia o código e compila
COPY src ./src
RUN mvn package -DskipTests -q

# ── Estágio 2: Runtime ────────────────────────────────────────
FROM eclipse-temurin:25-jre

WORKDIR /app

# Copia só o fat JAR do estágio de build com o nome correto do seu projeto
COPY --from=build /app/target/gateway-1.0-SNAPSHOT.jar app.jar

# Cria diretório de logs
RUN mkdir -p /app/logs

# Porta CoAP (UDP)
EXPOSE 5683/udp

# Variáveis de ambiente com valores padrão
ENV MQTT_BROKER_HOST=mosquitto \
    MQTT_BROKER_PORT=1883      \
    DATACENTER_URL=http://localhost:8000

ENTRYPOINT ["java", "-jar", "app.jar"]