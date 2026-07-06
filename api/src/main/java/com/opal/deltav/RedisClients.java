package com.opal.deltav;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.net.URI;

final class RedisClients {

    private static volatile JedisPool pool;
    private static final Object poolLock = new Object();

    private RedisClients() {}

    static JedisPool getPool() {
        if (pool == null) {
            synchronized (poolLock) {
                if (pool == null) {
                    String redisUrl = System.getenv("REDIS_URL");
                    if (redisUrl == null || redisUrl.isBlank()) {
                        throw new IllegalStateException("REDIS_URL is not configured");
                    }
                    JedisPoolConfig config = new JedisPoolConfig();
                    config.setMaxTotal(8);
                    config.setMaxIdle(4);
                    pool = new JedisPool(config, URI.create(redisUrl));
                }
            }
        }
        return pool;
    }
}
