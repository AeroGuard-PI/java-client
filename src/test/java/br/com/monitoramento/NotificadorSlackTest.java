package br.com.monitoramento;

import br.com.monitoramento.NotificacaoRede.Nivel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class NotificadorSlackTest {
    private final BancoSimulado banco = new BancoSimulado();
    private final BlockingQueue<Map<String, String>> recebidas = new LinkedBlockingQueue<>();
    private final List<NotificadorSlack> clientes = new ArrayList<>();
    private final Maquina maquina = new Maquina("máquina <rede>", "Linux", "02:00:00:00:00:01");
    private final List<MetricaRede> metricas = List.of(
            new MetricaRede("Download - eth0", 12.5, "Mbps", Instant.parse("2026-11-20T12:00:00Z")));
    private final AtomicInteger status = new AtomicInteger(200);
    private HttpServer servidor;
    private ExecutorService atendimentos;
    private volatile String corpo = "{\"ok\":true,\"channel\":\"C1\",\"ts\":\"1.0\"}";
    private volatile long atraso;

    @BeforeEach
    void iniciarServidor() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        atendimentos = Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());
        servidor.setExecutor(atendimentos);
        servidor.createContext("/api/chat.postMessage", this::responder);
        servidor.start();
    }

    @AfterEach
    void encerrar() {
        clientes.forEach(NotificadorSlack::close);
        servidor.stop(0);
        atendimentos.shutdownNow();
    }

    @Test
    void publicaComTokenCanalDoBancoEMensagemEscapada() throws Exception {
        var cliente = cliente("xoxb-test", 5000);
        assertTrue(cliente.processar(maquina, "11111111000191", metricas).get(5, TimeUnit.SECONDS));
        var recebida = recebidas.poll(1, TimeUnit.SECONDS);
        assertNotNull(recebida);
        assertEquals("Bearer xoxb-test", recebida.get("authorization"));
        assertEquals("C1", recebida.get("channel"));
        assertTrue(recebida.get("text").contains("máquina &lt;rede&gt;"));
        assertTrue(recebida.get("text").contains("12.50 Mbps"));
    }

    @Test
    void naoConsultaBancoNemPublicaSemToken() throws Exception {
        var cliente = cliente("", 5000);
        assertFalse(cliente.processar(maquina, "11111111000191", metricas).get());
        assertEquals(0, banco.consultas.get());
        assertTrue(recebidas.isEmpty());
    }

    @Test
    void ausenciaDeConfiguracaoDesativaSemFallback() throws Exception {
        banco.configuracao = null;
        var cliente = cliente("xoxb-test", 5000);
        assertFalse(cliente.processar(maquina, "11111111000191", metricas).get());
        assertTrue(recebidas.isEmpty());
    }

    @Test
    void mudancaDeCanalEDesativacaoSaoLidasNoBanco() throws Exception {
        var cliente = cliente("xoxb-test", 5000);
        assertTrue(cliente.processar(maquina, "11111111000191", metricas).get());
        assertEquals("C1", recebidas.take().get("channel"));
        banco.configuracao = config(true, "C2");
        assertTrue(cliente.processar(maquina, "11111111000191", metricas).get());
        assertEquals("C2", recebidas.take().get("channel"));
        banco.configuracao = config(false, "C2");
        assertFalse(cliente.processar(maquina, "11111111000191", metricas).get());
        assertTrue(recebidas.isEmpty());
    }

    @Test
    void erroDoBancoNaoEnviaEPermiteNovoCiclo() throws Exception {
        var cliente = cliente("xoxb-test", 5000);
        banco.falha = new SQLException("Banco indisponível");
        assertFalse(cliente.processar(maquina, "11111111000191", metricas).get());
        assertTrue(recebidas.isEmpty());
        banco.falha = null;
        assertTrue(cliente.processar(maquina, "11111111000191", metricas).get());
    }

    @Test
    void chamadaRetornaEnquantoBancoEstaBloqueadoESoAceitaUmaTarefa() throws Exception {
        banco.entrada = new CountDownLatch(1);
        banco.liberacao = new CountDownLatch(1);
        var cliente = cliente("xoxb-test", 5000);
        var primeira = cliente.processar(maquina, "11111111000191", metricas);
        assertTrue(banco.entrada.await(2, TimeUnit.SECONDS));
        assertFalse(primeira.isDone());
        assertFalse(cliente.processar(maquina, "11111111000191", metricas).get());
        banco.liberacao.countDown();
        assertTrue(primeira.get(5, TimeUnit.SECONDS));
        assertEquals(1, banco.consultas.get());
    }

    @Test
    void http200ComOkFalseNaoConfirmaEnvio() throws Exception {
        corpo = "{\"ok\":false,\"error\":\"invalid_auth\"}";
        var cliente = cliente("xoxb-test", 5000);
        assertFalse(cliente.processar(maquina, "11111111000191", metricas).get());
        assertNotNull(recebidas.poll(1, TimeUnit.SECONDS));
        assertFalse(cliente.processar(maquina, "11111111000191", metricas).get());
        assertTrue(recebidas.isEmpty());
    }

    @Test
    void respeitaRetryAfterAntesDeNovaTentativa() throws Exception {
        status.set(429);
        corpo = "{\"ok\":false,\"error\":\"ratelimited\"}";
        var cliente = cliente("xoxb-test", 5000);
        assertFalse(cliente.processar(maquina, "11111111000191", metricas).get());
        recebidas.take();
        assertFalse(cliente.processar(maquina, "11111111000191", metricas).get());
        assertTrue(recebidas.isEmpty());
        TimeUnit.MILLISECONDS.sleep(1100);
        status.set(200);
        corpo = "{\"ok\":true}";
        assertTrue(cliente.processar(maquina, "11111111000191", metricas).get(5, TimeUnit.SECONDS));
        assertEquals(1, recebidas.size());
    }

    @Test
    void timeoutNaoBloqueiaOChamadorNemAcumulaRequisicoes() throws Exception {
        atraso = 1000;
        var cliente = cliente("xoxb-test", 150);
        var futuro = cliente.processar(maquina, "11111111000191", metricas);
        assertFalse(futuro.get(3, TimeUnit.SECONDS));
        assertEquals(1, recebidas.size());
        assertFalse(cliente.processar(maquina, "11111111000191", metricas).get());
        assertEquals(1, recebidas.size());
    }

    @Test
    void modoConfiguradoSoPublicaAlertasPersistidosEDeduplica() throws Exception {
        banco.configuracao = new ConfiguracaoSlack(true, "C1", ConfiguracaoSlack.Modo.CONFIGURADO,
                Nivel.CRITICO, 60);
        var cliente = cliente("xoxb-test", 5000);
        assertFalse(cliente.processar(maquina, "11111111000191", metricas).get());
        banco.alertas = List.of(new NotificacaoRede(1, metricas.getFirst(), Nivel.CRITICO));
        assertTrue(cliente.processar(maquina, "11111111000191", metricas).get());
        assertTrue(recebidas.take().get("text").contains("CRITICO"));
        assertFalse(cliente.processar(maquina, "11111111000191", metricas).get());
        assertTrue(recebidas.isEmpty());
    }

    @Test
    void encerramentoResolveTarefaPendenteERecusaNovosEnvios() throws Exception {
        banco.entrada = new CountDownLatch(1);
        banco.liberacao = new CountDownLatch(1);
        var cliente = cliente("xoxb-test", 5000);
        var pendente = cliente.processar(maquina, "11111111000191", metricas);
        assertTrue(banco.entrada.await(2, TimeUnit.SECONDS));
        cliente.close();
        assertFalse(pendente.get(1, TimeUnit.SECONDS));
        assertFalse(cliente.processar(maquina, "11111111000191", metricas).get());
        assertTrue(recebidas.isEmpty());
    }

    private NotificadorSlack cliente(String token, int timeout) {
        var configuracao = NotificadorSlack.configurarCliente();
        configuracao.setMethodsEndpointUrlPrefix("http://127.0.0.1:" + servidor.getAddress().getPort() + "/api/");
        configuracao.setHttpClientCallTimeoutMillis(timeout);
        configuracao.setHttpClientReadTimeoutMillis(timeout);
        var cliente = new NotificadorSlack(banco, token, configuracao);
        clientes.add(cliente);
        return cliente;
    }

    private static ConfiguracaoSlack config(boolean ativo, String canal) {
        return new ConfiguracaoSlack(ativo, canal, ConfiguracaoSlack.Modo.TUDO, Nivel.ALERTA, 60);
    }

    private void responder(HttpExchange troca) throws IOException {
        var parametros = new HashMap<String, String>();
        var form = new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        for (var campo : form.split("&")) {
            var partes = campo.split("=", 2);
            if (partes.length == 2) {
                parametros.put(URLDecoder.decode(partes[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(partes[1], StandardCharsets.UTF_8));
            }
        }
        parametros.put("authorization", troca.getRequestHeaders().getFirst("Authorization"));
        recebidas.add(parametros);
        try {
            TimeUnit.MILLISECONDS.sleep(atraso);
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
        }
        troca.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (status.get() == 429) {
            troca.getResponseHeaders().set("Retry-After", "1");
        }
        var bytes = corpo.getBytes(StandardCharsets.UTF_8);
        try {
            troca.sendResponseHeaders(status.get(), bytes.length);
            troca.getResponseBody().write(bytes);
        } finally {
            troca.close();
        }
    }

    private static class BancoSimulado extends BancoDados {
        private volatile ConfiguracaoSlack configuracao = config(true, "C1");
        private volatile List<NotificacaoRede> alertas = List.of();
        private volatile SQLException falha;
        private CountDownLatch entrada;
        private CountDownLatch liberacao;
        private final AtomicInteger consultas = new AtomicInteger();

        @Override
        public ConfiguracaoSlack buscarConfiguracaoSlack(String cnpj) throws SQLException {
            consultas.incrementAndGet();
            if (entrada != null) {
                entrada.countDown();
                try {
                    if (!liberacao.await(5, TimeUnit.SECONDS)) {
                        throw new SQLException("Consulta simulada excedeu o prazo.");
                    }
                } catch (InterruptedException erro) {
                    Thread.currentThread().interrupt();
                    throw new SQLException(erro);
                }
            }
            if (falha != null) {
                throw falha;
            }
            return configuracao;
        }

        @Override
        public List<NotificacaoRede> buscarAlertasRede(String cnpj, String mac, Instant inicio) {
            return alertas;
        }
    }
}
