package com.opal.deltav.otp;

import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredentialBuilder;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.net.URI;
import java.util.logging.Logger;

/**
 * Redis client pool supporting both managed identity and connection string authentication.
 *
 * Configuration priority:
 * 1. If REDIS_HOST is set -> uses managed identity (Azure Cache for Redis with Entra ID)
 * 2. Else if REDIS_URL is set -> uses connection string
 * 3. Else -> throws exception
 */
final class RedisClients {

    private static final Logger logger = Logger.getLogger(RedisClients.class.getName());
    private static final String REDIS_SCOPE = "https://redis.azure.com/.default";

    private static volatile JedisPool pool;
    private static final Object poolLock = new Object();

    private RedisClients() {}

    static JedisPool getPool() {
        if (pool == null) {
            synchronized (poolLock) {
                if (pool == null) {
                    pool = createPool();
                }
            }
        }
        return pool;
    }

    private static JedisPool createPool() {
        String redisHost = System.getenv("REDIS_HOST");
        String redisUrl = System.getenv("REDIS_URL");

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(4);

        if (redisHost != null && !redisHost.isBlank()) {
            // Use managed identity for Azure Cache for Redis
            logger.info("Initializing Redis client with managed identity");
            return createManagedIdentityPool(redisHost, poolConfig);
        } else if (redisUrl != null && !redisUrl.isBlank()) {
            // Use connection string
            logger.info("Initializing Redis client with connection string");
            return new JedisPool(poolConfig, URI.create(redisUrl));
        } else {
            throw new IllegalStateException("Neither REDIS_HOST nor REDIS_URL is configured");
        }
    }

    private static JedisPool createManagedIdentityPool(String redisHost, JedisPoolConfig poolConfig) {
        // For Redis Entra ID auth, username is the Object ID (Principal ID) of the managed identity
        // Priority: REDIS_USER > MSI_OBJECT_ID > AZURE_CLIENT_ID
        String redisUser = System.getenv("REDIS_USER");
        if (redisUser == null || redisUser.isBlank()) {
            redisUser = System.getenv("MSI_OBJECT_ID");
        }
        if (redisUser == null || redisUser.isBlank()) {
            redisUser = System.getenv("AZURE_CLIENT_ID");
        }
        if (redisUser == null || redisUser.isBlank()) {
            throw new IllegalStateException(
                "REDIS_USER must be set to the Object ID of the system-assigned managed identity. " +
                "Find it in Azure Portal > Function App > Identity > System assigned > Object (principal) ID");
        }

        int redisPort = 6380; // Default SSL port for Azure Redis
        String portEnv = System.getenv("REDIS_PORT");
        if (portEnv != null && !portEnv.isBlank()) {
            redisPort = Integer.parseInt(portEnv);
        }

        boolean useSsl = true;
        String sslEnv = System.getenv("REDIS_SSL");
        if (sslEnv != null && ("false".equalsIgnoreCase(sslEnv) || "0".equals(sslEnv))) {
            useSsl = false;
        }

        // Get access token using managed identity
        String accessToken = new DefaultAzureCredentialBuilder()
                .build()
                .getToken(new TokenRequestContext().addScopes(REDIS_SCOPE))
                .block()
                .getToken();

        DefaultJedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .user(redisUser)
                .password(accessToken)
                .ssl(useSsl)
                .build();

        return new JedisPool(poolConfig, new HostAndPort(redisHost, redisPort), clientConfig);
    }
}
