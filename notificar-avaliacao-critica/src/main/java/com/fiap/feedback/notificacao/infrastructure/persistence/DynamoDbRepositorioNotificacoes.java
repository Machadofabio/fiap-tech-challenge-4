package com.fiap.feedback.notificacao.infrastructure.persistence;

import com.fiap.feedback.notificacao.repository.RepositorioNotificacoes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

@ApplicationScoped
public class DynamoDbRepositorioNotificacoes implements RepositorioNotificacoes {
    private final DynamoDbClient dynamoDb;
    private final String tabela;
    private final long leaseSeconds;

    @Inject
    public DynamoDbRepositorioNotificacoes(
            DynamoDbClient dynamoDb,
            @ConfigProperty(name = "app.dynamodb.notification-table") String tabela,
            @ConfigProperty(name = "app.alert.processing-lease-seconds") long leaseSeconds) {
        this.dynamoDb = dynamoDb;
        this.tabela = tabela;
        this.leaseSeconds = leaseSeconds;
    }

    @Override
    public Optional<String> reservar(String eventId, Instant agora) {
        String token = UUID.randomUUID().toString();
        long leaseUntil = agora.plusSeconds(leaseSeconds).getEpochSecond();
        Map<String, AttributeValue> item = Map.of(
                "eventId", texto(eventId),
                "status", texto("PROCESSING"),
                "lockToken", texto(token),
                "leaseUntil", numero(leaseUntil));
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tabela)
                    .item(item)
                    .conditionExpression("attribute_not_exists(eventId)")
                    .build());
            return Optional.of(token);
        } catch (ConditionalCheckFailedException existente) {
            return renovarSeExpirada(eventId, token, agora.getEpochSecond(), leaseUntil);
        }
    }

    private Optional<String> renovarSeExpirada(String eventId, String token, long agora, long leaseUntil) {
        try {
            dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tabela)
                    .key(Map.of("eventId", texto(eventId)))
                    .updateExpression("SET #status = :processing, lockToken = :token, leaseUntil = :lease")
                    .conditionExpression("#status = :processing AND leaseUntil < :now")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":processing", texto("PROCESSING"),
                            ":token", texto(token),
                            ":lease", numero(leaseUntil),
                            ":now", numero(agora)))
                    .build());
            return Optional.of(token);
        } catch (ConditionalCheckFailedException ocupadaOuEnviada) {
            return Optional.empty();
        }
    }

    @Override
    public void marcarEnviada(String eventId, String token) {
        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(tabela)
                .key(Map.of("eventId", texto(eventId)))
                .updateExpression("SET #status = :sent, sentAt = :sentAt REMOVE leaseUntil")
                .conditionExpression("#status = :processing AND lockToken = :token")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(
                        ":sent", texto("SENT"),
                        ":processing", texto("PROCESSING"),
                        ":token", texto(token),
                        ":sentAt", texto(Instant.now().toString())))
                .build());
    }

    @Override
    public void liberar(String eventId, String token) {
        dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(tabela)
                .key(Map.of("eventId", texto(eventId)))
                .conditionExpression("#status = :processing AND lockToken = :token")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(
                        ":processing", texto("PROCESSING"),
                        ":token", texto(token)))
                .build());
    }

    private AttributeValue texto(String valor) {
        return AttributeValue.builder().s(valor).build();
    }

    private AttributeValue numero(long valor) {
        return AttributeValue.builder().n(Long.toString(valor)).build();
    }
}
