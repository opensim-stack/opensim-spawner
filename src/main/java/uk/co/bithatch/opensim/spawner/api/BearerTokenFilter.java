package uk.co.bithatch.opensim.spawner.api;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;

@Component
public class BearerTokenFilter extends OncePerRequestFilter {

    private final SpawnerProperties properties;

    public BearerTokenFilter(SpawnerProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var path = request.getRequestURI();
        if (path == null || !path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        var configuredToken = properties.getToken();
        if (configuredToken == null || configuredToken.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        var auth = request.getHeader("Authorization");
        var session = request.getSession(false);
        if (!isAuthorized(auth, configuredToken) && !UiAuthSupport.isAdmin(session)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"Unauthorized\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    static boolean isAuthorized(String authHeader, String expectedToken) {
        if (expectedToken == null || expectedToken.isBlank()) {
            return true;
        }
        if (authHeader == null || authHeader.isBlank()) {
            return false;
        }
        var prefix = "Bearer ";
        if (!authHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return false;
        }
        return expectedToken.equals(authHeader.substring(prefix.length()).trim());
    }
}
