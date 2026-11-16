package br.com.monitoramento;

import oshi.SystemInfo;

import java.time.Instant;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
                var hostname = so.getNetworkParams().getHostName();
                return new Maquina(hostname.substring(0, Math.min(50, hostname.length())), sistemaOperacional(), mac);
            }
        }
        throw new IllegalArgumentException("O MAC configurado não foi encontrado nesta máquina.");
    }

    public synchronized List<MetricaRede> coletar() {
        return coletarDetalhado().metricas();
    }

    public synchronized ColetaRede coletarDetalhado() {
        var metricas = new ArrayList<MetricaRede>();
        var intervalos = new ArrayList<IntervaloRede>();
        var encontradas = new HashSet<String>();
        try {
            for (var rede : sistema.getHardware().getNetworkIFs()) {
                try {
                    if (!rede.updateAttributes()) {
                        continue;
                    }
                    boolean linux = System.getProperty("os.name").startsWith("Linux");
                    Long recebidos = rede.getBytesRecv();
                    Long enviados = rede.getBytesSent();
                    if (linux) {
                        recebidos = lerContador(rede.getName(), "rx_bytes");
                        enviados = lerContador(rede.getName(), "tx_bytes");
                    }
                    if (recebidos == null || enviados == null || recebidos < 0 || enviados < 0) {
                        continue;
                    }
                    var atual = new LeituraRede(rede.getName(), rede.getMacaddr(),
                            recebidos, enviados, System.nanoTime(), Instant.now(),
                            lerContador(rede.getName(), "rx_errors"), lerContador(rede.getName(), "tx_errors"),
                            lerContador(rede.getName(), "rx_dropped"),
                            rede.getIfOperStatus().name());
                    if (atual.interfaceNome().length() > 49) {
                        throw new IllegalArgumentException("Nome da interface excede o limite de captura.tipo.");
                    }
                    encontradas.add(atual.interfaceNome());
                    var anterior = anteriores.put(atual.interfaceNome(), atual);
                    var intervalo = IntervaloRede.entre(anterior, atual);
                    intervalos.add(intervalo);
                    metricas.addAll(intervalo.metricas());
                } catch (RuntimeException erro) {
                    System.err.println("Não foi possível ler " + rede.getName() + ".");
                }
            }
        } catch (RuntimeException | LinkageError erro) {
            anteriores.clear();
            throw erro;
        }
        anteriores.keySet().retainAll(encontradas);
        return new ColetaRede(List.copyOf(metricas), List.copyOf(intervalos));
    }

    private static Long lerContador(String interfaceNome, String contador) {
        try {
            var caminho = Path.of("/sys/class/net", interfaceNome, "statistics", contador);
            long valor = Long.parseUnsignedLong(Files.readString(caminho).strip());
            return valor >= 0 ? valor : null;
        } catch (IOException | NumberFormatException erro) {
            return null;
        }
    }

    public static String normalizarMac(String mac) {
        var texto = mac.strip().replace(":", "").replace("-", "").toLowerCase(Locale.ROOT);
        if (!texto.matches("[0-9a-f]{12}") || texto.equals("000000000000") || texto.equals("ffffffffffff")) {
            throw new IllegalArgumentException("MAC_ADDRESS inválido.");
        }
        return HexFormat.ofDelimiter(":").formatHex(HexFormat.of().parseHex(texto));
    }

    private String sistemaOperacional() {
        var arquivo = Path.of(System.getenv().getOrDefault("HOST_ROOT", "/"), "etc", "os-release");
        if (Files.isRegularFile(arquivo)) {
            try (var linhas = Files.lines(arquivo)) {
                var nome = linhas.filter(linha -> linha.startsWith("PRETTY_NAME="))
                        .map(linha -> linha.substring("PRETTY_NAME=".length()).replace("\"", ""))
                        .findFirst().orElse("Linux");
                return nome.substring(0, Math.min(50, nome.length()));
            } catch (IOException erro) {
                throw new IllegalStateException("Não foi possível ler o sistema operacional da EC2.", erro);
            }
        }
        return sistema.getOperatingSystem().getFamily();
    }
}
