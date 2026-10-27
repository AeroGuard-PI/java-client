package br.com.monitoramento;

import java.time.Instant;

public record LeituraRede(String interfaceNome, String macAddress, long recebidos,
                         long enviados, long tempoNanos, Instant dataHora) {
}
