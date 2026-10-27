package br.com.monitoramento;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {
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
        var agendador = Executors.newSingleThreadScheduledExecutor();

        agendador.scheduleAtFixedRate(() -> {
            try {
                var maquina = captura.identificarMaquina(mac);
                var metricas = captura.coletar();
                int quantidade = banco.salvar(maquina, cnpj, metricas);
                System.out.println("Capturas de rede salvas: " + quantidade);
            } catch (Exception | LinkageError erro) {
                System.err.println("Falha na coleta: " + erro.getMessage());
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
            }
        }));

        System.out.println("Monitor de rede iniciado. Para encerrar, pressione Ctrl+C.");
    }
}
