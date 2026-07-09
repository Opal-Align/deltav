package com.opal.deltav.function;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.opal.deltav.util.TokenUtil;

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

        // Block direct access to root or index.html - must use valid token URL
        String resource = rawUrl;
        if (resource.isEmpty() || "/".equals(resource) || resource.equals("/index.html")) {
            logger.warning("Direct access to index.html blocked - token required");
            return addCors(request, request.createResponseBuilder(HttpStatus.NOT_FOUND)
                    .header("Content-Type", "text/html; charset=utf-8")
                    .body("<html><body><h1>Invalid Link</h1><p>Please use the link provided to you to access this page.</p></body></html>")).build();
        }

        // Normalize and prevent directory traversal
        resource = resource.replace("\\", "/");
        while (resource.contains("//")) resource = resource.replace("//", "/");
        if (resource.contains("..")) {
            return addCors(request, request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Invalid path"))).build();
        }

        // Only serve CSS, JS and other static assets (not HTML)
        String classpathLocation = "/web" + resource;

        try {
            byte[] data = readResource(classpathLocation);
            if (data == null) {
                return addCors(request, request.createResponseBuilder(HttpStatus.NOT_FOUND)
                        .body("Not found")).build();
            }

            String ext = getExtension(resource);

            // Block direct access to any HTML file
            if ("html".equals(ext)) {
                logger.warning("Direct access to HTML file blocked: " + resource);
                return addCors(request, request.createResponseBuilder(HttpStatus.NOT_FOUND)
                        .body("Not found")).build();
            }

            String contentType = CONTENT_TYPES.getOrDefault(ext, URLConnection.guessContentTypeFromName(resource));
            if (contentType == null || contentType.isBlank()) {
                contentType = "application/octet-stream";
            }

            HttpResponseMessage.Builder builder = request.createResponseBuilder(HttpStatus.OK)
                    .header("Content-Type", contentType)
                    .header("Cache-Control", "public, max-age=3600");

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
     * Handle URL request with clientId:practiceId format (e.g., /123:456).
     * Extracts clientId and practiceId, stores them in cookies, and serves index.html.
     */
    private HttpResponseMessage handleTokenRequest(HttpRequestMessage<Optional<String>> request, String pathSegment, Logger logger) {
        logger.info("Path segment received: " + pathSegment);

        // Parse clientId:practiceId format
        String clientId = null;
        String practiceId = null;

        if (pathSegment.contains(":")) {
            String[] parts = pathSegment.split(":", 2);
            if (parts.length == 2) {
                clientId = parts[0].trim();
                practiceId = parts[1].trim();
            }
        }

        if (clientId == null || clientId.isEmpty() || practiceId == null || practiceId.isEmpty()) {
            logger.warning("Invalid URL format: " + pathSegment + " (expected clientId:practiceId)");
            return addCors(request, request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .header("Content-Type", "text/html; charset=utf-8")
                    .body("<html><body><h1>Invalid Link</h1><p>The link format is invalid. Please use the link provided to you.</p></body></html>")).build();
        }

        logger.info("Parsed clientId=" + clientId + ", practiceId=" + practiceId);

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

            // Replace placeholders with empty values (UI will handle display)
            html = html.replace("${practice_name}", "");
            html = html.replace("${patient_first_name}", "");
            html = html.replace("${practice_logo_url}", "logo.png");
            html = html.replace("${phone_number}", "");

            // Inject registration token and context (clientId:practiceId) for JavaScript
            String context = clientId + ":" + practiceId;
            String inject = "<script>" +
                    "window.DELTAV_TOKEN='" + registrationToken + "';" +
                    "window.DELTAV_TOKEN_EXP=" + exp + ";" +
                    "window.DELTAV_CONTEXT='" + context + "';" +
                    "</script>";
            if (html.contains("</head>")) {
                html = html.replace("</head>", inject + "</head>");
            } else if (html.contains("</body>")) {
                html = html.replace("</body>", inject + "</body>");
            } else {
                html = html + inject;
            }

            // Cookie TTL
            long cookieTtlSeconds = 1800; // 30 minutes default
            String cookieTtlEnv = System.getenv("COOKIE_TTL_SECONDS");
            if (cookieTtlEnv != null && !cookieTtlEnv.isBlank()) {
                try { cookieTtlSeconds = Math.max(300, Long.parseLong(cookieTtlEnv)); } catch (Exception ignore) {}
            }

            // Build single cookie with clientId:practiceId
            String contextCookie = String.format("DELTAV_CONTEXT=%s; Max-Age=%d; Path=/; SameSite=Strict",
                    context, cookieTtlSeconds);

            return addCors(request, request.createResponseBuilder(HttpStatus.OK)
                    .header("Content-Type", "text/html; charset=utf-8")
                    .header("Cache-Control", "no-store, must-revalidate")
                    .header("Set-Cookie", contextCookie)
                    .body(html.getBytes(StandardCharsets.UTF_8))).build();

        } catch (IOException e) {
            logger.severe("Error serving index.html: " + e.getMessage());
            return addCors(request, request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Internal server error")).build();
        }
    }
}