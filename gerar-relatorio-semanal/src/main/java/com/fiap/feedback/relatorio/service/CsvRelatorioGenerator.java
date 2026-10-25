package com.fiap.feedback.relatorio.service;

import com.fiap.feedback.relatorio.model.AvaliacaoRelatorio;
import com.fiap.feedback.relatorio.model.PeriodoRelatorio;
import com.fiap.feedback.relatorio.model.ResumoRelatorio;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CsvRelatorioGenerator {
    public String gerar(PeriodoRelatorio periodo, List<AvaliacaoRelatorio> avaliacoes,
            ResumoRelatorio resumo, ZoneId zone) {
        StringBuilder csv = new StringBuilder();
        csv.append("tipo,id,descricao,nota,urgencia,dataEnvio,dia,quantidade,media\r\n");
        csv.append(linha("RESUMO", "", periodo.descricao(), "", "", "", "",
                Integer.toString(resumo.quantidade()), resumo.media() == null ? "não se aplica" : resumo.media().toPlainString()));
        for (String urgencia : List.of("CRITICA", "NORMAL")) {
            csv.append(linha("POR_URGENCIA", "", "", "", urgencia, "", "",
                    Integer.toString(resumo.quantidadePorUrgencia().getOrDefault(urgencia, 0)), ""));
        }
        for (Map.Entry<String, Integer> dia : resumo.quantidadePorDia().entrySet()) {
            csv.append(linha("POR_DIA", "", "", "", "", "", dia.getKey(),
                    Integer.toString(dia.getValue()), ""));
        }
        if (avaliacoes.isEmpty()) {
            csv.append(linha("AVALIACOES", "", "Nenhuma avaliação no período", "", "", "", "", "0", ""));
        } else {
            for (AvaliacaoRelatorio avaliacao : avaliacoes) {
                csv.append(linha("AVALIACAO", avaliacao.id(), avaliacao.descricao(), Integer.toString(avaliacao.nota()),
                        avaliacao.urgencia(), avaliacao.dataEnvio().toString(),
                        avaliacao.dataEnvio().atZone(zone).toLocalDate().toString(), "", ""));
            }
        }
        return csv.toString();
    }

    private String linha(String... valores) {
        return java.util.Arrays.stream(valores)
                .map(this::escapar)
                .collect(java.util.stream.Collectors.joining(",")) + "\r\n";
    }

    private String escapar(String valor) {
        return "\"" + valor.replace("\"", "\"\"") + "\"";
    }
}
