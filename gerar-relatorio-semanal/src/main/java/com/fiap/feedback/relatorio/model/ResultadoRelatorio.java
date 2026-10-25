package com.fiap.feedback.relatorio.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record ResultadoRelatorio(
        String status,
        String geracaoId,
        String periodo,
        String chaveS3,
        ResumoRelatorio resumo) {
    public Map<String, Object> comoMapa() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("geracaoId", geracaoId);
        result.put("periodo", periodo);
        result.put("chaveS3", chaveS3);
        if (resumo != null) {
            result.put("quantidade", resumo.quantidade());
            result.put("media", resumo.media() == null ? "não se aplica" : resumo.media().toPlainString());
            result.put("quantidadePorUrgencia", resumo.quantidadePorUrgencia());
            result.put("quantidadePorDia", resumo.quantidadePorDia());
        }
        return result;
    }
}
