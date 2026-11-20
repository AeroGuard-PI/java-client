package br.com.monitoramento;

import br.com.monitoramento.NotificacaoRede.Nivel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "AEROGUARD_TEST_DB", matches = "true")
class BancoDadosIntegrationTest {
    private static final String CNPJ_A = "11111111000191";
    private static final String CNPJ_B = "22222222000191";
    private static final String MAC = "02:00:00:00:00:01";
    private static boolean fixturesCriadas;
    private final BancoDados banco = new BancoDados();
    private final Maquina maquina = new Maquina("rede-teste", "Linux", MAC);

    @BeforeAll
    static void preparar() throws SQLException {
        try (var conexao = Conexao.abrir()) {
            conexao.setAutoCommit(false);
            try (var comando = conexao.createStatement()) {
                comando.executeUpdate("INSERT INTO localizacao VALUES (1001, 'SP', 'Teste A', '00000000', '1'), "
                        + "(1002, 'SP', 'Teste B', '00000000', '2')");
                comando.executeUpdate("INSERT INTO empresa VALUES (1001, '" + CNPJ_A + "', 'Teste A', 'TSA', 'A0001', 1001), "
                        + "(1002, '" + CNPJ_B + "', 'Teste B', 'TSB', 'B0001', 1002)");
                comando.executeUpdate("INSERT INTO maquina VALUES (1001, 'Teste A', 'Linux', '" + MAC + "', 1001), "
                        + "(1002, 'Teste B', 'Linux', '" + MAC + "', 1002)");
                comando.executeUpdate("INSERT INTO componentes VALUES (1001, 'Rede'), (1002, 'CPU')");
                comando.executeUpdate("INSERT INTO configuracao_maquina (id, disponibilidade, maquina_captura_id, componentes_id) "
                        + "VALUES (1001, 1, 1001, 1001), (1002, 1, 1002, 1001), (1003, 1, 1001, 1002)");
                comando.executeUpdate("INSERT INTO discretizacao (id, empresa_id, config_maquina_id, limite, nivel_alerta) "
                        + "VALUES (1001, 1001, 1001, 80, 'ALERTA'), (1002, 1001, 1001, 90, 'CRÍTICO'), "
                        + "(1003, 1002, 1002, 80, 'ALERTA'), (1004, 1002, 1002, 90, 'CRÍTICO'), "
                        + "(1005, 1001, 1003, 80, 'ALERTA'), (1006, 1001, 1003, 90, 'CRÍTICO')");
                conexao.commit();
                fixturesCriadas = true;
            } catch (SQLException erro) {
                conexao.rollback();
                throw erro;
            }
        }
    }

    @BeforeEach
    void limparCapturas() throws SQLException {
        limparDados();
        executar("UPDATE configuracao_maquina SET disponibilidade = 1 WHERE maquina_captura_id IN (1001, 1002)");
        executar("UPDATE discretizacao d JOIN configuracao_maquina cm ON cm.id = d.config_maquina_id "
                + "JOIN maquina m ON m.id = cm.maquina_captura_id SET d.empresa_id = m.fkEmpresa "
                + "WHERE d.id BETWEEN 1001 AND 1006");
    }

    @AfterAll
    static void encerrar() throws SQLException {
        if (!fixturesCriadas) {
            return;
        }
        limparDados();
        executar("DELETE FROM discretizacao WHERE id BETWEEN 1001 AND 1006");
        executar("DELETE FROM configuracao_maquina WHERE maquina_captura_id IN (1001, 1002)");
        executar("DELETE FROM maquina WHERE id IN (1001, 1002)");
        executar("DELETE FROM componentes WHERE id IN (1001, 1002)");
        executar("DELETE FROM empresa WHERE id IN (1001, 1002)");
        executar("DELETE FROM localizacao WHERE id IN (1001, 1002)");
    }

    @Test
    void leConfiguracaoExclusivamenteDaEmpresa() throws SQLException {
        assertNull(banco.buscarConfiguracaoSlack(CNPJ_A));
        executar("INSERT INTO configuracao_slack (empresa_id, ativo, canal, modo, nivel_minimo, cooldown_min) "
                + "VALUES (1001, 1, 'CA', 'CONFIGURADO', 'CRITICO', 90), "
                + "(1002, 0, 'CB', 'TUDO', 'ALERTA', 0)");
        var primeira = banco.buscarConfiguracaoSlack(CNPJ_A);
        assertEquals(new ConfiguracaoSlack(true, "CA", ConfiguracaoSlack.Modo.CONFIGURADO,
                Nivel.CRITICO, 90), primeira);
        assertEquals("CB", banco.buscarConfiguracaoSlack(CNPJ_B).canal());
        assertFalse(banco.buscarConfiguracaoSlack(CNPJ_B).ativo());
        assertNull(banco.buscarConfiguracaoSlack("33333333000191"));
    }

    @Test
    void configuracaoIncompletaNaoGeraFallback() throws SQLException {
        executar("INSERT INTO configuracao_slack (empresa_id, canal) VALUES (1001, '')");
        assertThrows(IllegalArgumentException.class, () -> banco.buscarConfiguracaoSlack(CNPJ_A));
        executar("UPDATE configuracao_slack SET canal = 'CA', cooldown_min = 1441 WHERE empresa_id = 1001");
        assertThrows(IllegalArgumentException.class, () -> banco.buscarConfiguracaoSlack(CNPJ_A));
    }

