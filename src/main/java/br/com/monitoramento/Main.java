package br.com.monitoramento;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);
    public static void main(String[] args) {
        var captura = new CapturaRede();

        if (args.length == 1 && args[0].equals("--interfaces")) {
            captura.listarInterfaces();
            return;
        }
        if (args.length != 0) {
            System.out.println("Use --interfaces para consultar os endereços MAC.");
            return;
        }

        var mac = CapturaRede.normalizarMac(Conexao.variavel("MAC_ADDRESS"));
        var cnpj = Conexao.variavel("EMPRESA_CNPJ").strip();
        if (!cnpj.matches("[0-9]{14}")) {
            throw new IllegalArgumentException("EMPRESA_CNPJ deve conter 14 dígitos, sem pontuação.");
        }
        var banco = new BancoDados();
        var slack = new NotificadorSlack(banco);
        var agendador = Executors.newSingleThreadScheduledExecutor();

        agendador.scheduleAtFixedRate(() -> {
            try {
                var maquina = captura.identificarMaquina(mac);
                var metricas = captura.coletar();
                int quantidade = banco.salvar(maquina, cnpj, metricas);
                if (quantidade > 0) {
                    slack.processar(maquina, cnpj, metricas);
                }
            } catch (Exception | LinkageError erro) {
                LOG.warn("Falha na coleta: {}", erro.getMessage());
            }
        }, 0, 5, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            agendador.shutdown();
            try {
                if (!agendador.awaitTermination(15, TimeUnit.SECONDS)) {
                    agendador.shutdownNow();
                }
            } catch (InterruptedException erro) {
                agendador.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                slack.close();
            }
        }));

        LOG.info("Monitor de rede iniciado. Coleta a cada cinco segundos.");
    }
}
