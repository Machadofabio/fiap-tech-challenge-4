FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace
COPY . .
RUN chmod +x ./gradlew \
    && ./gradlew --no-daemon \
      :receber-avaliacao:quarkusBuild \
      :notificar-avaliacao-critica:quarkusBuild \
      :gerar-relatorio-semanal:quarkusBuild

FROM alpine:3.22
COPY --from=build /workspace/build/receber-avaliacao/function.zip /artifacts/receber-avaliacao.zip
COPY --from=build /workspace/build/notificar-avaliacao-critica/function.zip /artifacts/notificar-avaliacao-critica.zip
COPY --from=build /workspace/build/gerar-relatorio-semanal/function.zip /artifacts/gerar-relatorio-semanal.zip
CMD ["sh", "-c", "cp /artifacts/*.zip /lambda-artifacts/ && echo 'Pacotes das Lambdas atualizados.'"]
