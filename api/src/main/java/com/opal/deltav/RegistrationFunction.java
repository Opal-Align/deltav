package com.opal.deltav;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableServiceClient;
import com.azure.data.tables.TableServiceClientBuilder;
import com.azure.data.tables.models.TableEntity;
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

    @FunctionName("register")
    public HttpResponseMessage run(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "register"
            ) HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        Logger logger = context.getLogger();

        // Parse JSON body
        String body = request.getBody().orElse(null);
        if (body == null || body.isBlank()) {
            return jsonResponse(request, HttpStatus.BAD_REQUEST,
                    Map.of("error", "Request body is required"));
        }

        JsonObject json;
        try {
            json = gson.fromJson(body, JsonObject.class);
        } catch (Exception e) {
            return jsonResponse(request, HttpStatus.BAD_REQUEST,
                    Map.of("error", "Invalid JSON"));
        }

        // Validate
        List<String> errors = validate(json);
        if (!errors.isEmpty()) {
            return jsonResponse(request, HttpStatus.BAD_REQUEST,
                    Map.of("errors", errors));
        }

        // Store in Table Storage
        try {
            String connStr = System.getenv("AzureWebJobsStorage");
            TableServiceClient serviceClient = new TableServiceClientBuilder()
                    .connectionString(connStr)
                    .buildClient();
            serviceClient.createTableIfNotExists(TABLE_NAME);
            TableClient tableClient = serviceClient.getTableClient(TABLE_NAME);

            String partitionKey = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
            String rowKey = UUID.randomUUID().toString();

            TableEntity entity = new TableEntity(partitionKey, rowKey)
                    .addProperty("registrant", getStr(json, "registrant"))
                    .addProperty("firstName", getStr(json, "first_name"))
                    .addProperty("lastName", getStr(json, "last_name"))
                    .addProperty("dob", getStr(json, "dob"))
                    .addProperty("phone", getStr(json, "phone"))
                    .addProperty("email", getStr(json, "email"))
                    .addProperty("confirmAccurate", getBool(json, "confirm_accurate"))
                    .addProperty("agreePrivacy", getBool(json, "agree_privacy"))
                    .addProperty("redirectUrl", getStr(json, "redirect_url"))
                    .addProperty("relationship", getStr(json, "relationship"))
                    .addProperty("relationshipOther", getStr(json, "relationship_other"))
                    .addProperty("submittedAt", OffsetDateTime.now().toString());

            tableClient.createEntity(entity);

            logger.info("Registration stored: " + rowKey);

            return jsonResponse(request, HttpStatus.CREATED,
                    Map.of("id", rowKey, "redirect_url", Objects.toString(getStr(json, "redirect_url"), "")));

        } catch (Exception e) {
            logger.severe("Table Storage error: " + e.getMessage());
            return jsonResponse(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    Map.of("error", "Internal server error"));
        }
    }

    private List<String> validate(JsonObject json) {
        List<String> errors = new ArrayList<>();

        requireNonBlank(json, "first_name", "First name is required", errors);
        requireNonBlank(json, "last_name", "Last name is required", errors);
        requireNonBlank(json, "dob", "Date of birth is required", errors);
        requireNonBlank(json, "phone", "Phone is required", errors);
        requireNonBlank(json, "email", "Email is required", errors);

        String email = getStr(json, "email");
        if (email != null && !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            errors.add("Invalid email format");
        }

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

        if (!getBool(json, "confirm_accurate")) {
            errors.add("Must confirm accuracy");
        }
        if (!getBool(json, "agree_privacy")) {
            errors.add("Must agree to privacy policy");
        }

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
        if (val == null || val.isBlank()) {
            errors.add(msg);
        }
    }

    private String getStr(JsonObject j, String field) {
        return j.has(field) && !j.get(field).isJsonNull() ? j.get(field).getAsString() : null;
    }

    private boolean getBool(JsonObject j, String field) {
        return j.has(field) && !j.get(field).isJsonNull() && j.get(field).getAsBoolean();
    }

    private HttpResponseMessage jsonResponse(HttpRequestMessage<?> request, HttpStatus status, Map<String, ?> body) {
        return request.createResponseBuilder(status)
                .body(gson.toJson(body))
                .header("Content-Type", "application/json")
                .build();
    }
}
