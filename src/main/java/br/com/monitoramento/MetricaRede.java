package br.com.monitoramento;

import java.time.Instant;

public record MetricaRede(String tipo, double valor, String unidade, Instant dataHora) {
}
