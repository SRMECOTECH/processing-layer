package com.srm.gps.application.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The signed-in user of a portal request, as the token says (PortalTokens) - checked by PortalAuthFilter before any
 * /api/portal/** controller runs.
 *
 * @param tenantCode  the company a TRANSPORTER or CONSIGNER user belongs to; null for a DEVELOPER (GPS provider)
 * @param expiresAt   epoch seconds
 */
public record PortalUser(String userId, String username, Role role, String displayName, String tenantCode,
                         String tenantName, long expiresAt) {

    /** The three kinds of login. The sign-in page asks which one, as well as the user id and password. */
    public enum Role {
        /** The GPS provider (us): everything, every company, plus the engine. */
        DEVELOPER,
        /** A truck company: its fleet, the shipments it carries, its alerts. */
        TRANSPORTER,
        /** A company sending goods: its shipments from plant to consignee, and how its transporters perform. */
        CONSIGNER
    }

    static final String REQUEST_ATTRIBUTE = PortalUser.class.getName();

    public static PortalUser of(HttpServletRequest request) {
        Object user = request.getAttribute(REQUEST_ATTRIBUTE);
        if (!(user instanceof PortalUser portalUser)) {
            throw new NotSignedInException();
        }
        return portalUser;
    }

    public boolean developer() {
        return role == Role.DEVELOPER;
    }

    /** Only the GPS provider may change master data, users and places. */
    public void requireDeveloper() {
        if (!developer()) {
            throw new ForbiddenException("Only the GPS provider can do this");
        }
    }

    public static class NotSignedInException extends RuntimeException {
        public NotSignedInException() {
            super("Sign in to use the portal");
        }
    }

    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }
}
