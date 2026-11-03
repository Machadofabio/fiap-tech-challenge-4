package com.fiap.feedback.relatorio.infrastructure.persistence;

import com.fiap.feedback.relatorio.repository.GeracaoRelatorioRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

@ApplicationScoped
public class DynamoDbGeracaoRelatorioRepository implements GeracaoRelatorioRepository {
    private final DynamoDbClient dynamoDb;
    private final String table;
    private final long leaseSeconds;

    @Inject
    public DynamoDbGeracaoRelatorioRepository(DynamoDbClient dynamoDb,
            @ConfigProperty(name = "app.dynamodb.generations-table") String table,
            @ConfigProperty(name = "app.report.processing-lease-seconds") long leaseSeconds) {
        this.dynamoDb = dynamoDb;
        this.table = table;
        this.leaseSeconds = leaseSeconds;
    }

    @Override
    public ReservaGeracao reservar(String geracaoId, String origem, String periodo, Instant agora) {
        String token = UUID.randomUUID().toString();
        long leaseUntil = agora.plusSeconds(leaseSeconds).getEpochSecond();
        Map<String, AttributeValue> existente = buscar(geracaoId);
        if (existente != null) {
            validarMesmoPedido(existente, origem, periodo);
            if ("SENT".equals(valor(existente, "status"))) {
                return ReservaGeracao.concluida(valor(existente, "s3Key"));
            }
            long leaseAtual = Long.parseLong(valor(existente, "leaseUntil"));
            if (leaseAtual >= agora.getEpochSecond()) return new ReservaGeracao(null, false, null);
            try {
                dynamoDb.updateItem(UpdateItemRequest.builder()
                        .tableName(table)
                        .key(chave(geracaoId))
                        .updateExpression("SET lockToken = :token, leaseUntil = :lease")
                        .conditionExpression("#status = :processing AND leaseUntil = :oldLease AND leaseUntil < :now")
                        .expressionAttributeNames(Map.of("#status", "status"))
                        .expressionAttributeValues(Map.of(
                                ":processing", texto("PROCESSING"), ":token", texto(token),
                                ":lease", numero(leaseUntil), ":oldLease", numero(leaseAtual),
                                ":now", numero(agora.getEpochSecond())))
                        .build());
                return ReservaGeracao.adquirida(token);
            } catch (ConditionalCheckFailedException disputa) {
                return resultadoCorrida(geracaoId, origem, periodo);
            }
        }
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(table)
                    .item(Map.of(
                            "generationId", texto(geracaoId),
                            "status", texto("PROCESSING"),
                            "lockToken", texto(token),
                            "leaseUntil", numero(leaseUntil),
                            "origin", texto(origem),
                            "period", texto(periodo),
                            "createdAt", texto(agora.toString())))
                    .conditionExpression("attribute_not_exists(generationId)")
                    .build());
            return ReservaGeracao.adquirida(token);
        } catch (ConditionalCheckFailedException disputa) {
            return resultadoCorrida(geracaoId, origem, periodo);
        }
    }

    @Override
    public void concluir(String geracaoId, String token, String chaveS3, Instant enviadaEm) {
        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(table)
                .key(chave(geracaoId))
                .updateExpression("SET #status = :sent, s3Key = :key, sentAt = :sentAt REMOVE leaseUntil, lockToken")
                .conditionExpression("#status = :processing AND lockToken = :token")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(
                        ":sent", texto("SENT"), ":processing", texto("PROCESSING"),
                        ":token", texto(token), ":key", texto(chaveS3), ":sentAt", texto(enviadaEm.toString())))
                .build());
    }

    private ReservaGeracao resultadoCorrida(String geracaoId, String origem, String periodo) {
        Map<String, AttributeValue> atual = buscar(geracaoId);
        if (atual != null) validarMesmoPedido(atual, origem, periodo);
        if (atual != null && "SENT".equals(valor(atual, "status"))) {
            return ReservaGeracao.concluida(valor(atual, "s3Key"));
        }
        return new ReservaGeracao(null, false, null);
    }

    private void validarMesmoPedido(Map<String, AttributeValue> item, String origem, String periodo) {
        if (!Objects.equals(valor(item, "origin"), origem) || !Objects.equals(valor(item, "period"), periodo)) {
            throw new IllegalArgumentException("geracaoId já foi usado para outra origem ou período.");
        }
    }

    private Map<String, AttributeValue> buscar(String geracaoId) {
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(table).key(chave(geracaoId)).consistentRead(true).build())
                .item();
        return item == null || item.isEmpty() ? null : item;
    }

    private Map<String, AttributeValue> chave(String geracaoId) {
        return Map.of("generationId", texto(geracaoId));
    }

    private String valor(Map<String, AttributeValue> item, String field) {
        return Optional.ofNullable(item.get(field)).map(AttributeValue::s).orElseGet(() -> {
            AttributeValue number = item.get(field);
            return number == null ? null : number.n();
        });
    }

    private AttributeValue texto(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private AttributeValue numero(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
