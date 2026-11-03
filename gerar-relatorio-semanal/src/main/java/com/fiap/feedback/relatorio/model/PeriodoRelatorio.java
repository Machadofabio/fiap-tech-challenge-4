package com.fiap.feedback.relatorio.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public record PeriodoRelatorio(LocalDate dataInicio, LocalDate dataFim, String descricao) {
    public Instant inicioInclusivo(ZoneId zone) {
        return dataInicio == null ? Instant.MIN : dataInicio.atStartOfDay(zone).toInstant();
    }

    public Instant fimExclusivo(ZoneId zone) {
        return dataFim.plusDays(1).atStartOfDay(zone).toInstant();
    }

    public boolean contem(Instant instante, ZoneId zone) {
        return !instante.isBefore(inicioInclusivo(zone)) && instante.isBefore(fimExclusivo(zone));
    }

    public String inicioChave() {
        return dataInicio == null ? "historico" : dataInicio.toString();
    }

    public String fimChave() {
        return dataFim.toString();
    }
}
