package com.fiap.feedback.avaliacao.service;

import com.fiap.feedback.avaliacao.model.CampoInvalido;
import java.util.List;

public class DadosAvaliacaoInvalidosException extends RuntimeException {
    private final List<CampoInvalido> campos;

    public DadosAvaliacaoInvalidosException(List<CampoInvalido> campos) {
        super("Confira os dados informados.");
        this.campos = List.copyOf(campos);
    }

    public List<CampoInvalido> campos() {
        return campos;
    }
}
