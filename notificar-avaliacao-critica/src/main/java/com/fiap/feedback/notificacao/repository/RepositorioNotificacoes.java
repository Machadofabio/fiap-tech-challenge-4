package com.fiap.feedback.notificacao.repository;

import java.time.Instant;
import java.util.Optional;

public interface RepositorioNotificacoes {
    Optional<String> reservar(String eventId, Instant agora);
    void marcarEnviada(String eventId, String token);
    void liberar(String eventId, String token);
}
