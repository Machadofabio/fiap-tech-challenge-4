package com.fiap.feedback.avaliacao.model;

public enum Urgencia {
    CRITICA,
    NORMAL;

    public static Urgencia classificar(int nota) {
        return nota <= 4 ? CRITICA : NORMAL;
    }
}
