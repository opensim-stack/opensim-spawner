package uk.co.bithatch.opensim.spawner.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.service.ApprovalService;
import uk.co.bithatch.opensim.spawner.service.OpenSimService;
import uk.co.bithatch.opensim.spawner.service.SimulatorProvisioningService;
import uk.co.bithatch.opensim.spawner.service.SetupWizardService;
import uk.co.bithatch.opensim.spawner.state.GridStateRepository;

@Controller
public class UiController {

    private final SpawnerProperties properties;
    private final ApprovalService approvalService;
    private final SetupWizardService setupWizardService;
    private final OpenSimService openSimService;
    private final SimulatorProvisioningService simulatorProvisioningService;
    private final GridStateRepository gridStateRepository;

    public UiController(SpawnerProperties properties,
            ApprovalService approvalService,
            SetupWizardService setupWizardService,
            OpenSimService openSimService,
            SimulatorProvisioningService simulatorProvisioningService,
            GridStateRepository gridStateRepository) {
        this.properties = properties;
        this.approvalService = approvalService;
        this.setupWizardService = setupWizardService;
        this.openSimService = openSimService;
        this.simulatorProvisioningService = simulatorProvisioningService;
        this.gridStateRepository = gridStateRepository;
    }

    @GetMapping("/")
    public String rootRedirect() {
        return "redirect:/index.html";
    }

    @GetMapping("/index.html")
    public String indexRedirect() {
        return "redirect:/ui/index.html";
    }

    @GetMapping("/ui/index.html")
    public String uiIndexRedirect() {
        if (requiresGuidedSetup()) {
            return "redirect:/ui/setup.html";
        }
        return "redirect:/ui/bots.html";
    }

    @GetMapping({ "/ui", "/ui/" })
    public String uiRootRedirect() {
        return "redirect:/ui/index.html";
    }

    @GetMapping(path = "/ui/api/auth/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> authStatus(HttpServletRequest request) {
        var session = request.getSession(false);
        var response = new LinkedHashMap<String, Object>();
        var authenticated = UiAuthSupport.isAuthenticated(session);
        response.put("authenticated", authenticated);
        response.put("admin", UiAuthSupport.isAdmin(session));
        response.put("first", authenticated ? UiAuthSupport.authenticatedUserFirst(session) : "");
        response.put("last", authenticated ? UiAuthSupport.authenticatedUserLast(session) : "");
        return response;
    }

    @GetMapping(path = "/ui/api/auth/grid-status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> gridStatus() {
        var response = new LinkedHashMap<String, Object>();
        response.put("available", simulatorProvisioningService.hasActiveGridLoginService());
        return response;
    }

    @GetMapping(path = "/ui/api/setup/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> setupStatus() {
        var response = new LinkedHashMap<String, Object>();
        response.put("guided", isGuidedProvisioningMode());
        response.put("required", requiresGuidedSetup());
        return response;
    }

