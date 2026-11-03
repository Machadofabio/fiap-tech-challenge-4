package com.fiap.feedback.relatorio.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record ResumoRelatorio(
        int quantidade,
        BigDecimal media,
        Map<String, Integer> quantidadePorUrgencia,
        Map<String, Integer> quantidadePorDia) {
    public ResumoRelatorio {
        quantidadePorUrgencia = Collections.unmodifiableMap(new TreeMap<>(quantidadePorUrgencia));
        quantidadePorDia = Collections.unmodifiableMap(new TreeMap<>(quantidadePorDia));
    }
}
