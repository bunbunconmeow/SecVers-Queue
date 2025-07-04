package org.secverse.queueSystem.DB;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * DBManager for managing MySQL connections using MySQL Connector/J 9.3.0
 */
public class DBManager {
    private static final String DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";
    private static final String URL_TEMPLATE = "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    private final Logger logger;
    private final String url;
    private final String username;
    private final String password;
    private Connection connection;

    public DBManager(Logger logger, String host, int port, String database, String username, String password) {
        this.logger = logger;
        this.url = String.format(URL_TEMPLATE, host, port, database);
        this.username = username;
        this.password = password;
    }

    /**
     * Loads the JDBC driver and establishes the database connection.
     */
    public void connect() {
        try {
            Class.forName(DRIVER_CLASS);
            connection = DriverManager.getConnection(url, username, password);
            logger.info("[SecVers DBManager] Connected to database: " + url);
        } catch (ClassNotFoundException e) {
            logger.severe("[SecVers DBManager] JDBC driver not found: " + e.getMessage());
        } catch (SQLException e) {
            logger.severe("[SecVers DBManager] Connection failed: " + e.getMessage());
        }
    }


    private void ensureConnection() {
        try {
            if (connection == null || connection.isClosed() || !connection.isValid(2)) {
                logger.warning("[SecVers DBManager] Connection lost. Reconnecting...");
                connect();
            }
        } catch (SQLException e) {
            logger.warning("[SecVers DBManager] Error checking connection validity: " + e.getMessage());
            connect();
        }
    }

    /**
     * Closes the database connection.
     */
    public void disconnect() {
        if (connection != null) {
            try {
                connection.close();
                logger.info("[SecVers DBManager] Database connection closed.");
            } catch (SQLException e) {
                logger.warning("[SecVers DBManager] Error closing database connection: " + e.getMessage());
            }
        }
    }

    /**
     * Retrieves the primary group of a player from LuckPerms.
     * @param uuid Player UUID
     * @return primary_group or "default" if not found
     */
    public String getPrimaryGroup(UUID uuid) {
        ensureConnection();

        if (connection == null) {
            logger.warning("[SecVers DBManager] No database connection available, defaulting to 'default' group.");
            return "default";
        }
        String sql = "SELECT permission FROM luckperms_user_permissions WHERE uuid = ?";
        String uid = uuid.toString().replace("-", "");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("permission");
                }
            }
        } catch (SQLException e) {
            logger.warning("[SecVers DBManager] Error querying permission: " + e.getMessage());
        }
        return "default";
    }

    /**
     * Checks if a player is in a specific group.
     * @param uuid Player UUID
     * @param groupName Name of the group to check
     * @return true if the player belongs to the specified group
     */
    public boolean isPlayerInGroup(UUID uuid, String groupName) {
        ensureConnection();

        if (connection == null) {
            logger.warning("[DBManager] No database connection available, isPlayerInGroup returning false.");
            return false;
        }
        String sql = "SELECT 1 FROM luckperms_user_permissions WHERE uuid = ? AND permission = ? LIMIT 1";

        String uid = uuid.toString();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uid);
            ps.setString(2, groupName);
            logger.info(ps.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.warning("[DBManager] Error querying isPlayerInGroup: " + e.getMessage());
        }
        return false;
    }
}
