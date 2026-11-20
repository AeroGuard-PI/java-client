package br.com.monitoramento;

public record NotificacaoRede(long id, MetricaRede metrica, Nivel nivel) {
    public enum Nivel {
        MEDICAO, ALERTA, CRITICO
    }
}
