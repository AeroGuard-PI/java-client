package br.com.monitoramento;

import br.com.monitoramento.NotificacaoRede.Nivel;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class PoliticaAlertasRede {
    private final Map<String, Envio> confirmados = new HashMap<>();
    private ConfiguracaoSlack configuracao;

    public List<NotificacaoRede> selecionar(ConfiguracaoSlack atual, List<MetricaRede> metricas,
                                            List<NotificacaoRede> alertas, Instant agora) {
        if (!Objects.equals(configuracao, atual)) {
            confirmados.clear();
            configuracao = atual;
        }
        if (atual == null || !atual.ativo()) {
            return List.of();
        }
        var tipos = metricas.stream().map(MetricaRede::tipo).collect(Collectors.toSet());
        confirmados.keySet().retainAll(tipos);
        if (atual.modo() == ConfiguracaoSlack.Modo.TUDO) {
            return metricas.stream()
                    .map(metrica -> new NotificacaoRede(0, metrica, Nivel.MEDICAO))
                    .toList();
        }
        var recentes = new LinkedHashMap<String, NotificacaoRede>();
        for (var alerta : alertas) {
            if (tipos.contains(alerta.metrica().tipo())) {
                recentes.merge(alerta.metrica().tipo(), alerta,
                        (anterior, novo) -> novo.id() > anterior.id() ? novo : anterior);
            }
        }
        return recentes.values().stream()
                .filter(alerta -> alerta.nivel() != Nivel.MEDICAO)
                .filter(alerta -> alerta.nivel().ordinal() >= atual.nivelMinimo().ordinal())
                .filter(alerta -> deveEnviar(alerta, atual.cooldownMin(), agora))
                .toList();
    }

    private boolean deveEnviar(NotificacaoRede alerta, int cooldownMin, Instant agora) {
        var anterior = confirmados.get(alerta.metrica().tipo());
        if (anterior == null) {
            return true;
        }
        if (alerta.id() <= anterior.id()) {
            return false;
        }
        return alerta.nivel() != anterior.nivel()
                || cooldownMin > 0
                && Duration.between(anterior.instante(), agora).compareTo(Duration.ofMinutes(cooldownMin)) >= 0;
    }

    public void confirmar(List<NotificacaoRede> notificacoes, Instant instante) {
        for (var notificacao : notificacoes) {
            if (notificacao.nivel() != Nivel.MEDICAO) {
                confirmados.put(notificacao.metrica().tipo(),
                        new Envio(notificacao.id(), notificacao.nivel(), instante));
            }
        }
    }

    private record Envio(long id, Nivel nivel, Instant instante) {
    }
}
