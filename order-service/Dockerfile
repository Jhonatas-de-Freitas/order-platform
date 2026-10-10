# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY order-service/pom.xml order-service/
COPY inventory-service/pom.xml inventory-service/
COPY payment-service/pom.xml payment-service/
COPY order-service/src order-service/src
# The three images build at the same time and share this cache, so it is locked.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw -B -ntp -q -pl order-service package -DskipTests
RUN cp order-service/target/order-service-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER app
EXPOSE 8081
HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=12 \
    CMD wget -q -O /dev/null "http://localhost:${SERVER_PORT:-8081}/actuator/health/readiness" || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
