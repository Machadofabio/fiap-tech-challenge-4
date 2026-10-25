package com.fiap.feedback.relatorio.infrastructure.client;

import com.fiap.feedback.relatorio.model.PeriodoRelatorio;
import com.fiap.feedback.relatorio.model.ResumoRelatorio;
import com.fiap.feedback.relatorio.service.EmailResumoRelatorio;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;

@ApplicationScoped
public class SesEmailResumoRelatorio implements EmailResumoRelatorio {
    private final SesClient ses;
    private final String remetente;
    private final String destinatario;

    @Inject
    public SesEmailResumoRelatorio(SesClient ses,
            @ConfigProperty(name = "app.email.sender") String remetente,
            @ConfigProperty(name = "app.email.recipient") String destinatario) {
        this.ses = ses;
        this.remetente = remetente;
        this.destinatario = destinatario;
    }

    @Override
    public void enviar(PeriodoRelatorio periodo, ResumoRelatorio resumo, String chaveS3) {
        String media = resumo.media() == null ? "não se aplica" : resumo.media().toPlainString();
        String porUrgencia = resumo.quantidadePorUrgencia().entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue()).collect(Collectors.joining("\n"));
        String porDia = resumo.quantidadePorDia().isEmpty()
                ? "Nenhuma avaliação no período"
                : resumo.quantidadePorDia().entrySet().stream()
                        .map(entry -> entry.getKey() + ": " + entry.getValue()).collect(Collectors.joining("\n"));
        String vazio = resumo.quantidade() == 0 ? "\nNenhuma avaliação no período.\n" : "";
        String corpo = "Relatório de avaliações\n\nPeríodo: " + periodo.descricao()
                + "\nQuantidade: " + resumo.quantidade()
                + "\nMédia das notas: " + media + "\n" + vazio
                + "\nQuantidade por urgência:\n" + porUrgencia
                + "\n\nQuantidade por dia (America/Sao_Paulo):\n" + porDia
                + "\n\nArquivo CSV salvo no bucket privado. Chave do objeto: " + chaveS3;
        ses.sendEmail(SendEmailRequest.builder()
                .source(remetente)
                .destination(Destination.builder().toAddresses(destinatario).build())
                .message(Message.builder()
                        .subject(Content.builder().charset("UTF-8").data("Relatório semanal de avaliações").build())
                        .body(Body.builder().text(Content.builder().charset("UTF-8").data(corpo).build()).build())
                        .build())
                .build());
    }
}
