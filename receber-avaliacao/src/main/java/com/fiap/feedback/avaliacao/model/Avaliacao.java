package com.fiap.feedback.avaliacao.model;

import java.time.Instant;
import java.util.UUID;

public record Avaliacao(UUID id, String descricao, int nota, Urgencia urgencia, Instant dataEnvio) {
}
