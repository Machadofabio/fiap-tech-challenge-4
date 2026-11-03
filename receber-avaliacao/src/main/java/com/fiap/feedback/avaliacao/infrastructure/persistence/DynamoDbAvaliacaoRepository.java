package com.fiap.feedback.avaliacao.infrastructure.persistence;

import com.fiap.feedback.avaliacao.model.Avaliacao;
import com.fiap.feedback.avaliacao.model.Urgencia;
import com.fiap.feedback.avaliacao.repository.AvaliacaoRepository;
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
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@ApplicationScoped
public class DynamoDbAvaliacaoRepository implements AvaliacaoRepository {
    private final DynamoDbClient dynamoDb;
    private final String tabela;

    @Inject
    public DynamoDbAvaliacaoRepository(
            DynamoDbClient dynamoDb,
            @ConfigProperty(name = "app.dynamodb.table") String tabela) {
        this.dynamoDb = dynamoDb;
        this.tabela = tabela;
    }

    @Override
    public Optional<Avaliacao> buscarPorId(UUID id) {
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tabela)
                        .key(Map.of("id", AttributeValue.builder().s(id.toString()).build()))
                        .consistentRead(true)
                        .build())
                .item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(mapear(item));
    }

    @Override
    public Optional<Avaliacao> salvarSeAusente(Avaliacao avaliacao) {
        Map<String, AttributeValue> item = Map.of(
                "id", texto(avaliacao.id().toString()),
                "descricao", texto(avaliacao.descricao()),
                "nota", AttributeValue.builder().n(Integer.toString(avaliacao.nota())).build(),
                "urgencia", texto(avaliacao.urgencia().name()),
                "dataEnvio", texto(avaliacao.dataEnvio().toString()));

        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tabela)
                    .item(item)
                    .conditionExpression("attribute_not_exists(id)")
                    .build());
            return Optional.empty();
        } catch (ConditionalCheckFailedException conflito) {
            return buscarPorId(avaliacao.id()).or(() -> {
                throw new IllegalStateException("A chave existia, mas o registro original não foi encontrado.");
            });
        }
    }

    private AttributeValue texto(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private Avaliacao mapear(Map<String, AttributeValue> item) {
        return new Avaliacao(
                UUID.fromString(item.get("id").s()),
                item.get("descricao").s(),
                Integer.parseInt(item.get("nota").n()),
                Urgencia.valueOf(item.get("urgencia").s()),
                Instant.parse(item.get("dataEnvio").s()));
    }
}
