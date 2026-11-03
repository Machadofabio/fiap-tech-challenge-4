package com.fiap.feedback.relatorio.repository;

import java.time.Instant;

public interface GeracaoRelatorioRepository {
    ReservaGeracao reservar(String geracaoId, String origem, String periodo, Instant agora);
    void concluir(String geracaoId, String token, String chaveS3, Instant enviadaEm);

    record ReservaGeracao(String token, boolean jaConcluida, String chaveS3) {
        public static ReservaGeracao adquirida(String token) {
            return new ReservaGeracao(token, false, null);
        }
        public static ReservaGeracao concluida(String chaveS3) {
            return new ReservaGeracao(null, true, chaveS3);
        }
    }
}
