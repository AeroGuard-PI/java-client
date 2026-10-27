package br.com.monitoramento;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class Conexao {
    public static Connection abrir() throws SQLException {
        var host = System.getenv().getOrDefault("DB_HOST", "localhost");
        var porta = System.getenv().getOrDefault("DB_PORT", "3306");
        var url = "jdbc:mysql://" + host + ":" + porta + "/AeroGuard";
        var dados = new Properties();
        dados.setProperty("user", variavel("DB_USER"));
        dados.setProperty("password", variavel("DB_PASSWORD"));
        dados.setProperty("connectTimeout", "3000");
        dados.setProperty("socketTimeout", "5000");
        dados.setProperty("rewriteBatchedStatements", "true");
        dados.setProperty("connectionTimeZone", "UTC");
        dados.setProperty("forceConnectionTimeZoneToSession", "true");
        return DriverManager.getConnection(url, dados);
    }

    public static String variavel(String nome) {
        var valor = System.getenv(nome);
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("Preencha a variável " + nome + ".");
        }
        return valor;
    }
}
