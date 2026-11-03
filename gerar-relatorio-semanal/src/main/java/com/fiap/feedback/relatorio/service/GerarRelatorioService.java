package com.fiap.feedback.relatorio.service;

import com.fiap.feedback.relatorio.model.AvaliacaoRelatorio;
import com.fiap.feedback.relatorio.model.PeriodoRelatorio;
import com.fiap.feedback.relatorio.model.ResumoRelatorio;
import com.fiap.feedback.relatorio.model.ResultadoRelatorio;
import com.fiap.feedback.relatorio.model.SolicitacaoRelatorio;
import com.fiap.feedback.relatorio.repository.AvaliacaoRelatorioRepository;
import com.fiap.feedback.relatorio.repository.GeracaoRelatorioRepository;
import com.fiap.feedback.relatorio.repository.GeracaoRelatorioRepository.ReservaGeracao;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

@ApplicationScoped
public class GerarRelatorioService {
    private static final Pattern GERACAO_ID_VALIDO = Pattern.compile("^[A-Za-z0-9._-]{1,80}$");
    private final AvaliacaoRelatorioRepository avaliacoes;
    private final GeracaoRelatorioRepository geracoes;
    private final ArmazenamentoRelatorio armazenamento;
    private final EmailResumoRelatorio email;
    private final PeriodoRelatorioFactory periodos;
    private final CsvRelatorioGenerator csvGenerator;

    @Inject
    public GerarRelatorioService(AvaliacaoRelatorioRepository avaliacoes,
            GeracaoRelatorioRepository geracoes,
            ArmazenamentoRelatorio armazenamento,
            EmailResumoRelatorio email,
            PeriodoRelatorioFactory periodos,
            CsvRelatorioGenerator csvGenerator) {
        this.avaliacoes = avaliacoes;
        this.geracoes = geracoes;
        this.armazenamento = armazenamento;
        this.email = email;
        this.periodos = periodos;
        this.csvGenerator = csvGenerator;
    }

    public ResultadoRelatorio gerar(SolicitacaoRelatorio solicitacao) {
        PeriodoRelatorio periodo = periodos.criar(solicitacao);
        String geracaoId = gerarId(solicitacao, periodo);
        Instant agora = Instant.now();
        ReservaGeracao reserva = geracoes.reservar(
                geracaoId, solicitacao.tipo().trim().toUpperCase(), periodo.descricao(), agora);
        if (reserva.jaConcluida()) {
            return new ResultadoRelatorio("JA_CONCLUIDO", geracaoId, periodo.descricao(), reserva.chaveS3(), null);
        }
        if (reserva.token() == null) {
            throw new IllegalStateException("Esta geração já está em processamento; tente novamente mais tarde.");
        }

        List<AvaliacaoRelatorio> noPeriodo = avaliacoes.listarTodas().stream()
                .filter(avaliacao -> periodo.contem(avaliacao.dataEnvio(), periodos.zone()))
                .sorted(Comparator.comparing(AvaliacaoRelatorio::dataEnvio).thenComparing(AvaliacaoRelatorio::id))
                .toList();
        ResumoRelatorio resumo = resumir(noPeriodo);
        String csv = csvGenerator.gerar(periodo, noPeriodo, resumo, periodos.zone());
        String chave = chaveS3(periodo, geracaoId);
        armazenamento.salvar(chave, csv);
        email.enviar(periodo, resumo, chave);
        geracoes.concluir(geracaoId, reserva.token(), chave, Instant.now());
        return new ResultadoRelatorio("CONCLUIDO", geracaoId, periodo.descricao(), chave, resumo);
    }

    private String gerarId(SolicitacaoRelatorio solicitacao, PeriodoRelatorio periodo) {
        String tipo = solicitacao.tipo().trim().toUpperCase();
        String id = solicitacao.geracaoId();
        if (id == null || id.isBlank()) {
            if ("MANUAL".equals(tipo)) {
                throw new IllegalArgumentException("geracaoId é obrigatório para distinguir uma solicitação manual.");
            }
            id = "semanal-" + periodo.dataInicio();
        }
        if (!GERACAO_ID_VALIDO.matcher(id).matches()) {
            throw new IllegalArgumentException("geracaoId deve ter de 1 a 80 caracteres: letras, números, ponto, hífen ou sublinhado.");
        }
        return id;
    }

    private ResumoRelatorio resumir(List<AvaliacaoRelatorio> avaliacoes) {
        Map<String, Integer> urgencias = new TreeMap<>();
        urgencias.put("CRITICA", 0);
        urgencias.put("NORMAL", 0);
        Map<String, Integer> dias = new TreeMap<>();
        int soma = 0;
        for (AvaliacaoRelatorio avaliacao : avaliacoes) {
            soma += avaliacao.nota();
            urgencias.compute(avaliacao.urgencia(), (key, value) -> value == null ? 1 : value + 1);
            String dia = avaliacao.dataEnvio().atZone(periodos.zone()).toLocalDate().toString();
            dias.compute(dia, (key, value) -> value == null ? 1 : value + 1);
        }
        BigDecimal media = avaliacoes.isEmpty()
                ? null
                : BigDecimal.valueOf(soma).divide(BigDecimal.valueOf(avaliacoes.size()), 2, RoundingMode.HALF_UP);
        return new ResumoRelatorio(avaliacoes.size(), media, urgencias, dias);
    }

    private String chaveS3(PeriodoRelatorio periodo, String geracaoId) {
        LocalDate mes = periodo.dataInicio() == null ? periodo.dataFim() : periodo.dataInicio();
        return "relatorios/%04d/%02d/%s-a-%s-%s.csv".formatted(
                mes.getYear(), mes.getMonthValue(), periodo.inicioChave(), periodo.fimChave(), geracaoId);
    }
}
