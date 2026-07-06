package com.opal.deltav;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class DbConnectionFactory {

    private static final String CONNECTION_SETTING = "APPDB_CONNECTION_STRING";

    private DbConnectionFactory() {}

    public static Connection open() throws SQLException {
        String url = System.getenv(CONNECTION_SETTING);
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(CONNECTION_SETTING + " is not configured");
        }
        return DriverManager.getConnection(url);
    }
}
