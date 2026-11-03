package com.fiap.feedback.avaliacao.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fiap.feedback.avaliacao.service.CorpoJsonInvalidoException;
import com.fiap.feedback.avaliacao.model.CorpoAvaliacaoParseado;
import com.fiap.feedback.avaliacao.service.CorpoAvaliacaoParser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;

@ApplicationScoped
public class JacksonCorpoAvaliacaoParser implements CorpoAvaliacaoParser {
    private final ObjectMapper objectMapper;

    @Inject
    public JacksonCorpoAvaliacaoParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public CorpoAvaliacaoParseado parsear(String corpo) {
        if (corpo == null || corpo.isBlank()) {
            throw new CorpoJsonInvalidoException(new IllegalArgumentException("JSON vazio"));
        }
        final JsonNode raiz;
        try {
            raiz = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(corpo);
        } catch (JsonProcessingException exception) {
            throw new CorpoJsonInvalidoException(exception);
        }
        if (raiz == null) {
            throw new CorpoJsonInvalidoException(new IllegalArgumentException("JSON vazio"));
        }
        if (!raiz.isObject()) {
            return new CorpoAvaliacaoParseado(false, false, false, null, false, false, null);
        }

        JsonNode descricao = raiz.get("descricao");
        JsonNode nota = raiz.get("nota");
        boolean notaNumero = nota != null && nota.isNumber();
        BigDecimal valorNota = notaNumero ? nota.decimalValue() : null;

        return new CorpoAvaliacaoParseado(
                true,
                raiz.has("descricao"),
                descricao != null && descricao.isTextual(),
                descricao != null && descricao.isTextual() ? descricao.textValue() : null,
                raiz.has("nota"),
                notaNumero,
                valorNota);
    }
}
