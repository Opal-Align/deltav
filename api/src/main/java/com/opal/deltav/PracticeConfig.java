package com.opal.deltav;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

public class PracticeConfig {

    private static volatile Map<String, String> redirectMap;
    private static final Object lock = new Object();

    private PracticeConfig() {}

    public static Map<String, String> getRedirectMap() {
        if (redirectMap == null) {
            synchronized (lock) {
                if (redirectMap == null) {
                    redirectMap = loadFromClasspath();
                }
            }
        }
        return redirectMap;
    }

    public static String getRedirectUrl(String practiceId) {
        if (practiceId == null || practiceId.isBlank()) return null;
        return getRedirectMap().get(practiceId.trim());
    }

    private static Map<String, String> loadFromClasspath() {
        try (InputStream is = PracticeConfig.class.getClassLoader()
                .getResourceAsStream("practice-redirects.json")) {
            if (is == null) return Collections.emptyMap();
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            return Collections.unmodifiableMap(
                    new Gson().fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), type));
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
