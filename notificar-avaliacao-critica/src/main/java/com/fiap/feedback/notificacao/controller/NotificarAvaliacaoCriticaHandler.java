package com.fiap.feedback.notificacao.controller;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord;
import com.fiap.feedback.notificacao.model.EventoStream;
import com.fiap.feedback.notificacao.service.ProcessarNotificacoes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

@ApplicationScoped
@Named("stream")
public class NotificarAvaliacaoCriticaHandler implements RequestHandler<DynamodbEvent, String> {
    private static final Logger LOG = Logger.getLogger(NotificarAvaliacaoCriticaHandler.class);
    private final ProcessarNotificacoes processador;

    @Inject
    public NotificarAvaliacaoCriticaHandler(ProcessarNotificacoes processador) {
        this.processador = processador;
    }

    @Override
    public String handleRequest(DynamodbEvent event, Context context) {
        if (event == null || event.getRecords() == null) {
            throw new IllegalArgumentException("Evento DynamoDB Streams inválido.");
        }
        List<EventoStream> registros = event.getRecords().stream()
                .map(registro -> new EventoStream(
                        registro.getEventID(),
                        registro.getEventName(),
                        valor(registro.getDynamodb(), "id", AttributeValue::getS),
                        valor(registro.getDynamodb(), "descricao", AttributeValue::getS),
                        valor(registro.getDynamodb(), "nota", AttributeValue::getN),
                        valor(registro.getDynamodb(), "urgencia", AttributeValue::getS),
                        valor(registro.getDynamodb(), "dataEnvio", AttributeValue::getS)))
                .toList();
        LOG.infof("Recebido lote do DynamoDB Streams; quantidade=%d", registros.size());
        return processador.executar(registros);
    }

    private String valor(StreamRecord stream, String nome, Function<AttributeValue, String> extrator) {
        if (stream == null || stream.getNewImage() == null) return null;
        Map<String, AttributeValue> imagem = stream.getNewImage().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()));
        AttributeValue atributo = imagem.get(nome);
        return atributo == null ? null : extrator.apply(atributo);
    }
}
