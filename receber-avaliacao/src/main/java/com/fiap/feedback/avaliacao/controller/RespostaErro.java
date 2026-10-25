package com.fiap.feedback.avaliacao.controller;

import com.fiap.feedback.avaliacao.model.CampoInvalido;
import java.util.List;

public record RespostaErro(String codigo, String mensagem, List<CampoInvalido> campos) {
    public RespostaErro {
        campos = List.copyOf(campos);
    }
}
