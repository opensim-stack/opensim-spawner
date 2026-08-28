package uk.co.bithatch.opensim.spawner.api;

import jakarta.servlet.http.HttpSession;

final class UiAuthSupport {

    static final String SESSION_AUTH_KEY = "spawnerUiAuthenticated";
    static final String SESSION_ADMIN_KEY = "spawnerUiAdmin";
    static final String SESSION_USER_FIRST_KEY = "spawnerUiUserFirst";
    static final String SESSION_USER_LAST_KEY = "spawnerUiUserLast";

    private UiAuthSupport() {
    }

    static boolean isAuthenticated(HttpSession session) {
        if (session == null) {
            return false;
        }
        return Boolean.TRUE.equals(session.getAttribute(SESSION_AUTH_KEY));
    }

    static boolean isAdmin(HttpSession session) {
        if (!isAuthenticated(session)) {
            return false;
        }
        return Boolean.TRUE.equals(session.getAttribute(SESSION_ADMIN_KEY));
    }

    static void markAdminAuthenticated(HttpSession session) {
        session.setAttribute(SESSION_AUTH_KEY, Boolean.TRUE);
        session.setAttribute(SESSION_ADMIN_KEY, Boolean.TRUE);
        session.removeAttribute(SESSION_USER_FIRST_KEY);
        session.removeAttribute(SESSION_USER_LAST_KEY);
    }

    static void markUserAuthenticated(HttpSession session, String first, String last) {
        session.setAttribute(SESSION_AUTH_KEY, Boolean.TRUE);
        session.setAttribute(SESSION_ADMIN_KEY, Boolean.FALSE);
        session.setAttribute(SESSION_USER_FIRST_KEY, normalize(first));
        session.setAttribute(SESSION_USER_LAST_KEY, normalize(last));
    }

    static String authenticatedUserFirst(HttpSession session) {
        if (session == null) {
            return "";
        }
        return normalize((String) session.getAttribute(SESSION_USER_FIRST_KEY));
    }

    static String authenticatedUserLast(HttpSession session) {
        if (session == null) {
            return "";
        }
        return normalize((String) session.getAttribute(SESSION_USER_LAST_KEY));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
