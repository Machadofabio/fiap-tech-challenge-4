package com.fiap.feedback.notificacao.service;

import com.fiap.feedback.notificacao.model.AvaliacaoCritica;

public interface EnviadorAlerta {
    void enviar(AvaliacaoCritica avaliacao);
}
