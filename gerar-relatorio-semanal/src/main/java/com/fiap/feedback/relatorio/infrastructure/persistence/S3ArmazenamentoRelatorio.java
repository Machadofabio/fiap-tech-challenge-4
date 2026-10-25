package com.fiap.feedback.relatorio.infrastructure.persistence;

import com.fiap.feedback.relatorio.service.ArmazenamentoRelatorio;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

@ApplicationScoped
public class S3ArmazenamentoRelatorio implements ArmazenamentoRelatorio {
    private final S3Client s3;
    private final String bucket;

    @Inject
    public S3ArmazenamentoRelatorio(S3Client s3, @ConfigProperty(name = "app.s3.bucket") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public String salvar(String chave, String csv) {
        byte[] conteudo = csv.getBytes(StandardCharsets.UTF_8);
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket).key(chave).contentType("text/csv; charset=utf-8")
                        .serverSideEncryption(ServerSideEncryption.AES256).build(),
                RequestBody.fromBytes(conteudo));
        return chave;
    }
}
