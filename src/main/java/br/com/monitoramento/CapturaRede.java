package br.com.monitoramento;

import oshi.SystemInfo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CapturaRede {
    private final SystemInfo sistema = new SystemInfo();
    private final Map<String, LeituraRede> anteriores = new HashMap<>();

    public void listarInterfaces() {
        var interfaces = sistema.getHardware().getNetworkIFs();
        if (interfaces.isEmpty()) {
            System.out.println("Nenhuma interface de rede encontrada.");
        }
        for (var rede : interfaces) {
            System.out.println(rede.getName() + " | " + rede.getMacaddr());
        }
    }

    public Maquina identificarMaquina(String mac) {
        for (var rede : sistema.getHardware().getNetworkIFs()) {
            if (rede.getMacaddr().equalsIgnoreCase(mac)) {
                var so = sistema.getOperatingSystem();
                return new Maquina(so.getNetworkParams().getHostName(), so.getFamily(), mac);
            }
        }
        throw new IllegalArgumentException("O MAC configurado não foi encontrado nesta máquina.");
    }

    public synchronized List<MetricaRede> coletar() {
        var metricas = new ArrayList<MetricaRede>();
        var encontradas = new HashSet<String>();
        try {
            for (var rede : sistema.getHardware().getNetworkIFs()) {
                try {
                    if (!rede.updateAttributes() || rede.getBytesRecv() < 0 || rede.getBytesSent() < 0) {
                        continue;
                    }
                    var atual = new LeituraRede(rede.getName(), rede.getMacaddr(),
                            rede.getBytesRecv(), rede.getBytesSent(), System.nanoTime(), Instant.now());
                    encontradas.add(atual.interfaceNome());
                    var anterior = anteriores.put(atual.interfaceNome(), atual);
                    metricas.addAll(calcular(anterior, atual));
                } catch (RuntimeException erro) {
                    System.err.println("Não foi possível ler " + rede.getName() + ".");
                }
            }
        } catch (RuntimeException | LinkageError erro) {
            anteriores.clear();
            throw erro;
        }
        anteriores.keySet().retainAll(encontradas);
        return List.copyOf(metricas);
    }

    static List<MetricaRede> calcular(LeituraRede anterior, LeituraRede atual) {
        if (anterior == null || !anterior.macAddress().equals(atual.macAddress())) {
            return List.of();
        }
        long recebidos = atual.recebidos() - anterior.recebidos();
        long enviados = atual.enviados() - anterior.enviados();
        double segundos = (atual.tempoNanos() - anterior.tempoNanos()) / 1_000_000_000.0;
        if (segundos <= 0 || recebidos < 0 || enviados < 0) {
            return List.of();
        }
        if (atual.interfaceNome().length() > 49) {
            throw new IllegalArgumentException("Nome da interface excede o limite de captura.tipo.");
        }
        double download = recebidos * 8.0 / segundos / 1_000_000.0;
        double upload = enviados * 8.0 / segundos / 1_000_000.0;
        return List.of(
                new MetricaRede("Download - " + atual.interfaceNome(), download, "Mbps", atual.dataHora()),
                new MetricaRede("Upload - " + atual.interfaceNome(), upload, "Mbps", atual.dataHora())
        );
    }

    public static String normalizarMac(String mac) {
        var texto = mac.strip().replace(":", "").replace("-", "").toLowerCase(Locale.ROOT);
        if (!texto.matches("[0-9a-f]{12}") || texto.equals("000000000000") || texto.equals("ffffffffffff")) {
            throw new IllegalArgumentException("MAC_ADDRESS inválido.");
        }
        return HexFormat.ofDelimiter(":").formatHex(HexFormat.of().parseHex(texto));
    }
}
