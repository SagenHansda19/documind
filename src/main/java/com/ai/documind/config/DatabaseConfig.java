package com.ai.documind.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    @Value("${spring.datasource.url:${DATABASE_URL:}}")
    private String datasourceUrl;

    @Value("${spring.datasource.username:${DB_USER:${DB_USERNAME:postgres}}}")
    private String dbUser;

    @Value("${spring.datasource.password:${DB_PASSWORD:}}")
    private String dbPassword;

    @Bean
    @Primary
    public DataSource dataSource() {
        ParsedDb db = resolveDatabaseConfig(datasourceUrl, dbUser, dbPassword);
        log.info("Initializing DataSource for host/database at: {}", db.safeLogUrl);

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setJdbcUrl(db.jdbcUrl);
        if (db.user != null && !db.user.isBlank()) {
            dataSource.setUsername(db.user);
        }
        if (db.password != null && !db.password.isBlank()) {
            dataSource.setPassword(db.password);
        }

        dataSource.setMaximumPoolSize(10);
        dataSource.setMinimumIdle(2);
        dataSource.setIdleTimeout(30000);
        dataSource.setPoolName("DocuMind-HikariPool");
        return dataSource;
    }

    private ParsedDb resolveDatabaseConfig(String rawUrl, String defaultUser, String defaultPassword) {
        String u = rawUrl;
        if (u == null || u.isBlank()) {
            u = System.getenv("DATABASE_URL");
        }
        if (u == null || u.isBlank()) {
            u = System.getenv("SPRING_DATASOURCE_URL");
        }
        if (u == null || u.isBlank()) {
            u = "jdbc:postgresql://localhost:5432/documind_db";
        }

        ParsedDb res = new ParsedDb();
        res.user = (defaultUser != null && !defaultUser.isBlank() && !"postgres".equalsIgnoreCase(defaultUser))
                ? defaultUser
                : System.getenv("DB_USER");
        if (res.user == null || res.user.isBlank()) {
            res.user = System.getenv("DB_USERNAME");
        }
        res.password = (defaultPassword != null && !defaultPassword.isBlank())
                ? defaultPassword
                : System.getenv("DB_PASSWORD");

        String working = u.trim();
        if (working.startsWith("jdbc:")) {
            working = working.substring(5);
        }

        int protoIdx = working.indexOf("://");
        if (protoIdx != -1) {
            working = working.substring(protoIdx + 3);
        }

        // Handle credentials embedded in URL: user:password@host:port/db
        // Extract using lastIndexOf('@') to safely preserve special characters in passwords (e.g. @, #, %)
        int lastAt = working.lastIndexOf('@');
        String hostPart;
        if (lastAt != -1) {
            String userInfo = working.substring(0, lastAt);
            hostPart = working.substring(lastAt + 1);
            int colonIdx = userInfo.indexOf(':');
            if (colonIdx != -1) {
                if (res.user == null || res.user.isBlank() || "postgres".equalsIgnoreCase(res.user)) {
                    res.user = userInfo.substring(0, colonIdx);
                }
                if (res.password == null || res.password.isBlank()) {
                    res.password = userInfo.substring(colonIdx + 1);
                }
            } else if (res.user == null || res.user.isBlank()) {
                res.user = userInfo;
            }
        } else {
            hostPart = working;
        }

        String hostPort;
        String dbPart;
        int slashIdx = hostPart.indexOf('/');
        if (slashIdx != -1) {
            hostPort = hostPart.substring(0, slashIdx);
            dbPart = hostPart.substring(slashIdx + 1);
        } else {
            hostPort = hostPart;
            dbPart = "postgres";
        }

        String db = dbPart;
        String query = "";
        int qIdx = dbPart.indexOf('?');
        if (qIdx != -1) {
            db = dbPart.substring(0, qIdx);
            query = dbPart.substring(qIdx + 1);
        }

        boolean isLocal = hostPort.startsWith("localhost") || hostPort.startsWith("127.0.0.1");
        if (!isLocal && !query.contains("sslmode=")) {
            query = query.isBlank() ? "sslmode=require" : query + "&sslmode=require";
        }

        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(hostPort).append("/").append(db);
        if (!query.isBlank()) {
            jdbc.append("?").append(query);
        }

        res.jdbcUrl = jdbc.toString();
        res.safeLogUrl = "jdbc:postgresql://" + hostPort + "/" + db + (query.isBlank() ? "" : "?" + query);
        if (res.user == null || res.user.isBlank()) {
            res.user = "postgres";
        }
        return res;
    }

    private static class ParsedDb {
        String jdbcUrl;
        String user;
        String password;
        String safeLogUrl;
    }
}
