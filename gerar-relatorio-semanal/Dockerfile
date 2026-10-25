FROM eclipse-temurin:25-jdk

WORKDIR /workspace
COPY . .
RUN chmod +x ./gradlew

EXPOSE 8080
CMD ["./gradlew", "--no-daemon", ":gerar-relatorio-semanal:quarkusDev"]
