package com.fiap.feedback.relatorio.infrastructure.persistence;

import com.fiap.feedback.relatorio.model.AvaliacaoRelatorio;
import com.fiap.feedback.relatorio.repository.AvaliacaoRelatorioRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

@ApplicationScoped
public class DynamoDbAvaliacaoRelatorioRepository implements AvaliacaoRelatorioRepository {
    private final DynamoDbClient dynamoDb;
    private final String table;

    @Inject
    public DynamoDbAvaliacaoRelatorioRepository(
            DynamoDbClient dynamoDb,
            @ConfigProperty(name = "app.dynamodb.evaluations-table") String table) {
        this.dynamoDb = dynamoDb;
        this.table = table;
    }

    @Override
    public List<AvaliacaoRelatorio> listarTodas() {
        List<AvaliacaoRelatorio> result = new ArrayList<>();
        ScanRequest request = ScanRequest.builder().tableName(table).consistentRead(true).build();
        for (ScanResponse page : dynamoDb.scanPaginator(request)) {
            for (Map<String, AttributeValue> item : page.items()) result.add(mapear(item));
        }
        return List.copyOf(result);
    }

    private AvaliacaoRelatorio mapear(Map<String, AttributeValue> item) {
        return new AvaliacaoRelatorio(
                texto(item, "id"), texto(item, "descricao"), Integer.parseInt(texto(item, "nota")),
                texto(item, "urgencia"), Instant.parse(texto(item, "dataEnvio")));
    }

    private String texto(Map<String, AttributeValue> item, String field) {
        AttributeValue value = item.get(field);
        if (value == null) throw new IllegalStateException("Registro da tabela avaliacoes sem o campo " + field + ".");
        return value.s() != null ? value.s() : value.n();
    }
}
