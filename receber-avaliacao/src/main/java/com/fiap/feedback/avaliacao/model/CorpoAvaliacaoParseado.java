package com.fiap.feedback.avaliacao.model;

import java.math.BigDecimal;

public record CorpoAvaliacaoParseado(
        boolean objetoJson,
        boolean descricaoPresente,
        boolean descricaoTexto,
        String descricao,
        boolean notaPresente,
        boolean notaNumero,
        BigDecimal nota) {
}
