package com.fiap.feedback.relatorio.model;

public record SolicitacaoRelatorio(
        String tipo,
        String dataInicio,
        String dataFim,
        String geracaoId,
        String dataReferencia) {
}
