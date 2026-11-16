package br.com.monitoramento;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public record IntervaloRede(String interfaceNome, String macAddress, Instant inicio, Instant coletadoEm,
                            Long duracaoNanos, Long recebidos, Long enviados, Long errosRecebidos,
                            Long errosEnviados, Long descartesRecebidos, String estadoOperacional,
                            String motivo) {
    static IntervaloRede entre(LeituraRede anterior, LeituraRede atual) {
        String motivo = null;
        if (anterior == null) {
            motivo = "PRIMEIRA_LEITURA";
        } else if (!anterior.macAddress().equals(atual.macAddress())) {
            motivo = "INTERFACE_ALTERADA";
        } else if (atual.tempoNanos() <= anterior.tempoNanos()) {
            motivo = "TEMPO_INVALIDO";
        } else if (atual.recebidos() < anterior.recebidos() || atual.enviados() < anterior.enviados()) {
            motivo = "CONTADOR_REINICIADO";
        } else {
            long diferencaRelogios = Math.abs(Duration.between(anterior.dataHora(), atual.dataHora()).toNanos()
                    - (atual.tempoNanos() - anterior.tempoNanos()));
            if (!atual.dataHora().isAfter(anterior.dataHora()) || diferencaRelogios > 1_000_000_000L) {
                motivo = "RELOGIO_ALTERADO";
            }
        }
        if (motivo != null) {
            return new IntervaloRede(atual.interfaceNome(), atual.macAddress(), null, atual.dataHora(),
                    null, null, null, null, null, null, atual.estadoOperacional(), motivo);
        }
        return new IntervaloRede(atual.interfaceNome(), atual.macAddress(), anterior.dataHora(), atual.dataHora(),
                atual.tempoNanos() - anterior.tempoNanos(), atual.recebidos() - anterior.recebidos(),
                atual.enviados() - anterior.enviados(), delta(anterior.errosRecebidos(), atual.errosRecebidos()),
                delta(anterior.errosEnviados(), atual.errosEnviados()),
                delta(anterior.descartesRecebidos(), atual.descartesRecebidos()), atual.estadoOperacional(), null);
    }

    private static Long delta(Long anterior, Long atual) {
        return anterior == null || atual == null || atual < anterior ? null : atual - anterior;
    }

    List<MetricaRede> metricas() {
        if (duracaoNanos == null) {
            return List.of();
        }
        double fator = 8_000.0 / duracaoNanos;
        return List.of(new MetricaRede("Download - " + interfaceNome, recebidos * fator, "Mbps", coletadoEm),
                new MetricaRede("Upload - " + interfaceNome, enviados * fator, "Mbps", coletadoEm));
    }
}
