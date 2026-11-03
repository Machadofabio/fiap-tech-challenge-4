package com.fiap.feedback.relatorio.model;

import java.time.Instant;
import java.util.Objects;

public record AvaliacaoRelatorio(String id, String descricao, int nota, String urgencia, Instant dataEnvio) {
    public AvaliacaoRelatorio {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Avaliação sem id.");
        Objects.requireNonNull(descricao, "Avaliação sem descrição.");
        if (nota < 0 || nota > 10) throw new IllegalArgumentException("Nota persistida fora do intervalo.");
        if (!"CRITICA".equals(urgencia) && !"NORMAL".equals(urgencia)) {
            throw new IllegalArgumentException("Urgência persistida inválida.");
        }
        Objects.requireNonNull(dataEnvio, "Avaliação sem dataEnvio.");
    }
}
