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

import jakarta.servlet.http.HttpServletRequest;
import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.service.ApprovalService;
import uk.co.bithatch.opensim.spawner.service.SetupWizardService;

@Controller
public class UiController {

    private final SpawnerProperties properties;
    private final ApprovalService approvalService;
    private final SetupWizardService setupWizardService;

    public UiController(SpawnerProperties properties, ApprovalService approvalService, SetupWizardService setupWizardService) {
        this.properties = properties;
        this.approvalService = approvalService;
        this.setupWizardService = setupWizardService;
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
        return "redirect:/ui/bots.html";
    }

    @GetMapping({ "/ui", "/ui/" })
    public String uiRootRedirect() {
        return "redirect:/ui/index.html";
    }

    @GetMapping(path = "/ui/api/auth/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> authStatus(HttpServletRequest request) {
        var response = new LinkedHashMap<String, Object>();
        response.put("authenticated", UiAuthSupport.isAuthenticated(request.getSession(false)));
        return response;
    }

    @GetMapping(path = "/ui/api/config", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> uiConfig() {
        var response = new LinkedHashMap<String, Object>();
        response.put("gridName", normalize(properties.getOpensimGridName()));
        response.put("gridNick", normalize(properties.getOpensimGridNick()));
        return response;
    }

    @PostMapping(path = "/ui/api/auth/login", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> login(@RequestParam String username,
            @RequestParam String password,
            HttpServletRequest request) {
        var expectedUser = normalize(properties.getOpensimConsoleUser());
        var expectedPass = normalize(properties.getOpensimConsolePass());

        if (expectedUser.isEmpty() || expectedPass.isEmpty()) {
            return unauthorized("UI login is not configured. Set OPENSIM_CONSOLE_USER and OPENSIM_CONSOLE_PASS.");
        }

        if (!expectedUser.equals(normalize(username)) || !expectedPass.equals(password == null ? "" : password)) {
            return unauthorized("Invalid credentials.");
        }

        var session = request.getSession(true);
        session.setAttribute(UiAuthSupport.SESSION_AUTH_KEY, Boolean.TRUE);

        var response = new LinkedHashMap<String, Object>();
        response.put("ok", true);
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

    @PostMapping(path = "/ui/api/setup/run", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> runSetupWizard(@RequestBody(required = false) Map<String, Object> payload) {
        return setupWizardService.runSetup(payload);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static ResponseEntity<Map<String, Object>> unauthorized(String message) {
        var response = new LinkedHashMap<String, Object>();
        response.put("ok", false);
        response.put("error", message);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }
}
