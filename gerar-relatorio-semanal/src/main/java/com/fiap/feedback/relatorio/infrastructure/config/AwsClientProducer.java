package com.fiap.feedback.relatorio.infrastructure.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.net.URI;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.ses.SesClient;

@ApplicationScoped
public class AwsClientProducer {
    @ConfigProperty(name = "app.aws.region") String region;
    @ConfigProperty(name = "app.dynamodb.endpoint") String dynamoEndpoint;
    @ConfigProperty(name = "app.s3.endpoint") String s3Endpoint;
    @ConfigProperty(name = "app.ses.endpoint") String sesEndpoint;

    @Produces @Singleton
    DynamoDbClient dynamoDbClient() {
        var builder = DynamoDbClient.builder().region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (dynamoEndpoint != null && !dynamoEndpoint.isBlank()) builder.endpointOverride(URI.create(dynamoEndpoint));
        return builder.build();
    }

    void closeDynamoDb(@Disposes DynamoDbClient client) { client.close(); }

    @Produces @Singleton
    S3Client s3Client() {
        var builder = S3Client.builder().region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        if (s3Endpoint != null && !s3Endpoint.isBlank()) builder.endpointOverride(URI.create(s3Endpoint));
        return builder.build();
    }

    void closeS3(@Disposes S3Client client) { client.close(); }

    @Produces @Singleton
    SesClient sesClient() {
        var builder = SesClient.builder().region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (sesEndpoint != null && !sesEndpoint.isBlank()) builder.endpointOverride(URI.create(sesEndpoint));
        return builder.build();
    }

    void closeSes(@Disposes SesClient client) { client.close(); }
}
