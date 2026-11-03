package com.fiap.feedback.notificacao.model;

import java.time.Instant;

public record AvaliacaoCritica(String id, String descricao, int nota, String urgencia, Instant dataEnvio) {
    public AvaliacaoCritica {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("A avaliação precisa ter identificador.");
        if (descricao == null || descricao.isBlank()) throw new IllegalArgumentException("A avaliação precisa ter descrição.");
        if (nota < 0 || nota > 4) throw new IllegalArgumentException("Somente notas de 0 a 4 são críticas.");
        if (!"CRITICA".equals(urgencia)) throw new IllegalArgumentException("A urgência da avaliação deve ser CRITICA.");
        if (dataEnvio == null) throw new IllegalArgumentException("A avaliação precisa ter data de envio.");
    }
}
