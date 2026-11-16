package br.com.monitoramento;

import java.util.List;

public record ColetaRede(List<MetricaRede> metricas, List<IntervaloRede> intervalos) {
}
