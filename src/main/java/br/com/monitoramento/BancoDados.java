package br.com.monitoramento;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

public class BancoDados {
    public int salvar(Maquina maquina, String cnpj, List<MetricaRede> metricas) throws SQLException {
        try (var conexao = Conexao.abrir()) {
            conexao.setAutoCommit(false);
            try {
                int empresaId = buscarEmpresa(conexao, cnpj);
                int maquinaId = buscarOuCadastrarMaquina(conexao, maquina, empresaId);
                int componenteId = buscarRede(conexao);
                int configuracaoId = buscarConfiguracao(conexao, maquinaId, componenteId);
                int quantidade = 0;
                if (configuracaoId != 0) {
                    quantidade = inserirCapturas(conexao, maquinaId, componenteId, configuracaoId, metricas);
                }
                conexao.commit();
                return quantidade;
            } catch (SQLException | RuntimeException erro) {
                conexao.rollback();
                throw erro;
            }
        }
    }

    private int buscarEmpresa(Connection conexao, String cnpj) throws SQLException {
        var sql = "SELECT id FROM empresa WHERE cnpj = ?";
        try (var consulta = preparar(conexao, sql)) {
            consulta.setString(1, cnpj);
            try (var resultado = consulta.executeQuery()) {
                if (!resultado.next()) {
                    throw new SQLException("Empresa não encontrada. Confira EMPRESA_CNPJ.");
                }
                return resultado.getInt("id");
            }
        }
    }

    private int buscarOuCadastrarMaquina(Connection conexao, Maquina maquina, int empresaId) throws SQLException {
        int id = buscarMaquina(conexao, maquina.macAddress(), empresaId);
        if (id == 0) {
            var sql = """
                    INSERT INTO maquina (hostname, so, macAddress, fkEmpresa)
                    VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE hostname = hostname
                    """;
            try (var inserir = preparar(conexao, sql)) {
                inserir.setString(1, maquina.hostname());
                inserir.setString(2, maquina.sistema());
                inserir.setString(3, maquina.macAddress());
                inserir.setInt(4, empresaId);
                inserir.executeUpdate();
            }
            id = buscarMaquina(conexao, maquina.macAddress(), empresaId);
            if (id == 0) {
                throw new SQLException("Não foi possível identificar a máquina cadastrada.");
            }
        }
        try (var atualizar = preparar(conexao, "UPDATE maquina SET hostname = ?, so = ? WHERE id = ?")) {
            atualizar.setString(1, maquina.hostname());
            atualizar.setString(2, maquina.sistema());
            atualizar.setInt(3, id);
            atualizar.executeUpdate();
        }
        return id;
    }

    private int buscarMaquina(Connection conexao, String mac, int empresaId) throws SQLException {
        var sql = """
                SELECT id FROM maquina
                WHERE fkEmpresa = ?
                    AND LOWER(REPLACE(REPLACE(TRIM(macAddress), ':', ''), '-', '')) = ?
                FOR UPDATE
                """;
        try (var consulta = preparar(conexao, sql)) {
            consulta.setInt(1, empresaId);
            consulta.setString(2, mac.replace(":", ""));
            try (var resultado = consulta.executeQuery()) {
                if (!resultado.next()) {
                    return 0;
                }
                int id = resultado.getInt("id");
                if (resultado.next()) {
                    throw new SQLException("Há mais de um cadastro para o mesmo MAC. Corrija o banco.");
                }
                return id;
            }
        }
    }

    private int buscarRede(Connection conexao) throws SQLException {
        try (var consulta = preparar(conexao, "SELECT id FROM componentes WHERE nome = ?")) {
            consulta.setString(1, "Rede");
            try (var resultado = consulta.executeQuery()) {
                if (!resultado.next()) {
                    throw new SQLException("Cadastre o componente Rede no banco.");
                }
                int id = resultado.getInt("id");
                if (resultado.next()) {
                    throw new SQLException("Existe mais de um componente chamado Rede.");
                }
                return id;
            }
        }
    }

    private int buscarConfiguracao(Connection conexao, int maquinaId, int componenteId) throws SQLException {
        var sql = """
                SELECT id, disponibilidade FROM configuracao_maquina
                WHERE maquina_captura_id = ? AND componentes_id = ?
                ORDER BY data_hora DESC, id DESC LIMIT 1 FOR UPDATE
                """;
        try (var consulta = preparar(conexao, sql)) {
            consulta.setInt(1, maquinaId);
            consulta.setInt(2, componenteId);
            try (var resultado = consulta.executeQuery()) {
                if (resultado.next()) {
                    return resultado.getInt("disponibilidade") == 1 ? resultado.getInt("id") : 0;
                }
            }
        }
        var inserirSql = """
                INSERT INTO configuracao_maquina (disponibilidade, maquina_captura_id, componentes_id)
                VALUES (1, ?, ?)
                """;
        try (var inserir = conexao.prepareStatement(inserirSql, Statement.RETURN_GENERATED_KEYS)) {
            inserir.setQueryTimeout(3);
            inserir.setInt(1, maquinaId);
            inserir.setInt(2, componenteId);
            inserir.executeUpdate();
            try (var chaves = inserir.getGeneratedKeys()) {
                if (chaves.next()) {
                    return chaves.getInt(1);
                }
                throw new SQLException("Não foi possível obter o ID da configuração.");
            }
        }
    }

    private int inserirCapturas(Connection conexao, int maquinaId, int componenteId,
                                int configuracaoId, List<MetricaRede> metricas) throws SQLException {
        if (metricas.isEmpty()) {
            return 0;
        }
        var sql = """
                INSERT INTO captura (tipo, valor, data_hora, unidade_medida, maquina_id,
                    componentes_id, configuracao_maquina_maquina_id, configuracao_maquina_componentes_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (var inserir = preparar(conexao, sql)) {
            for (var metrica : metricas) {
                inserir.setString(1, metrica.tipo());
                inserir.setDouble(2, metrica.valor());
                inserir.setTimestamp(3, Timestamp.from(metrica.dataHora()));
                inserir.setString(4, metrica.unidade());
                inserir.setInt(5, maquinaId);
                inserir.setInt(6, componenteId);
                inserir.setInt(7, configuracaoId);
                inserir.setInt(8, configuracaoId);
                inserir.addBatch();
            }
            var resultados = inserir.executeBatch();
            for (int resultado : resultados) {
                if (resultado != 1 && resultado != Statement.SUCCESS_NO_INFO) {
                    throw new SQLException("Uma captura não foi gravada.");
                }
            }
        }
        return metricas.size();
    }

    private PreparedStatement preparar(Connection conexao, String sql) throws SQLException {
        var comando = conexao.prepareStatement(sql);
        comando.setQueryTimeout(3);
        return comando;
    }
}
