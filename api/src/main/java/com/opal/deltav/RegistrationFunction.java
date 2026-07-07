package com.opal.deltav;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableServiceClient;
import com.azure.data.tables.TableServiceClientBuilder;
import com.azure.data.tables.models.TableEntity;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

public class RegistrationFunction {

    private static final Gson gson = new Gson();
    private static final String TABLE_NAME = "PatientRegistrations";
    private static volatile TableClient tableClient;
    private static final Object lock = new Object();

    private static TableClient getTableClient(Logger logger) {
        if (tableClient == null) {
            synchronized (lock) {
                if (tableClient == null) {
                    String storageAccountName = System.getenv("STORAGE_ACCOUNT_NAME");
                    String connStr = System.getenv("AzureWebJobsStorage");

                    TableServiceClient serviceClient;
                    if (storageAccountName != null && !storageAccountName.isBlank()) {
                        logger.info("Initializing Table Storage client with managed identity");
                        String endpoint = "https://" + storageAccountName + ".table.core.windows.net";
                        serviceClient = new TableServiceClientBuilder()
                                .endpoint(endpoint)
                                .credential(new DefaultAzureCredentialBuilder().build())
                                .buildClient();
                    } else if (connStr != null && !connStr.isBlank()) {
                        logger.info("Initializing Table Storage client with connection string");
                        serviceClient = new TableServiceClientBuilder()
                                .connectionString(connStr)
                                .buildClient();
                    } else {
                        throw new IllegalStateException("Neither STORAGE_ACCOUNT_NAME nor AzureWebJobsStorage is configured");
                    }

                    serviceClient.createTableIfNotExists(TABLE_NAME);
                    tableClient = serviceClient.getTableClient(TABLE_NAME);
                    logger.info("Table Storage client initialized successfully");
                }
            }
        }
        return tableClient;
    }

