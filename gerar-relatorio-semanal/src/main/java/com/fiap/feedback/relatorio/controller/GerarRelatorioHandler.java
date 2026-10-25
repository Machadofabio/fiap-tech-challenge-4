package com.fiap.feedback.relatorio.controller;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fiap.feedback.relatorio.model.SolicitacaoRelatorio;
import com.fiap.feedback.relatorio.service.GerarRelatorioService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
@Named("report")
public class GerarRelatorioHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    private static final Logger LOG = Logger.getLogger(GerarRelatorioHandler.class);
    private final GerarRelatorioService service;

    @Inject
    public GerarRelatorioHandler(GerarRelatorioService service) {
        this.service = service;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        if (event == null) throw new IllegalArgumentException("O evento do relatório é obrigatório.");
        SolicitacaoRelatorio solicitacao = new SolicitacaoRelatorio(
                texto(event, "tipo"), texto(event, "dataInicio"), texto(event, "dataFim"),
                texto(event, "geracaoId"), texto(event, "dataReferencia"));
        LOG.infof("Solicitação de relatório recebida; tipo=%s; geracaoId=%s",
                solicitacao.tipo(), solicitacao.geracaoId());
        return service.gerar(solicitacao).comoMapa();
    }

    private String texto(Map<String, Object> event, String name) {
        Object value = event.get(name);
        if (value == null) return null;
        if (value instanceof String string) return string;
        throw new IllegalArgumentException("O campo '" + name + "' deve ser texto.");
    }
}
