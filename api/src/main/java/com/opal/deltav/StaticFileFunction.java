package com.opal.deltav;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * Serves static assets (index.html, styles.css, form.js) from classpath under /web.
 * Route is a catch-all under the default route prefix ("api").
 *
 * Examples after deploy (assuming app name deltav-api):
 *  - https://deltav-api.azurewebsites.net/api           -> index.html
 *  - https://deltav-api.azurewebsites.net/api/index.html
 *  - https://deltav-api.azurewebsites.net/api/styles.css
 *  - https://deltav-api.azurewebsites.net/api/form.js
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
        // The bound route parameter isn't directly exposed via the typed signature, so derive from URL
        String rawUrl = request.getUri().getPath(); // e.g. /api, /api/index.html, /api/styles.css

        // Trim the route prefix ("/api") if present so we can map to resource name
        String resource = rawUrl;
        String prefix = "/" + getRoutePrefix(); // usually "/api"
        if (resource.startsWith(prefix)) {
            resource = resource.substring(prefix.length()); // e.g. "", "/index.html"
        }
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
                .header("Access-Control-Allow-Headers", "Content-Type, Authorization");
        return builder;
    }
}
