package com.opal.deltav.schedulelink;

import com.google.gson.Gson;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;

import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Public GET API for schedule trace links on {@code trace.opalalign.com}.
 *
 * <p>Patient opens SMS link {@code /{plainToken}}; the trace SPA calls
 * {@code GET /api/public/trace/{plainToken}} here. Reads {@code ScheduleLinkTokens}
 * (written by batch {@code ScheduleLinkTokenTableWriter} after SMS success).</p>
 */
public class ScheduleLinkResolveFunction {

    private static final Gson gson = new Gson();
    private final ScheduleLinkTokenResolveService resolveService = new ScheduleLinkTokenResolveService();

    @FunctionName("scheduleLinkResolve")
    public HttpResponseMessage run(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.GET},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "api/public/trace/{token}")
            HttpRequestMessage<Optional<String>> request,
            @BindingName("token") String token,
            final ExecutionContext context) {

        var logger = context.getLogger();

        if (token == null || token.isBlank()) {
            return jsonResponse(request, HttpStatus.BAD_REQUEST, Map.of("error", "invalid_link"));
        }

        try {
            ScheduleLinkTokenResolveService.ScheduleLinkPageResponse response =
                    resolveService.resolve(token, logger);
            return jsonResponse(request, HttpStatus.OK, response);
        } catch (ScheduleLinkTokenResolveService.ResolveException ex) {
            logger.log(Level.INFO, "Schedule link resolve failed: " + ex.getFailure().name());
            return jsonResponse(request, HttpStatus.NOT_FOUND, Map.of("error", "invalid_link"));
        } catch (IllegalArgumentException ex) {
            return jsonResponse(request, HttpStatus.BAD_REQUEST, Map.of("error", "invalid_link"));
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Schedule link resolve error", ex);
            return jsonResponse(request, HttpStatus.INTERNAL_SERVER_ERROR, Map.of("error", "internal_error"));
        }
    }

    private static HttpResponseMessage jsonResponse(
            HttpRequestMessage<?> request,
            HttpStatus status,
            Object body) {
        return addCors(request, request.createResponseBuilder(status)
                .header("Content-Type", "application/json")
                .body(gson.toJson(body)))
                .build();
    }

    private static HttpResponseMessage.Builder addCors(
            HttpRequestMessage<?> request,
            HttpResponseMessage.Builder builder) {
        String origin = Optional.ofNullable(request.getHeaders().get("Origin")).orElse("*");
        return builder.header("Access-Control-Allow-Origin", origin)
                .header("Vary", "Origin")
                .header("Access-Control-Allow-Methods", "GET,OPTIONS")
                .header("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }
}
