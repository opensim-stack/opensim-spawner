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
import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.service.BotProvisioningService;
import uk.co.bithatch.opensim.spawner.service.SimulatorProvisioningService;

@Component
@Order(10)
public class UiSessionAuthFilter extends OncePerRequestFilter {

    private final SpawnerProperties properties;
    private final SimulatorProvisioningService simulatorProvisioningService;
    private final BotProvisioningService botProvisioningService;

    public UiSessionAuthFilter(SpawnerProperties properties,
            SimulatorProvisioningService simulatorProvisioningService,
            BotProvisioningService botProvisioningService) {
        this.properties = properties;
        this.simulatorProvisioningService = simulatorProvisioningService;
        this.botProvisioningService = botProvisioningService;
    }

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

        if (requiresGuidedSetup() && ("/ui/setup.html".equals(path) || "/ui/index.html".equals(path))) {
            return true;
        }

        return !path.endsWith(".html");
    }

    private boolean requiresGuidedSetup() {
        if (!isGuidedProvisioningMode()) {
            return false;
        }
        return simulatorProvisioningService.listNames().isEmpty() && botProvisioningService.listNames().isEmpty();
    }

    private boolean isGuidedProvisioningMode() {
        var mode = properties.getOpensimProvisionMode();
        return mode != null && "guided".equalsIgnoreCase(mode.trim());
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
