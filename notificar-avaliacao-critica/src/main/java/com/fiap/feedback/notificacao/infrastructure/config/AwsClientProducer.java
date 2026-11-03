package com.fiap.feedback.notificacao.infrastructure.config;

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
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.SesClientBuilder;

@ApplicationScoped
public class AwsClientProducer {
    @ConfigProperty(name = "app.aws.region")
    String region;

    @ConfigProperty(name = "app.dynamodb.endpoint")
    String dynamoEndpoint;

    @ConfigProperty(name = "app.ses.endpoint")
    String sesEndpoint;

    @Produces
    @Singleton
    DynamoDbClient produzirDynamoDbClient() {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (dynamoEndpoint != null && !dynamoEndpoint.isBlank()) builder.endpointOverride(URI.create(dynamoEndpoint));
        return builder.build();
    }

    void fecharDynamoDbClient(@Disposes DynamoDbClient client) {
        client.close();
    }

    @Produces
    @Singleton
    SesClient produzirSesClient() {
        SesClientBuilder builder = SesClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (sesEndpoint != null && !sesEndpoint.isBlank()) builder.endpointOverride(URI.create(sesEndpoint));
        return builder.build();
    }

    void fecharSesClient(@Disposes SesClient client) {
        client.close();
    }
}
