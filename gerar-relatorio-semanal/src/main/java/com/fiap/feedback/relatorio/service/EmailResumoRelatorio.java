package com.fiap.feedback.relatorio.service;

import com.fiap.feedback.relatorio.model.PeriodoRelatorio;
import com.fiap.feedback.relatorio.model.ResumoRelatorio;

public interface EmailResumoRelatorio {
    void enviar(PeriodoRelatorio periodo, ResumoRelatorio resumo, String chaveS3);
}
