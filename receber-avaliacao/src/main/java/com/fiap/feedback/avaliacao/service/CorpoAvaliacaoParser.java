package com.fiap.feedback.avaliacao.service;

import com.fiap.feedback.avaliacao.model.CorpoAvaliacaoParseado;

public interface CorpoAvaliacaoParser {
    CorpoAvaliacaoParseado parsear(String corpo);
}
