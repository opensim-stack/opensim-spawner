package uk.co.bithatch.opensim.spawner.api;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(10)
public class UiSessionAuthFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = request.getRequestURI();
        if (path == null) {
            return true;
        }

        if (!path.startsWith("/ui/")) {
            return true;
        }

        if (path.startsWith("/ui/api/auth/")) {
            return true;
        }

        if ("/ui/login.html".equals(path) || "/ui/register.html".equals(path)) {
            return true;
        }

        return !path.endsWith(".html");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var session = request.getSession(false);
        if (!UiAuthSupport.isAuthenticated(session)) {
            var originalPath = request.getRequestURI();
            var query = request.getQueryString();
            var fullPath = query == null || query.isBlank() ? originalPath : originalPath + "?" + query;
            var encodedNext = URLEncoder.encode(fullPath, StandardCharsets.UTF_8);
            response.sendRedirect("/ui/login.html?next=" + encodedNext);
            return;
        }

        var path = request.getRequestURI();
        if (!UiAuthSupport.isAdmin(session) && !"/ui/change-password.html".equals(path)) {
            response.sendRedirect("/ui/change-password.html");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
