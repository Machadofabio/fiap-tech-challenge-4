package com.fiap.feedback.avaliacao.controller;

import com.fiap.feedback.avaliacao.service.CorpoJsonInvalidoException;
import com.fiap.feedback.avaliacao.service.DadosAvaliacaoInvalidosException;
import com.fiap.feedback.avaliacao.model.CampoInvalido;
import com.fiap.feedback.avaliacao.model.ResultadoRegistro;
import com.fiap.feedback.avaliacao.service.RegistrarAvaliacao;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

@Path("/avaliacao")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AvaliacaoResource {
    private static final Logger LOG = Logger.getLogger(AvaliacaoResource.class);
    private static final Pattern UUID_CANONICO = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final RegistrarAvaliacao registrarAvaliacao;

    @Inject
    public AvaliacaoResource(RegistrarAvaliacao registrarAvaliacao) {
        this.registrarAvaliacao = registrarAvaliacao;
    }

    @POST
    public Response criar(@HeaderParam("Idempotency-Key") String chave, byte[] corpo) {
        UUID id = interpretarChave(chave);
        if (id == null) {
            return erroChaveInvalida();
        }

        try {
            ResultadoRegistro resultado = registrarAvaliacao.executar(id, decodificarUtf8(corpo));
            return Response.status(resultado.criada() ? Response.Status.CREATED : Response.Status.OK)
                    .entity(RespostaAvaliacao.de(resultado.avaliacao(), resultado.criada()))
                    .build();
        } catch (CharacterCodingException exception) {
            return erroJsonInvalido();
        } catch (CorpoJsonInvalidoException exception) {
            return erroJsonInvalido();
        } catch (DadosAvaliacaoInvalidosException exception) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new RespostaErro("DADOS_INVALIDOS", exception.getMessage(), exception.campos()))
                    .build();
        } catch (Exception exception) {
            LOG.error("Falha interna ao registrar avaliação.", exception);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new RespostaErro(
                            "ERRO_INTERNO",
                            "Não foi possível processar a avaliação. Tente novamente.",
                            List.of()))
                    .build();
        }
    }

    private String decodificarUtf8(byte[] corpo) throws CharacterCodingException {
        if (corpo == null) {
            return null;
        }
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(corpo))
                .toString();
    }

    private Response erroJsonInvalido() {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new RespostaErro("JSON_INVALIDO", "O corpo da solicitação deve ser um JSON válido.", List.of()))
                .build();
    }

    private UUID interpretarChave(String chave) {
        if (chave == null || !UUID_CANONICO.matcher(chave).matches()) {
            return null;
        }
        try {
            return UUID.fromString(chave);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private Response erroChaveInvalida() {
        CampoInvalido campo = new CampoInvalido(
                "Idempotency-Key", "FORMATO_INVALIDO", "Envie um UUID válido.");
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new RespostaErro(
                        "CHAVE_INVALIDA",
                        "Envie Idempotency-Key com um UUID válido.",
                        List.of(campo)))
                .build();
    }
}