    @Test
    void alertasSaoIsoladosPorEmpresaMaquinaComponenteEInicio() throws SQLException {
        var inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(1);
        var metrica = new MetricaRede("Download - eth0", 200, "Mbps", Instant.now());
        assertEquals(1, banco.salvar(maquina, CNPJ_A, List.of(metrica)));
        assertEquals(1, banco.salvar(maquina, CNPJ_B, List.of(metrica)));
        executar("INSERT INTO historico_alerta (maquina_captura_id, componentes_captura_id, discretizacao_id) "
                + "SELECT id, id, CASE WHEN maquina_id = 1001 THEN 1002 ELSE 1004 END FROM captura WHERE maquina_id IN (1001, 1002)");
        executar("INSERT INTO captura (tipo, valor, unidade_medida, maquina_id, componentes_id, "
                + "configuracao_maquina_maquina_id, configuracao_maquina_componentes_id) "
                + "VALUES ('Uso CPU', 95, '%', 1001, 1002, 1003, 1003)");
        executar("INSERT INTO historico_alerta (maquina_captura_id, componentes_captura_id, discretizacao_id) "
                + "SELECT id, id, 1006 FROM captura WHERE componentes_id = 1002");
        var alertas = banco.buscarAlertasRede(CNPJ_A, MAC, inicio);
        assertEquals(1, alertas.size());
        assertEquals(Nivel.CRITICO, alertas.getFirst().nivel());
        assertEquals("Download - eth0", alertas.getFirst().metrica().tipo());
        assertEquals(200, alertas.getFirst().metrica().valor());
        assertTrue(banco.buscarAlertasRede(CNPJ_A, "02:00:00:00:00:ff", inicio).isEmpty());
        assertTrue(banco.buscarAlertasRede(CNPJ_A, MAC, inicio.plusSeconds(60)).isEmpty());
    }

    @Test
    void ignoraDiscretizacaoDeOutraMaquinaOuComponente() throws SQLException {
        var inicio = Instant.now().minusSeconds(1);
        banco.salvar(maquina, CNPJ_A,
                List.of(new MetricaRede("Upload - eth0", 20, "Mbps", Instant.now())));
        executar("INSERT INTO historico_alerta (maquina_captura_id, componentes_captura_id, discretizacao_id) "
                + "SELECT c.id, c.id, d.id FROM captura c CROSS JOIN discretizacao d "
                + "WHERE c.maquina_id = 1001 AND d.id IN (1002, 1004, 1006)");
        var alertas = banco.buscarAlertasRede(CNPJ_A, MAC, inicio);
        assertEquals(1, alertas.size());
        assertEquals(Nivel.CRITICO, alertas.getFirst().nivel());
    }

    @Test
    void ignoraEmpresaDivergenteNaDiscretizacao() throws SQLException {
        var inicio = Instant.now().minusSeconds(1);
        banco.salvar(maquina, CNPJ_A,
                List.of(new MetricaRede("Upload - eth0", 20, "Mbps", Instant.now())));
        executar("UPDATE discretizacao SET empresa_id = 1002 WHERE id = 1002");
        executar("INSERT INTO historico_alerta (maquina_captura_id, componentes_captura_id, discretizacao_id) "
                + "SELECT id, id, 1002 FROM captura WHERE maquina_id = 1001");
        assertTrue(banco.buscarAlertasRede(CNPJ_A, MAC, inicio).isEmpty());
    }

    @Test
    void desativacaoDeColetaPreservaConfiguracaoSemInserir() throws SQLException {
        executar("UPDATE configuracao_maquina SET disponibilidade = 0 WHERE id = 1001");
        var metrica = new MetricaRede("Upload - eth0", 20, "Mbps", Instant.now());
        assertEquals(0, banco.salvar(maquina, CNPJ_A, List.of(metrica)));
        assertEquals(0, contarCapturas());
    }

    @Test
    void falhaNoLoteReverteAsCapturas() throws SQLException {
        var valida = new MetricaRede("Download - eth0", 20, "Mbps", Instant.now());
        var invalida = new MetricaRede("x".repeat(61), 30, "Mbps", Instant.now());
        assertThrows(SQLException.class, () -> banco.salvar(maquina, CNPJ_A, List.of(valida, invalida)));
        assertEquals(0, contarCapturas());
    }

    private static int contarCapturas() throws SQLException {
        try (var conexao = Conexao.abrir();
             var consulta = conexao.prepareStatement("SELECT COUNT(*) FROM captura WHERE maquina_id = 1001");
             var resultado = consulta.executeQuery()) {
            resultado.next();
            return resultado.getInt(1);
        }
    }

    private static void limparDados() throws SQLException {
        executar("DELETE h FROM historico_alerta h JOIN captura c ON c.id = h.maquina_captura_id "
                + "WHERE c.maquina_id IN (1001, 1002)");
        executar("DELETE FROM captura WHERE maquina_id IN (1001, 1002)");
        executar("DELETE FROM configuracao_slack WHERE empresa_id IN (1001, 1002)");
    }

    private static void executar(String sql) throws SQLException {
        try (var conexao = Conexao.abrir(); var comando = conexao.createStatement()) {
            comando.executeUpdate(sql);
        }
    }
}
