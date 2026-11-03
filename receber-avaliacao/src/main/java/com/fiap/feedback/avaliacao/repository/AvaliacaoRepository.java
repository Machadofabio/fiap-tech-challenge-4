package com.fiap.feedback.avaliacao.repository;

import com.fiap.feedback.avaliacao.model.Avaliacao;
import java.util.Optional;
import java.util.UUID;

public interface AvaliacaoRepository {
    Optional<Avaliacao> buscarPorId(UUID id);

    /** Persists only when the key is absent; returns the original item when another request won the race. */
    Optional<Avaliacao> salvarSeAusente(Avaliacao avaliacao);
}
