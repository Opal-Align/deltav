package com.opal.deltav.session;

import com.opal.deltav.otp.RedisSessionService;
import com.opal.deltav.otp.RegistrationSession;

import java.util.logging.Logger;

/**
 * Manages session storage and verification.
 * Delegates to RedisSessionService for actual storage.
 */
public class SessionManager {

    private static volatile SessionManager instance;
    private static final Object lock = new Object();

    private SessionManager() {
    }

    public static SessionManager getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new SessionManager();
                }
            }
        }
        return instance;
    }

    /**
     * Create a new session for a practice.
     *
     * @param practiceId the practice ID
     * @param logger the logger
     * @return the created session ID
     */
    public String createSession(String practiceId, Logger logger) {
        return createSession(practiceId, null, logger);
    }

    /**
     * Create a new session with associated token.
     *
     * @param practiceId the practice ID
     * @param token the schedule link token associated with this session
     * @param logger the logger
     * @return the created session ID
     */
    public String createSession(String practiceId, String token, Logger logger) {
        String sessionId = RedisSessionService.createSession(practiceId, token);
        logger.info("Created session: " + sessionId + " for practice: " + practiceId);
        return sessionId;
    }

    /**
     * Verify if a session is valid (exists and OTP is verified).
     *
     * @param sessionId the session ID to verify
     * @param logger the logger
     * @return true if session is valid and OTP verified
     */
    public boolean isValidSession(String sessionId, Logger logger) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        RegistrationSession session = RedisSessionService.getSession(sessionId);
        if (session == null) {
            logger.info("Session not found: " + sessionId);
            return false;
        }
        boolean valid = session.isOtpVerified();
        logger.info("Session " + sessionId + " valid=" + valid + " state=" + session.state);
        return valid;
    }

    /**
     * Get the token associated with a session.
     *
     * @param sessionId the session ID
     * @param logger the logger
     * @return the associated token, or null if not found
     */
    public String getTokenForSession(String sessionId, Logger logger) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        RegistrationSession session = RedisSessionService.getSession(sessionId);
        if (session == null) {
            logger.info("Session not found for token lookup: " + sessionId);
            return null;
        }
        return session.token;
    }

    /**
     * Invalidate/delete a session.
     *
     * @param sessionId the session ID to invalidate
     * @param logger the logger
     */
    public void invalidateSession(String sessionId, Logger logger) {
        logger.info("Invalidating session: " + sessionId);
        RedisSessionService.invalidateSession(sessionId);
    }

    /**
     * Extend session expiry.
     *
     * @param sessionId the session ID
     * @param ttlSeconds new time to live in seconds
     * @param logger the logger
     */
    public void extendSession(String sessionId, long ttlSeconds, Logger logger) {
        logger.info("Extending session " + sessionId + " TTL to " + ttlSeconds + "s");
        RedisSessionService.extendSession(sessionId, (int) ttlSeconds);
    }
}