package com.fiap.feedback.notificacao.service;

import com.fiap.feedback.notificacao.model.EventoStream;
import com.fiap.feedback.notificacao.service.EnviadorAlerta;
import com.fiap.feedback.notificacao.repository.RepositorioNotificacoes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ProcessarNotificacoes {
    private static final Logger LOG = Logger.getLogger(ProcessarNotificacoes.class);
    private final RepositorioNotificacoes repositorio;
    private final EnviadorAlerta enviador;
    private final Clock clock;

    @Inject
    public ProcessarNotificacoes(RepositorioNotificacoes repositorio, EnviadorAlerta enviador) {
        this(repositorio, enviador, Clock.systemUTC());
    }

    ProcessarNotificacoes(RepositorioNotificacoes repositorio, EnviadorAlerta enviador, Clock clock) {
        this.repositorio = repositorio;
        this.enviador = enviador;
        this.clock = clock;
    }

    public String executar(List<EventoStream> eventos) {
        if (eventos == null) throw new IllegalArgumentException("O evento do DynamoDB Streams não pode ser nulo.");
        for (EventoStream evento : eventos) {
            if (evento == null) throw new IllegalArgumentException("O lote contém um registro nulo.");
            if (!evento.insercao() || !evento.critica()) continue;
            if (evento.eventId() == null || evento.eventId().isBlank()) {
                throw new IllegalArgumentException("O registro crítico do stream não tem eventID.");
            }

            Instant agora = clock.instant();
            Optional<String> reserva = repositorio.reservar(evento.eventId(), agora);
            if (reserva.isEmpty()) {
                LOG.infof("Alerta já processado ou reservado; eventID=%s", evento.eventId());
                continue;
            }

            String token = reserva.get();
            try {
                enviador.enviar(evento.paraAvaliacao());
                repositorio.marcarEnviada(evento.eventId(), token);
                LOG.infof("Alerta crítico enviado; eventID=%s", evento.eventId());
            } catch (RuntimeException falha) {
                try {
                    repositorio.liberar(evento.eventId(), token);
                } catch (RuntimeException falhaAoLiberar) {
                    falha.addSuppressed(falhaAoLiberar);
                }
                LOG.errorf("Falha ao processar alerta crítico; eventID=%s", evento.eventId());
                throw falha;
            }
        }
        return "Lote processado.";
    }
}
