package br.com.monitoramento;

import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class NotificadorSlack implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(NotificadorSlack.class);
    private final BancoDados banco;
    private final PoliticaAlertasRede politica = new PoliticaAlertasRede();
    private final AtomicBoolean ocupado = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("slack-envio").factory());
    private final Instant inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final Slack slack;
    private final MethodsClient cliente;
    private volatile Instant proximaTentativa = Instant.MIN;
    private volatile boolean fechado;
    private volatile CompletableFuture<Boolean> emAndamento;
    private String ultimoAviso;

    public NotificadorSlack(BancoDados banco) {
        this(banco, System.getenv().getOrDefault("SLACK_BOT_TOKEN", ""), configurarCliente());
    }

    NotificadorSlack(BancoDados banco, String token, SlackConfig configuracao) {
        this.banco = banco;
        slack = token == null || token.isBlank() ? null : Slack.getInstance(configuracao);
        cliente = slack == null ? null : slack.methods(token.strip());
        if (cliente == null) {
            LOG.info("Notificações desativadas: SLACK_BOT_TOKEN ausente.");
        }
    }

    static SlackConfig configurarCliente() {
        var configuracao = new SlackConfig();
        configuracao.getMethodsConfig().setStatsEnabled(false);
        configuracao.setHttpClientCallTimeoutMillis(5000);
        configuracao.setHttpClientReadTimeoutMillis(5000);
        return configuracao;
    }

    public CompletableFuture<Boolean> processar(Maquina maquina, String cnpj, List<MetricaRede> metricas) {
        if (cliente == null || fechado || metricas.isEmpty()
                || Instant.now().isBefore(proximaTentativa) || !ocupado.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(false);
        }
        var leituras = List.copyOf(metricas);
        var resultado = new CompletableFuture<Boolean>();
        emAndamento = resultado;
        try {
            executor.execute(() -> {
                boolean enviado = false;
                try {
                    enviado = publicar(maquina, cnpj, leituras);
                } catch (RuntimeException erro) {
                    avisar("Falha na notificação Slack: " + erro.getClass().getSimpleName());
                    proximaTentativa = Instant.now().plusSeconds(60);
                } finally {
                    ocupado.set(false);
                    resultado.complete(enviado);
                }
            });
        } catch (RejectedExecutionException erro) {
            ocupado.set(false);
            resultado.complete(false);
        }
        return resultado;
    }

    private boolean publicar(Maquina maquina, String cnpj, List<MetricaRede> metricas) {
        try {
            var configuracao = banco.buscarConfiguracaoSlack(cnpj);
            if (configuracao == null || !configuracao.ativo() || fechado) {
                politica.selecionar(configuracao, List.of(), List.of(), Instant.now());
                if (configuracao == null) {
                    avisar("Notificações Slack sem configuração no banco.");
                } else {
                    ultimoAviso = null;
                }
                return false;
            }
            var alertas = configuracao.modo() == ConfiguracaoSlack.Modo.CONFIGURADO
                    ? banco.buscarAlertasRede(cnpj, maquina.macAddress(), inicio) : List.<NotificacaoRede>of();
            var notificacoes = politica.selecionar(configuracao, metricas, alertas, Instant.now());
            if (notificacoes.isEmpty() || fechado) {
                return false;
            }
            var resposta = cliente.chatPostMessage(requisicao -> requisicao
                    .channel(configuracao.canal()).text(formatar(maquina, notificacoes)));
            if (!resposta.isOk()) {
                avisar("Slack recusou a mensagem: " + resposta.getError());
                proximaTentativa = Instant.now().plusSeconds(60);
                return false;
            }
            politica.confirmar(notificacoes, Instant.now());
            ultimoAviso = null;
            return true;
        } catch (SQLException erro) {
            avisar("Não foi possível consultar a configuração ou os alertas Slack no banco.");
        } catch (IllegalArgumentException erro) {
            avisar("Configuração Slack inválida: " + erro.getMessage());
        } catch (SlackApiException erro) {
            long espera = erro.getResponse().code() == 429
                    ? segundosDeEspera(erro.getResponse().header("Retry-After")) : 60;
            proximaTentativa = Instant.now().plusSeconds(espera);
            avisar("Falha HTTP no Slack: " + erro.getResponse().code());
        } catch (IOException erro) {
            proximaTentativa = Instant.now().plusSeconds(60);
            avisar("Falha na comunicação com Slack: " + erro.getClass().getSimpleName());
        }
        return false;
    }

    private static long segundosDeEspera(String valor) {
        try {
            long segundos = Long.parseLong(valor);
            return segundos >= 1 && segundos <= Integer.MAX_VALUE ? segundos : 60;
        } catch (NumberFormatException erro) {
            return 60;
        }
    }

    private static String formatar(Maquina maquina, List<NotificacaoRede> notificacoes) {
        var texto = new StringBuilder("*AeroGuard | Rede*\n*Máquina:* ")
                .append(escapar(maquina.hostname())).append('\n');
        for (var notificacao : notificacoes) {
            var metrica = notificacao.metrica();
            texto.append('\n').append(notificacao.nivel()).append(" | ")
                    .append(escapar(metrica.tipo())).append(": ")
                    .append(String.format(Locale.ROOT, "%.2f %s", metrica.valor(), escapar(metrica.unidade())))
                    .append(" | ").append(metrica.dataHora());
        }
        return texto.toString();
    }

    private static String escapar(String valor) {
        return valor.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void avisar(String mensagem) {
        if (!fechado && !mensagem.equals(ultimoAviso)) {
            LOG.warn("{}", mensagem);
            ultimoAviso = mensagem;
        }
    }

    @Override
    public void close() {
        if (fechado) {
            return;
        }
        fechado = true;
        executor.shutdownNow();
        var pendente = emAndamento;
        if (pendente != null) {
            pendente.complete(false);
        }
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warn("Encerramento do envio Slack excedeu o prazo.");
            }
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
        }
        if (slack != null) {
            try {
                slack.close();
            } catch (Exception erro) {
                LOG.warn("Falha ao encerrar o cliente Slack.");
            }
        }
    }
}
