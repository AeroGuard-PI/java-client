package br.com.monitoramento;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class IntervaloRedeTest {
    private LeituraRede leitura(long segundos, long bytes, Long erros) {
        return new LeituraRede("eth0", "02:42:ac:71:00:03", bytes, bytes / 2,
                segundos * 1_000_000_000L, Instant.parse("2026-11-27T12:00:00Z").plusSeconds(segundos),
                erros, 0L, null, "UP");
    }

    @Test
    void calculaTaxaComDuracaoRealEPreservaDeltas() {
        var intervalo = IntervaloRede.entre(leitura(0, 100, 0L), leitura(8, 8_000_100, 3L));
        assertEquals(8_000_000L, intervalo.recebidos());
        assertEquals(8_000_000_000L, intervalo.duracaoNanos());
        assertEquals(8.0, intervalo.metricas().getFirst().valor());
        assertEquals(3L, intervalo.errosRecebidos());
        assertNull(intervalo.descartesRecebidos());
    }

    @Test
    void primeiraLeituraPreservaEstadoSemInventarTaxa() {
        var intervalo = IntervaloRede.entre(null, leitura(0, 100, 0L));
        assertEquals("UP", intervalo.estadoOperacional());
        assertEquals("PRIMEIRA_LEITURA", intervalo.motivo());
        assertNull(intervalo.duracaoNanos());
        assertTrue(intervalo.metricas().isEmpty());
    }

    @Test
    void reinicioDeBytesInvalidaIntervaloMasReinicioDeErroNaoCriaNegativos() {
        var reiniciado = IntervaloRede.entre(leitura(0, 1000, 10L), leitura(5, 100, 0L));
        assertEquals("CONTADOR_REINICIADO", reiniciado.motivo());
        assertTrue(reiniciado.metricas().isEmpty());
        var parcial = IntervaloRede.entre(leitura(0, 100, 10L), leitura(5, 1000, 0L));
        assertNull(parcial.errosRecebidos());
        assertEquals(2, parcial.metricas().size());
    }

    @Test
    void saltoDoRelogioNaoAtribuiIntervaloAJanelaErrada() {
        var inicial = leitura(0, 100, 0L);
        var finalOriginal = leitura(5, 1000, 0L);
        var finalComSalto = new LeituraRede("eth0", inicial.macAddress(), 1000, 500,
                finalOriginal.tempoNanos(), inicial.dataHora().plusSeconds(30), 0L, 0L, 0L, "UP");
        assertEquals("RELOGIO_ALTERADO", IntervaloRede.entre(inicial, finalComSalto).motivo());
    }
}
