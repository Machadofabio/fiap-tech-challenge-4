package com.fiap.feedback.avaliacao.controller;

import com.fiap.feedback.avaliacao.model.Avaliacao;

public record RespostaAvaliacao(
        String resultado,
        String id,
        String descricao,
        int nota,
        String urgencia,
        String dataEnvio) {
    public static RespostaAvaliacao de(Avaliacao avaliacao, boolean criada) {
        return new RespostaAvaliacao(
                criada ? "criada" : "ja_recebida",
                avaliacao.id().toString(),
                avaliacao.descricao(),
                avaliacao.nota(),
                avaliacao.urgencia().name(),
                avaliacao.dataEnvio().toString());
    }
}
