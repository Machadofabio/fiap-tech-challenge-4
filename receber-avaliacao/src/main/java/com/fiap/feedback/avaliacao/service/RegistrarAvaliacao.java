package com.fiap.feedback.avaliacao.service;

import com.fiap.feedback.avaliacao.service.CorpoJsonInvalidoException;
import com.fiap.feedback.avaliacao.service.DadosAvaliacaoInvalidosException;
import com.fiap.feedback.avaliacao.model.CampoInvalido;
import com.fiap.feedback.avaliacao.model.CorpoAvaliacaoParseado;
import com.fiap.feedback.avaliacao.model.ResultadoRegistro;
import com.fiap.feedback.avaliacao.model.Avaliacao;
import com.fiap.feedback.avaliacao.model.DescricaoNormalizer;
import com.fiap.feedback.avaliacao.model.Urgencia;
import com.fiap.feedback.avaliacao.repository.AvaliacaoRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class RegistrarAvaliacao {
    private final AvaliacaoRepository repositorio;
    private final CorpoAvaliacaoParser parser;

    @Inject
    public RegistrarAvaliacao(AvaliacaoRepository repositorio, CorpoAvaliacaoParser parser) {
        this.repositorio = repositorio;
        this.parser = parser;
    }

    public ResultadoRegistro executar(UUID id, String corpo) {
        Optional<Avaliacao> existente = repositorio.buscarPorId(id);
        if (existente.isPresent()) {
            return new ResultadoRegistro(existente.get(), false);
        }

        CorpoAvaliacaoParseado entrada = parser.parsear(corpo);

        if (!entrada.objetoJson()) {
            throw new DadosAvaliacaoInvalidosException(List.of());
        }

        List<CampoInvalido> erros = new ArrayList<>();
        String descricao = validarDescricao(entrada, erros);
        Integer nota = validarNota(entrada, erros);
        if (!erros.isEmpty()) {
            throw new DadosAvaliacaoInvalidosException(erros);
        }

        Avaliacao avaliacao = new Avaliacao(
                id,
                descricao,
                nota,
                Urgencia.classificar(nota),
                Instant.now());

        Optional<Avaliacao> original = repositorio.salvarSeAusente(avaliacao);
        return original.map(value -> new ResultadoRegistro(value, false))
                .orElseGet(() -> new ResultadoRegistro(avaliacao, true));
    }

    private String validarDescricao(CorpoAvaliacaoParseado entrada, List<CampoInvalido> erros) {
        if (!entrada.descricaoPresente()) {
            erros.add(new CampoInvalido("descricao", "OBRIGATORIO", "A descrição é obrigatória."));
            return null;
        }
        if (!entrada.descricaoTexto()) {
            erros.add(new CampoInvalido("descricao", "TIPO_INVALIDO", "A descrição deve ser um texto."));
            return null;
        }
        String normalizada = DescricaoNormalizer.removerEspacosUnicodeDasExtremidades(entrada.descricao());
        int tamanho = normalizada.codePointCount(0, normalizada.length());
        if (tamanho < 1 || tamanho > 2000) {
            erros.add(new CampoInvalido("descricao", "COMPRIMENTO_INVALIDO", "A descrição deve ter de 1 a 2.000 caracteres."));
            return null;
        }
        return normalizada;
    }

    private Integer validarNota(CorpoAvaliacaoParseado entrada, List<CampoInvalido> erros) {
        if (!entrada.notaPresente()) {
            erros.add(new CampoInvalido("nota", "OBRIGATORIO", "A nota é obrigatória."));
            return null;
        }
        if (!entrada.notaNumero() || entrada.nota() == null || entrada.nota().stripTrailingZeros().scale() > 0) {
            erros.add(new CampoInvalido("nota", "TIPO_INVALIDO", "A nota deve ser um número inteiro."));
            return null;
        }
        if (entrada.nota().compareTo(BigDecimal.ZERO) < 0 || entrada.nota().compareTo(BigDecimal.TEN) > 0) {
            erros.add(new CampoInvalido("nota", "FORA_DO_INTERVALO", "A nota deve estar entre 0 e 10."));
            return null;
        }
        return entrada.nota().intValueExact();
    }
}
