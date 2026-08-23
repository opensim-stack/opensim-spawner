package uk.co.bithatch.opensim.spawner.api;

import jakarta.servlet.http.HttpSession;

final class UiAuthSupport {

    static final String SESSION_AUTH_KEY = "spawnerUiAuthenticated";

    private UiAuthSupport() {
    }

    static boolean isAuthenticated(HttpSession session) {
        if (session == null) {
            return false;
        }
        return Boolean.TRUE.equals(session.getAttribute(SESSION_AUTH_KEY));
    }
}
