package com.opal.deltav;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Looks up an existing patient by practice, name, and date of birth.
 * Returns nothing on mismatch or ambiguous matches (no SMS side-channel).
 */
public final class PatientIdentityService {

    private PatientIdentityService() {}

    public static LookupResult lookup(String practiceId, String firstName, String lastName, LocalDate dob) {
        String normalizedPractice = RedisOtpService.normalizePracticeId(practiceId);
        if (normalizedPractice == null || !normalizedPractice.matches("\\d+")) {
            return LookupResult.invalidPractice();
        }
        String first = normalizeName(firstName);
        String last = normalizeName(lastName);
        if (first == null || last == null || dob == null) {
            return LookupResult.noMatch();
        }

        String sql = """
                SELECT TOP 3 patient_key, cell
                FROM dbo.patient
                WHERE practice_id = ?
                  AND birthdate = ?
                  AND LOWER(LTRIM(RTRIM(firstname))) = ?
                  AND LOWER(LTRIM(RTRIM(lastname))) = ?
                  AND (is_active = 1 OR is_active IS NULL)
                """;

        try (Connection connection = DbConnectionFactory.open();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, Long.parseLong(normalizedPractice));
            ps.setObject(2, dob);
            ps.setString(3, first);
            ps.setString(4, last);

            List<PatientRecord> matches = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    matches.add(new PatientRecord(
                            rs.getLong("patient_key"),
                            rs.getString("cell")
                    ));
                }
            }

            if (matches.isEmpty()) {
                return LookupResult.noMatch();
            }
            if (matches.size() > 1) {
                return LookupResult.ambiguous();
            }

            PatientRecord patient = matches.get(0);
            String phone = patient.phone == null ? null : patient.phone.trim();
            if (phone == null || phone.isBlank()) {
                return LookupResult.noPhoneOnFile();
            }
            if (PhoneUtil.normalizeMobile(phone) == null) {
                return LookupResult.noPhoneOnFile();
            }
            return LookupResult.match(patient);
        } catch (IllegalStateException e) {
            throw e;
        } catch (SQLException e) {
            throw new IllegalStateException("Patient lookup failed", e);
        }
    }

    private static String normalizeName(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.toLowerCase(Locale.ROOT);
    }

    public static final class LookupResult {
        public final boolean matched;
        public final String error;
        public final PatientRecord patient;

        private LookupResult(boolean matched, String error, PatientRecord patient) {
            this.matched = matched;
            this.error = error;
            this.patient = patient;
        }

        public static LookupResult match(PatientRecord patient) {
            return new LookupResult(true, null, patient);
        }

        public static LookupResult noMatch() {
            return new LookupResult(false, "identity_mismatch", null);
        }

        public static LookupResult ambiguous() {
            return new LookupResult(false, "identity_mismatch", null);
        }

        public static LookupResult invalidPractice() {
            return new LookupResult(false, "invalid_practice", null);
        }

        public static LookupResult noPhoneOnFile() {
            return new LookupResult(false, "no_phone_on_file", null);
        }
    }
}
