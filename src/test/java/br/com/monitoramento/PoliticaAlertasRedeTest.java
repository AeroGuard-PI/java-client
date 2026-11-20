package br.com.monitoramento;

import br.com.monitoramento.NotificacaoRede.Nivel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PoliticaAlertasRedeTest {
    private final Instant agora = Instant.parse("2026-11-20T12:00:00Z");
    private final MetricaRede download = new MetricaRede("Download - eth0", 1500, "Mbps", agora);
    private final MetricaRede upload = new MetricaRede("Upload - eth0", 20, "Mbps", agora);
    private final PoliticaAlertasRede politica = new PoliticaAlertasRede();

    @Test
    void modoTudoPublicaMedicoesSemInventarSeveridade() {
        var configuracao = config(ConfiguracaoSlack.Modo.TUDO, Nivel.CRITICO, 60);
        var selecionadas = politica.selecionar(configuracao, List.of(download, upload), List.of(), agora);
        assertEquals(2, selecionadas.size());
        assertTrue(selecionadas.stream().allMatch(item -> item.nivel() == Nivel.MEDICAO));
    }

    @Test
    void modoConfiguradoExigeAlertaRegistrado() {
        var configuracao = config(ConfiguracaoSlack.Modo.CONFIGURADO, Nivel.ALERTA, 60);
        assertTrue(politica.selecionar(configuracao, List.of(download), List.of(), agora).isEmpty());
    }

    @Test
    void filtraNivelMinimoEEscolheEventoMaisRecenteDaMetrica() {
        var configuracao = config(ConfiguracaoSlack.Modo.CONFIGURADO, Nivel.CRITICO, 60);
        var antigo = new NotificacaoRede(1, download, Nivel.ALERTA);
        var recente = new NotificacaoRede(2, download, Nivel.CRITICO);
        var outro = new NotificacaoRede(3, upload, Nivel.ALERTA);
        assertEquals(List.of(recente), politica.selecionar(configuracao,
                List.of(download, upload), List.of(antigo, recente, outro), agora));
    }

    @Test
    void cooldownUsaMinutosEExigeUmNovoRegistro() {
        var configuracao = config(ConfiguracaoSlack.Modo.CONFIGURADO, Nivel.ALERTA, 60);
        var primeiro = new NotificacaoRede(1, download, Nivel.ALERTA);
        var seguinte = new NotificacaoRede(2, download, Nivel.ALERTA);
        var leituras = List.of(download);
        politica.selecionar(configuracao, leituras, List.of(primeiro), agora);
        politica.confirmar(List.of(primeiro), agora);
        assertTrue(politica.selecionar(configuracao, leituras, List.of(seguinte),
                agora.plusSeconds(3599)).isEmpty());
        assertEquals(List.of(seguinte), politica.selecionar(configuracao, leituras,
                List.of(seguinte), agora.plusSeconds(3600)));
        assertTrue(politica.selecionar(configuracao, leituras, List.of(primeiro),
                agora.plusSeconds(7200)).isEmpty());
    }

    @Test
    void falhaDeEnvioNaoConsomeOEvento() {
        var configuracao = config(ConfiguracaoSlack.Modo.CONFIGURADO, Nivel.ALERTA, 60);
        var alerta = new NotificacaoRede(4, download, Nivel.ALERTA);
        assertEquals(List.of(alerta), politica.selecionar(configuracao, List.of(download),
                List.of(alerta), agora));
        assertEquals(List.of(alerta), politica.selecionar(configuracao, List.of(download),
                List.of(alerta), agora.plusSeconds(5)));
    }

    @Test
    void cooldownZeroMantemMudancasDeNivelSemLembretes() {
        var configuracao = config(ConfiguracaoSlack.Modo.CONFIGURADO, Nivel.ALERTA, 0);
        var alerta = new NotificacaoRede(1, download, Nivel.ALERTA);
        politica.selecionar(configuracao, List.of(download), List.of(alerta), agora);
        politica.confirmar(List.of(alerta), agora);
        assertTrue(politica.selecionar(configuracao, List.of(download),
                List.of(new NotificacaoRede(2, download, Nivel.ALERTA)), agora.plusSeconds(86400)).isEmpty());
        var critico = new NotificacaoRede(3, download, Nivel.CRITICO);
        assertEquals(List.of(critico), politica.selecionar(configuracao, List.of(download),
                List.of(critico), agora.plusSeconds(5)));
    }

    @Test
    void desativacaoOuMudancaDeCanalAtualizaAPolitica() {
        var configuracao = config(ConfiguracaoSlack.Modo.CONFIGURADO, Nivel.ALERTA, 60);
        var alerta = new NotificacaoRede(1, download, Nivel.ALERTA);
        politica.selecionar(configuracao, List.of(download), List.of(alerta), agora);
        politica.confirmar(List.of(alerta), agora);
        var desativada = new ConfiguracaoSlack(false, "C1", configuracao.modo(), Nivel.ALERTA, 60);
        assertTrue(politica.selecionar(desativada, List.of(download), List.of(alerta), agora).isEmpty());
        var outroCanal = new ConfiguracaoSlack(true, "C2", configuracao.modo(), Nivel.ALERTA, 60);
        assertEquals(List.of(alerta), politica.selecionar(outroCanal, List.of(download), List.of(alerta), agora));
    }

    @Test
    void ignoraAlertaDeInterfaceQueNaoEstaMaisNaColeta() {
        var configuracao = config(ConfiguracaoSlack.Modo.CONFIGURADO, Nivel.ALERTA, 60);
        assertTrue(politica.selecionar(configuracao, List.of(upload),
                List.of(new NotificacaoRede(1, download, Nivel.CRITICO)), agora).isEmpty());
    }

    private ConfiguracaoSlack config(ConfiguracaoSlack.Modo modo, Nivel nivel, int cooldown) {
        return new ConfiguracaoSlack(true, "C1", modo, nivel, cooldown);
    }
}
