package com.fiap.feedback.notificacao.infrastructure.client;

import com.fiap.feedback.notificacao.model.AvaliacaoCritica;
import com.fiap.feedback.notificacao.service.EnviadorAlerta;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;

@ApplicationScoped
public class SesEnviadorAlerta implements EnviadorAlerta {
    private final SesClient ses;
    private final String remetente;
    private final String destinatario;

    @Inject
    public SesEnviadorAlerta(
            SesClient ses,
            @ConfigProperty(name = "app.alert.sender") String remetente,
            @ConfigProperty(name = "app.alert.recipient") String destinatario) {
        this.ses = ses;
        this.remetente = remetente;
        this.destinatario = destinatario;
    }

    @Override
    public void enviar(AvaliacaoCritica avaliacao) {
        String assunto = "Alerta de avaliação crítica";
        String corpo = "Uma avaliação crítica foi recebida.\n\n"
                + "Identificador: " + avaliacao.id() + "\n"
                + "Urgência: " + avaliacao.urgencia() + "\n"
                + "Nota: " + avaliacao.nota() + "\n"
                + "Data de envio: " + avaliacao.dataEnvio() + "\n\n"
                + "Descrição:\n" + avaliacao.descricao();

        ses.sendEmail(SendEmailRequest.builder()
                .source(remetente)
                .destination(Destination.builder().toAddresses(destinatario).build())
                .message(Message.builder()
                        .subject(Content.builder().charset("UTF-8").data(assunto).build())
                        .body(Body.builder().text(Content.builder().charset("UTF-8").data(corpo).build()).build())
                        .build())
                .build());
    }
}