    @GetMapping(path = "/ui/api/config", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> uiConfig() {
        var gridState = gridStateRepository.get();
        var response = new LinkedHashMap<String, Object>();
        response.put("gridName", firstNonBlank(gridState.getName(), properties.getOpensimGridName()));
        response.put("gridNick", firstNonBlank(gridState.getNick(), properties.getOpensimGridNick()));
        response.put("welcomeMessage", firstNonBlank(gridState.getWelcomeMessage(), properties.getOpensimWelcomeMessage()));
        response.put("consoleUser", normalize(gridState.getConsoleUser()));
        return response;
    }

    @PostMapping(path = "/ui/api/auth/login", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> login(@RequestParam String username,
            @RequestParam String password,
            HttpServletRequest request) {
        var gridState = gridStateRepository.get();
        var expectedUser = normalize(gridState.getConsoleUser());
        var expectedPass = normalize(gridState.getConsolePass());
        var session = request.getSession(true);

        if (!expectedUser.isEmpty() && !expectedPass.isEmpty()
                && expectedUser.equals(normalize(username))
                && expectedPass.equals(password == null ? "" : password)) {
            UiAuthSupport.markAdminAuthenticated(session);
            var response = new LinkedHashMap<String, Object>();
            response.put("ok", true);
            response.put("admin", true);
            return ResponseEntity.ok(response);
        }

        if (!simulatorProvisioningService.hasActiveGridLoginService()) {
            return unauthorized("Invalid credentials.");
        }

        var name = parseUserName(username);
        if (name == null) {
            return unauthorized("Use your OpenSim user name as 'First Last'.");
        }

        var pass = password == null ? new char[0] : password.toCharArray();
        var authenticated = openSimService.authenticate(name.first(), name.last(), pass);
        java.util.Arrays.fill(pass, '\0');
        if (!authenticated) {
            return unauthorized("Invalid credentials.");
        }

        UiAuthSupport.markUserAuthenticated(session, name.first(), name.last());

        var response = new LinkedHashMap<String, Object>();
        response.put("ok", true);
        response.put("admin", false);
        response.put("first", name.first());
        response.put("last", name.last());
        return ResponseEntity.ok(response);
    }

    @PostMapping(path = "/ui/api/auth/register", consumes = {
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> register(@RequestParam String first,
            @RequestParam String last,
            @RequestParam String email,
            @RequestParam String password) {
        var approval = approvalService.createApproval(first, last, email, password);
        var response = new LinkedHashMap<String, Object>();
        response.put("ok", true);
        response.put("first", approval.getFirst());
        response.put("last", approval.getLast());
        response.put("email", approval.getEmail());
        return response;
    }

    @PostMapping(path = "/ui/api/auth/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        var response = new LinkedHashMap<String, Object>();
        response.put("ok", true);
        return response;
    }

    @PostMapping(path = "/ui/api/auth/change-password", consumes = {
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> changeOwnPassword(@RequestParam String oldPassword,
            @RequestParam String newPassword,
            HttpServletRequest request) {
        var session = request.getSession(false);
        if (!UiAuthSupport.isAuthenticated(session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated.");
        }
        if (UiAuthSupport.isAdmin(session)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Admin users cannot use this endpoint. Use the Users page to reset passwords.");
        }
        if (!simulatorProvisioningService.hasActiveGridLoginService()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Password change is unavailable because no active ROBUST/STANDALONE simulator is online.");
        }

        var first = UiAuthSupport.authenticatedUserFirst(session);
        var last = UiAuthSupport.authenticatedUserLast(session);
        if (first.isEmpty() || last.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Session is not bound to an OpenSim user account.");
        }

        var normalizedNewPassword = normalize(newPassword);
        if (normalizedNewPassword.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password is required.");
        }

        var oldPass = oldPassword == null ? new char[0] : oldPassword.toCharArray();
        var valid = openSimService.authenticate(first, last, oldPass);
        java.util.Arrays.fill(oldPass, '\0');
        if (!valid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Old password is incorrect.");
        }

        openSimService.resetUserPassword(first, last, normalizedNewPassword);

        var response = new LinkedHashMap<String, Object>();
        response.put("ok", true);
        response.put("first", first);
        response.put("last", last);
        return response;
    }

    @PostMapping(path = "/ui/api/setup/run", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> runSetupWizard(@RequestBody(required = false) Map<String, Object> payload,
            HttpServletRequest request) {
        // First guided setup can be run anonymously. All other setup operations require admin.
        var allowedAnonymousFirstRun = requiresGuidedSetup();
        if (!allowedAnonymousFirstRun && !UiAuthSupport.isAdmin(request.getSession(false))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access is required.");
        }
        return setupWizardService.runSetup(payload);
    }

    private boolean requiresGuidedSetup() {
        if (!isGuidedProvisioningMode()) {
            return false;
        }
        return !gridStateRepository.get().isInitialized();
    }

    private boolean isGuidedProvisioningMode() {
        return "guided".equalsIgnoreCase(normalize(properties.getOpensimProvisionMode()));
    }

    private static UserName parseUserName(String raw) {
        var value = normalize(raw);
        if (value.isEmpty()) {
            return null;
        }
        var parts = value.split("\\s+");
        if (parts.length < 2) {
            return null;
        }
        var first = parts[0];
        var last = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)).trim();
        if (first.isEmpty() || last.isEmpty()) {
            return null;
        }
        return new UserName(first, last);
    }

    private record UserName(String first, String last) {
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (var value : values) {
            var normalized = normalize(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private static ResponseEntity<Map<String, Object>> unauthorized(String message) {
        var response = new LinkedHashMap<String, Object>();
        response.put("ok", false);
        response.put("error", message);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }
}
