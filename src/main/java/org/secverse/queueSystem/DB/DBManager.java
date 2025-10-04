package org.secverse.queueSystem.DB;

import java.net.InetAddress;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;

public class DBManager {
    private static final String DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";
    private static final String URL_TEMPLATE = "jdbc:mysql://%s:%d/%s?useSSL=%b&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    private final Logger logger;
    private final String url;
    private final String username;
    private final String password;
    private Connection connection;

    public DBManager(Logger logger, String host, int port, String database, boolean isSSL, String username, String password) {
        this.logger = logger;
        this.url = String.format(URL_TEMPLATE, host, port, database, isSSL);
        this.username = username;
        this.password = password;
    }


    public void connect() {
        try {
            Class.forName(DRIVER_CLASS);
            connection = DriverManager.getConnection(url, username, password);
            logger.info("[Queue DBManager] Connected to database: " + url);

            initializeSkipSchema();
        } catch (ClassNotFoundException e) {
            logger.severe("[Queue DBManager] JDBC driver not found: " + e.getMessage());
        } catch (SQLException e) {
            logger.severe("[Queue DBManager] Connection failed: " + e.getMessage());
        }
    }

    private void ensureConnection() {
        try {
            if (connection == null || connection.isClosed() || !connection.isValid(2)) {
                logger.warning("[Queue DBManager] Connection lost. Reconnecting...");
                connection = DriverManager.getConnection(url, username, password);
                // Re-run schema init in case we reconnected to a different node/schema.
                initializeSkipSchema();
            }
        } catch (SQLException e) {
            logger.warning("[Queue DBManager] Error ensuring connection: " + e.getMessage());
        }
    }


    public void disconnect() {
        if (connection != null) {
            try {
                connection.close();
                logger.info("[Queue DBManager] Database connection closed.");
            } catch (SQLException e) {
                logger.warning("[Queue DBManager] Error closing database connection: " + e.getMessage());
            }
        }
    }

    public String getPrimaryGroup(UUID uuid) {
        ensureConnection();
        if (connection == null) {
            logger.warning("[Queue DBManager] No database connection available, defaulting to 'default' group.");
            return "default";
        }

        String sql = "SELECT permission FROM luckperms_user_permissions WHERE uuid = ? LIMIT 1";
        String uid = uuid.toString().replace("-", "");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("permission");
                }
            }
        } catch (SQLException e) {
            logger.warning("[Queue DBManager] Error querying permission: " + e.getMessage());
        }
        return "default";
    }

    public boolean isPlayerInGroup(UUID uuid, String groupName) {
        ensureConnection();
        if (connection == null) {
            logger.warning("[DBManager] No database connection available, isPlayerInGroup returning false.");
            return false;
        }

        String sql = "SELECT 1 FROM luckperms_user_permissions WHERE uuid = ? AND permission = ? LIMIT 1";
        String uid = uuid.toString().replace("-", "");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uid);
            ps.setString(2, groupName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.warning("[DBManager] Error querying isPlayerInGroup: " + e.getMessage());
        }
        return false;
    }

    /* =========================================================
       Skip-Feature schema bootstrap (queueskip_tokens)
       ========================================================= */

    /**
     * Creates the queueskip_tokens table if it does not exist.
     * The schema is minimal, indexed for expiry checks and one-time consumption.
     */
    private void initializeSkipSchema() {
        ensureConnection();
        if (connection == null) return;

        final String ddl =
                "CREATE TABLE IF NOT EXISTS queueskip_tokens (\n" +
                        "  code         VARCHAR(64)  NOT NULL PRIMARY KEY,\n" +
                        "  issued_at    DATETIME(6)  NOT NULL,\n" +
                        "  expires_at   DATETIME(6)  NOT NULL,\n" +
                        "  consumed     TINYINT(1)   NOT NULL DEFAULT 0,\n" +
                        "  consumed_by  CHAR(36)     NULL,\n" +
                        "  consumed_at  DATETIME(6)  NULL,\n" +
                        "  created_by   VARCHAR(64)  NULL,\n" +
                        "  creator_ip   VARBINARY(16) NULL,\n" +
                        "  INDEX idx_expires (expires_at),\n" +
                        "  INDEX idx_consumed_expires (consumed, expires_at)\n" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;";

        try (Statement st = connection.createStatement()) {
            st.execute(ddl);
            logger.info("[Queue DBManager] Verified table 'queueskip_tokens'.");
        } catch (SQLException e) {
            logger.warning("[Queue DBManager] Failed to create 'queueskip_tokens': " + e.getMessage());
        }
    }

    public boolean insertSkipToken(String code, Instant issuedAt, Instant expiresAt, String createdBy, InetAddress creatorIp) {
        ensureConnection();
        if (connection == null) return false;

        String sql = "INSERT INTO queueskip_tokens (code, issued_at, expires_at, consumed, consumed_by, consumed_at, created_by, creator_ip) " +
                "VALUES (?, ?, ?, 0, NULL, NULL, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.setTimestamp(2, Timestamp.from(issuedAt));
            ps.setTimestamp(3, Timestamp.from(expiresAt));
            ps.setString(4, createdBy);
            if (creatorIp != null) ps.setBytes(5, creatorIp.getAddress());
            else ps.setNull(5, Types.BINARY);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            logger.warning("[Queue DBManager] insertSkipToken failed: " + e.getMessage());
            return false;
        }
    }

    public boolean isSkipTokenValid(String code) {
        ensureConnection();
        if (connection == null) return false;

        String sql = "SELECT consumed, expires_at FROM queueskip_tokens WHERE code = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                boolean consumed = rs.getBoolean(1);
                Instant expires = rs.getTimestamp(2).toInstant();
                return !consumed && Instant.now().isBefore(expires);
            }
        } catch (SQLException e) {
            logger.warning("[Queue DBManager] isSkipTokenValid failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Atomically consumes a token for the given player if valid.
     */
    public boolean consumeSkipToken(String code, UUID playerId) {
        ensureConnection();
        if (connection == null) return false;

        String select = "SELECT consumed, expires_at FROM queueskip_tokens WHERE code = ? FOR UPDATE";
        String update = "UPDATE queueskip_tokens SET consumed = 1, consumed_by = ?, consumed_at = ? WHERE code = ?";
        try {
            boolean originalAuto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(select)) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        connection.rollback();
                        connection.setAutoCommit(originalAuto);
                        return false;
                    }
                    boolean consumed = rs.getBoolean(1);
                    Instant expires = rs.getTimestamp(2).toInstant();
                    if (consumed || Instant.now().isAfter(expires)) {
                        connection.rollback();
                        connection.setAutoCommit(originalAuto);
                        return false;
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(update)) {
                ps.setString(1, playerId.toString());
                ps.setTimestamp(2, Timestamp.from(Instant.now()));
                ps.setString(3, code);
                int n = ps.executeUpdate();
                if (n != 1) {
                    connection.rollback();
                    connection.setAutoCommit(true);
                    return false;
                }
            }
            connection.commit();
            connection.setAutoCommit(true);
            return true;
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            logger.warning("[Queue DBManager] consumeSkipToken failed: " + e.getMessage());
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
            return false;
        }
    }
}
