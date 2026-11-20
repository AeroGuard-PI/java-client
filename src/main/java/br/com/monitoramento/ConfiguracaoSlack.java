package br.com.monitoramento;

import br.com.monitoramento.NotificacaoRede.Nivel;

public record ConfiguracaoSlack(boolean ativo, String canal, Modo modo,
                                Nivel nivelMinimo, int cooldownMin) {
    public ConfiguracaoSlack {
        if (canal == null || canal.isBlank()) {
            throw new IllegalArgumentException("Canal não configurado no banco.");
        }
        if (modo == null || nivelMinimo == null || nivelMinimo == Nivel.MEDICAO) {
            throw new IllegalArgumentException("Modo ou nível mínimo inválido.");
        }
        if (cooldownMin < 0 || cooldownMin > 1440) {
            throw new IllegalArgumentException("Cooldown deve estar entre 0 e 1440 minutos.");
        }
        canal = canal.strip();
    }

    public enum Modo {
        TUDO, CONFIGURADO
    }
}
