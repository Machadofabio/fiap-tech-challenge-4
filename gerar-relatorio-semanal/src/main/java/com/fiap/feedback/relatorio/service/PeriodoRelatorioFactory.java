package com.fiap.feedback.relatorio.service;

import com.fiap.feedback.relatorio.model.PeriodoRelatorio;
import com.fiap.feedback.relatorio.model.SolicitacaoRelatorio;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class PeriodoRelatorioFactory {
    private final Clock clock;
    private final ZoneId zone;

    @Inject
    public PeriodoRelatorioFactory(@ConfigProperty(name = "app.report.time-zone") String zone) {
        this(Clock.systemUTC(), ZoneId.of(zone));
    }

    PeriodoRelatorioFactory(Clock clock, ZoneId zone) {
        this.clock = clock;
        this.zone = zone;
    }

    public PeriodoRelatorio criar(SolicitacaoRelatorio solicitacao) {
        String tipo = solicitacao.tipo() == null ? "" : solicitacao.tipo().trim().toUpperCase();
        if ("SEMANAL".equals(tipo)) return semanal(solicitacao.dataReferencia());
        if (!"MANUAL".equals(tipo)) {
            throw new IllegalArgumentException("O campo 'tipo' deve ser SEMANAL ou MANUAL.");
        }
        return manual(solicitacao.dataInicio(), solicitacao.dataFim());
    }

    public ZoneId zone() {
        return zone;
    }

    private PeriodoRelatorio semanal(String referencia) {
        Instant instante = referencia == null || referencia.isBlank()
                ? clock.instant()
                : Instant.parse(referencia);
        LocalDate hoje = instante.atZone(zone).toLocalDate();
        LocalDate segundaAtual = hoje.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate inicio = segundaAtual.minusWeeks(1);
        LocalDate fim = segundaAtual.minusDays(1);
        return new PeriodoRelatorio(inicio, fim, inicio + " a " + fim);
    }

    private PeriodoRelatorio manual(String inicioInformado, String fimInformado) {
        if (vazio(inicioInformado) && vazio(fimInformado)) {
            throw new IllegalArgumentException("Informe dataInicio, dataFim ou ambas para o relatório manual.");
        }
        LocalDate hoje = LocalDate.now(clock.withZone(zone));
        LocalDate inicio = parsear(inicioInformado, "dataInicio");
        LocalDate fim = parsear(fimInformado, "dataFim");
        if (inicio != null && fim == null) fim = hoje;
        if (inicio != null && inicio.isAfter(hoje) && vazio(fimInformado)) {
            throw new IllegalArgumentException("dataInicio não pode ser posterior à data atual.");
        }
        if (inicio != null && fim != null && inicio.isAfter(fim)) {
            throw new IllegalArgumentException("dataInicio não pode ser posterior a dataFim.");
        }
        String descricao = inicio == null
                ? "Todo histórico até " + fim
                : inicio + " a " + fim;
        return new PeriodoRelatorio(inicio, fim, descricao);
    }

    private LocalDate parsear(String value, String campo) {
        if (vazio(value)) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException invalida) {
            throw new IllegalArgumentException(campo + " deve usar o formato YYYY-MM-DD.", invalida);
        }
    }

    private boolean vazio(String value) {
        return value == null || value.isBlank();
    }
}