    @FunctionName("register")
    public HttpResponseMessage run(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST, HttpMethod.OPTIONS},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "api/register"
            ) HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        Logger logger = context.getLogger();
        logger.info("Registration request received");

        if (request.getHttpMethod() == HttpMethod.OPTIONS) {
            return corsResponse(request, HttpStatus.NO_CONTENT, null);
        }

        String body = request.getBody().orElse(null);
        if (body == null || body.isBlank()) {
            logger.warning("Request rejected: empty or missing body");
            return jsonResponse(request, HttpStatus.BAD_REQUEST,
                    Map.of("error", "Request body is required"));
        }

        JsonObject json;
        try {
            json = gson.fromJson(body, JsonObject.class);
        } catch (Exception e) {
            logger.warning("Request rejected: invalid JSON - " + e.getMessage());
            return jsonResponse(request, HttpStatus.BAD_REQUEST,
                    Map.of("error", "Invalid JSON"));
        }

        String secret = System.getenv("REGISTRATION_TOKEN_SECRET");
        if (secret == null || secret.isBlank()) {
            logger.severe("REGISTRATION_TOKEN_SECRET is not configured");
            return jsonResponse(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    Map.of("error", "Server configuration error"));
        }
        String headerToken = getHeaderIgnoreCase(request.getHeaders(), "X-Registration-Token");
        String bodyToken = json.has("registration_token") && !json.get("registration_token").isJsonNull()
                ? json.get("registration_token").getAsString() : null;
        String token = (headerToken != null && !headerToken.isBlank()) ? headerToken : bodyToken;
        TokenUtil.ValidationResult tokenResult = TokenUtil.validate(token, secret);
        if (!tokenResult.valid) {
            logger.warning("Registration rejected due to token error: " + tokenResult.error);
            return jsonResponse(request, HttpStatus.UNAUTHORIZED,
                    Map.of("error", tokenResult.error));
        }

        List<String> errors = validate(json);
        if (!errors.isEmpty()) {
            logger.warning("Validation failed: " + String.join(", ", errors));
            return jsonResponse(request, HttpStatus.BAD_REQUEST,
                    Map.of("errors", errors));
        }

        String sessionId = getStr(json, "session_id");
        RegistrationSession session = RedisSessionService.getSession(sessionId);
        if (session == null || !session.isOtpVerified()) {
            logger.warning("Registration rejected: session not OTP-verified");
            return jsonResponse(request, HttpStatus.UNAUTHORIZED,
                    Map.of("error", "otp_not_verified"));
        }
        if (!Objects.equals(session.practiceId, getStr(json, "practice"))) {
            logger.warning("Registration rejected: session practice mismatch");
            return jsonResponse(request, HttpStatus.UNAUTHORIZED,
                    Map.of("error", "invalid_session"));
        }

        try {
            TableClient client = getTableClient(logger);

            String practiceId = getStr(json, "practice");
            String monthYear = LocalDate.now().format(DateTimeFormatter.ofPattern("MM-yyyy"));
            String partitionKey = practiceId + "_" + monthYear;
            String rowKey = UUID.randomUUID().toString();
            logger.info("Storing registration: partitionKey=" + partitionKey + ", rowKey=" + rowKey);

            TableEntity entity = new TableEntity(partitionKey, rowKey)
                    .addProperty("practice", practiceId)
                    .addProperty("registrant", getStr(json, "registrant"))
                    .addProperty("patientType", getStr(json, "patient_type"))
                    .addProperty("firstName", getStr(json, "first_name"))
                    .addProperty("lastName", getStr(json, "last_name"))
                    .addProperty("dob", getStr(json, "dob"))
                    .addProperty("mobile", session.phoneE164)
                    .addProperty("sessionId", sessionId)
                    .addProperty("confirmAccurate", getBool(json, "confirm_accurate"))
                    .addProperty("agreePrivacy", getBool(json, "agree_privacy"))
                    .addProperty("redirectUrl", getStr(json, "redirect_url"))
                    .addProperty("relationship", getStr(json, "relationship"))
                    .addProperty("relationshipOther", getStr(json, "relationship_other"))
                    .addProperty("submittedAt", OffsetDateTime.now().toString());

            client.createEntity(entity);
            logger.info("Registration stored successfully: " + partitionKey + "/" + rowKey);

            String redirectUrl = PracticeConfig.getRedirectUrl(practiceId);
            logger.info("Practice=" + practiceId + ", resolved redirect_url=" + redirectUrl);

            return jsonResponse(request, HttpStatus.CREATED,
                    Map.of("id", rowKey, "redirect_url", Objects.toString(redirectUrl, "")));

        } catch (IllegalStateException e) {
            logger.severe("Configuration error: " + e.getMessage());
            return jsonResponse(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    Map.of("error", "Internal server error"));
        } catch (Exception e) {
            logger.severe("Table Storage error: " + e.getClass().getName() + " - " + e.getMessage());
            return jsonResponse(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    Map.of("error", "Internal server error"));
        }
    }

    private List<String> validate(JsonObject json) {
        List<String> errors = new ArrayList<>();

        requireNonBlank(json, "first_name", "First name is required", errors);
        requireNonBlank(json, "last_name", "Last name is required", errors);
        requireNonBlank(json, "dob", "Date of birth is required", errors);
        requireNonBlank(json, "patient_type", "Patient type is required", errors);

        String dob = getStr(json, "dob");
        if (dob != null && !dob.isBlank()) {
            try {
                if (LocalDate.parse(dob).isAfter(LocalDate.now())) {
                    errors.add("Date of birth cannot be in the future");
                }
            } catch (Exception e) {
                errors.add("Invalid date format for dob");
            }
        }

        if (!getBool(json, "confirm_accurate")) errors.add("Must confirm accuracy");
        if (!getBool(json, "agree_privacy")) errors.add("Must agree to privacy policy");

        String registrant = getStr(json, "registrant");
        if ("another".equals(registrant)) {
            requireNonBlank(json, "relationship", "Relationship is required", errors);
            if ("other".equals(getStr(json, "relationship"))) {
                requireNonBlank(json, "relationship_other", "Please specify the relationship", errors);
            }
        }

        return errors;
    }

    private void requireNonBlank(JsonObject j, String field, String msg, List<String> errors) {
        String val = getStr(j, field);
        if (val == null || val.isBlank()) errors.add(msg);
    }

    private String getStr(JsonObject j, String field) {
        return j.has(field) && !j.get(field).isJsonNull() ? j.get(field).getAsString() : null;
    }

    private boolean getBool(JsonObject j, String field) {
        return j.has(field) && !j.get(field).isJsonNull() && j.get(field).getAsBoolean();
    }

    private HttpResponseMessage jsonResponse(HttpRequestMessage<?> request, HttpStatus status, Map<String, ?> body) {
        HttpResponseMessage.Builder builder = request.createResponseBuilder(status)
                .body(gson.toJson(body))
                .header("Content-Type", "application/json");
        addCorsHeaders(builder, request);
        return builder.build();
    }

    private HttpResponseMessage corsResponse(HttpRequestMessage<?> request, HttpStatus status, Map<String, ?> body) {
        HttpResponseMessage.Builder builder = request.createResponseBuilder(status);
        if (body != null) {
            builder.body(gson.toJson(body))
                   .header("Content-Type", "application/json");
        }
        addCorsHeaders(builder, request);
        return builder.build();
    }

    private void addCorsHeaders(HttpResponseMessage.Builder builder, HttpRequestMessage<?> request) {
        String origin = request.getHeaders() != null ? request.getHeaders().get("Origin") : null;
        if (origin == null || origin.isBlank()) origin = "*";
        builder.header("Access-Control-Allow-Origin", origin)
               .header("Vary", "Origin")
               .header("Access-Control-Allow-Methods", "POST, OPTIONS")
               .header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With, X-Registration-Token")
               .header("Access-Control-Max-Age", "3600");
    }

    private String getHeaderIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }
}