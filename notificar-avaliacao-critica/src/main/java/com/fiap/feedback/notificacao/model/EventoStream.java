package com.fiap.feedback.notificacao.model;

import java.time.Instant;

public record EventoStream(String eventId, String eventName, String id, String descricao,
        String nota, String urgencia, String dataEnvio) {
    public boolean insercao() {
        return "INSERT".equals(eventName);
    }

    public boolean critica() {
        try {
            return nota != null && Integer.parseInt(nota) >= 0 && Integer.parseInt(nota) <= 4
                    && "CRITICA".equals(urgencia);
        } catch (NumberFormatException invalida) {
            throw new IllegalArgumentException("A nota do evento não é válida.", invalida);
        }
    }

    public com.fiap.feedback.notificacao.model.AvaliacaoCritica paraAvaliacao() {
        try {
            return new com.fiap.feedback.notificacao.model.AvaliacaoCritica(
                    id, descricao, Integer.parseInt(nota), urgencia, Instant.parse(dataEnvio));
        } catch (RuntimeException invalida) {
            throw new IllegalArgumentException("O evento de inserção não contém uma avaliação válida.", invalida);
        }
    }
}
