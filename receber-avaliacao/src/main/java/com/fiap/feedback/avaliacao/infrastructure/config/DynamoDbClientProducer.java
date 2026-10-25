package com.fiap.feedback.avaliacao.infrastructure.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.net.URI;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

@ApplicationScoped
public class DynamoDbClientProducer {
    @ConfigProperty(name = "app.aws.region")
    String region;

    @ConfigProperty(name = "app.dynamodb.endpoint")
    String endpoint;

    @Produces
    @Singleton
    DynamoDbClient produzirCliente() {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    void fecharCliente(@Disposes DynamoDbClient client) {
        client.close();
    }
}
