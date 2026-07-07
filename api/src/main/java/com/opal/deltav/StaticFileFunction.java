package com.opal.deltav;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.opal.deltav.schedulelinktoken.ScheduleLinkTokenProvider;
import com.opal.deltav.schedulelinktoken.ScheduleLinkTokenProviderFactory;
import com.opal.deltav.session.SessionManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Serves static assets (index.html, styles.css, form.js) from classpath under /web.
 * Now injects a short-lived token into index.html for subsequent POST /api/register validation.
 */
public class StaticFileFunction {

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "application/javascript; charset=utf-8"
    );

    @FunctionName("static")
    public HttpResponseMessage run(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.GET, HttpMethod.HEAD},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "{*path}"
            ) HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        Logger logger = context.getLogger();

        String path = request.getQueryParameters().getOrDefault("path", ""); // not used; for clarity
        // With empty routePrefix, the request path maps directly to the resource path
        String rawUrl = request.getUri().getPath(); // e.g. /, /index.html, /styles.css

        // If the path targets the API namespace, don't serve static content
        if (rawUrl.equals("/api") || rawUrl.equals("/api/") || rawUrl.startsWith("/api/")) {
            return addCors(request, request.createResponseBuilder(HttpStatus.NOT_FOUND)
                    .body("Not found")).build();
        }

        // Check if path looks like a schedule link token (e.g., /<token>)
        // Token format: single path segment, no file extension
        String pathSegment = rawUrl.startsWith("/") ? rawUrl.substring(1) : rawUrl;
        if (!pathSegment.isEmpty() && !pathSegment.contains("/") && !pathSegment.contains(".")) {
            return handleTokenRequest(request, pathSegment, logger);
        }

        String resource = rawUrl;
        if (resource.isEmpty() || "/".equals(resource)) {
            resource = "/index.html";
        }

        // Normalize and prevent directory traversal
        resource = resource.replace("\\", "/");
        while (resource.contains("//")) resource = resource.replace("//", "/");
        if (resource.contains("..")) {
            return addCors(request, request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Invalid path"))).build();
        }

        String classpathLocation = "/web" + resource; // resources are placed under src/main/resources/web

        try {
            byte[] data = readResource(classpathLocation);
            if (data == null) {
                return addCors(request, request.createResponseBuilder(HttpStatus.NOT_FOUND)
                        .body("Not found")).build();
            }

            String ext = getExtension(resource);
            String contentType = CONTENT_TYPES.getOrDefault(ext, URLConnection.guessContentTypeFromName(resource));
            if (contentType == null || contentType.isBlank()) {
                contentType = "application/octet-stream";
            }

            // If serving index.html, inject a short-lived token
            if ("html".equals(ext) && resource.endsWith("/index.html")) {
                String secret = System.getenv("REGISTRATION_TOKEN_SECRET");
                if (secret == null || secret.isBlank()) {
                    logger.severe("REGISTRATION_TOKEN_SECRET is not configured");
                    return addCors(request, request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body("Server configuration error")).build();
                }
                long ttl = 900; // default 15 minutes
                String ttlEnv = System.getenv("REGISTRATION_TOKEN_TTL_SECONDS");
                if (ttlEnv != null && !ttlEnv.isBlank()) {
                    try { ttl = Math.max(60, Long.parseLong(ttlEnv)); } catch (Exception ignore) {}
                }
                long exp = Instant.now().getEpochSecond() + ttl;
                String token = TokenUtil.createToken(exp, secret);

                String html = new String(data, StandardCharsets.UTF_8);
                String inject = "<script>window.DELTAV_TOKEN='" + token + "';window.DELTAV_TOKEN_EXP=" + exp + ";</script>";
                if (html.contains("</head>")) {
                    html = html.replace("</head>", inject + "</head>");
                } else if (html.contains("</body>")) {
                    html = html.replace("</body>", inject + "</body>");
                } else {
                    html = html + inject;
                }
                data = html.getBytes(StandardCharsets.UTF_8);
            }

            HttpResponseMessage.Builder builder = request.createResponseBuilder(HttpStatus.OK)
                    .header("Content-Type", contentType)
                    .header("Cache-Control", ext.equals("html") ? "no-store, must-revalidate" : "public, max-age=3600");

            addCors(request, builder);
            if (request.getHttpMethod() == HttpMethod.HEAD) {
                return builder.build();
            }
            return builder.body(data).build();
        } catch (IOException e) {
            logger.severe("Error serving static resource '" + classpathLocation + "': " + e.getMessage());
            return addCors(request, request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Internal server error")).build();
        }
    }

    private static String getExtension(String path) {
        int i = path.lastIndexOf('.');
        if (i == -1 || i == path.length() - 1) return "";
        return path.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream in = StaticFileFunction.class.getResourceAsStream(path)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        }
    }

    private static String getRoutePrefix() {
        // Mirrors default in host.json; if changed, update here accordingly.
        // In this project host.json sets routePrefix to "api".
        return "api";
    }

    private static HttpResponseMessage.Builder addCors(HttpRequestMessage<?> req, HttpResponseMessage.Builder builder) {
        String origin = Optional.ofNullable(req.getHeaders().get("Origin")).orElse("*");
        builder.header("Access-Control-Allow-Origin", origin)
                .header("Vary", "Origin")
                .header("Access-Control-Allow-Methods", "GET,HEAD,OPTIONS")
                .header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Registration-Token");
        return builder;
    }

    /**
     * Handle token-based request (e.g., /<token>).
     * Validates token against table storage and serves index.html.
     */
    private HttpResponseMessage handleTokenRequest(HttpRequestMessage<Optional<String>> request, String token, Logger logger) {
        logger.info("Token request received: " + token);

        // Validate token against table storage
        ScheduleLinkTokenProvider provider = ScheduleLinkTokenProviderFactory.getProvider();
        ScheduleLinkTokenProvider.ValidationResult validationResult = provider.validateToken(token, logger);

        if (!validationResult.valid) {
            logger.warning("Token validation failed for '" + token + "': " + validationResult.error);
            return addCors(request, request.createResponseBuilder(HttpStatus.NOT_FOUND)
                    .header("Content-Type", "text/html; charset=utf-8")
                    .body("<html><body><h1>Invalid or Expired Link</h1><p>This link is no longer valid. Please request a new link.</p></body></html>")).build();
        }

        logger.info("Token validated successfully: " + token + ", practiceId=" + validationResult.token.getPracticeId());

        // Generate session ID
        String sessionId = UUID.randomUUID().toString();
        long sessionTtlSeconds = 1800; // 30 minutes default
        String sessionTtlEnv = System.getenv("SESSION_TTL_SECONDS");
        if (sessionTtlEnv != null && !sessionTtlEnv.isBlank()) {
            try { sessionTtlSeconds = Math.max(300, Long.parseLong(sessionTtlEnv)); } catch (Exception ignore) {}
        }

        // Store session
        SessionManager.getInstance().storeSession(sessionId, token, sessionTtlSeconds, logger);

        // Serve index.html with registration token
        try {
            byte[] data = readResource("/web/index.html");
            if (data == null) {
                logger.severe("index.html not found in resources");
                return addCors(request, request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Internal server error")).build();
            }

            String secret = System.getenv("REGISTRATION_TOKEN_SECRET");
            if (secret == null || secret.isBlank()) {
                logger.severe("REGISTRATION_TOKEN_SECRET is not configured");
                return addCors(request, request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Server configuration error")).build();
            }

            long ttl = 900; // default 15 minutes
            String ttlEnv = System.getenv("REGISTRATION_TOKEN_TTL_SECONDS");
            if (ttlEnv != null && !ttlEnv.isBlank()) {
                try { ttl = Math.max(60, Long.parseLong(ttlEnv)); } catch (Exception ignore) {}
            }
            long exp = Instant.now().getEpochSecond() + ttl;
            String registrationToken = TokenUtil.createToken(exp, secret);

            String html = new String(data, StandardCharsets.UTF_8);
            String inject = "<script>window.DELTAV_TOKEN='" + registrationToken + "';window.DELTAV_TOKEN_EXP=" + exp + ";</script>";
            if (html.contains("</head>")) {
                html = html.replace("</head>", inject + "</head>");
            } else if (html.contains("</body>")) {
                html = html.replace("</body>", inject + "</body>");
            } else {
                html = html + inject;
            }

            // Build session cookie
            String cookie = String.format("DELTAV_SESSION=%s; Max-Age=%d; Path=/; HttpOnly; SameSite=Strict",
                    sessionId, sessionTtlSeconds);

            return addCors(request, request.createResponseBuilder(HttpStatus.OK)
                    .header("Content-Type", "text/html; charset=utf-8")
                    .header("Cache-Control", "no-store, must-revalidate")
                    .header("Set-Cookie", cookie)
                    .body(html.getBytes(StandardCharsets.UTF_8))).build();

        } catch (IOException e) {
            logger.severe("Error serving index.html for token request: " + e.getMessage());
            return addCors(request, request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Internal server error")).build();
        }
    }
}
