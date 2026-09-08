package uk.co.bithatch.opensim.spawner.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import uk.co.bithatch.opensim.spawner.service.BotProvisioningService;
import uk.co.bithatch.opensim.spawner.service.OpenSimService;
import uk.co.bithatch.opensim.spawner.service.SimulatorProvisioningService;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final OpenSimService openSimService;
    private final SimulatorProvisioningService simulatorProvisioningService;
    private final BotProvisioningService botProvisioningService;

    public UserController(OpenSimService openSimService,
            SimulatorProvisioningService simulatorProvisioningService,
            BotProvisioningService botProvisioningService) {
        this.openSimService = openSimService;
        this.simulatorProvisioningService = simulatorProvisioningService;
        this.botProvisioningService = botProvisioningService;
    }

    @PostMapping(consumes = {
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> createUser(@RequestParam String first,
            @RequestParam String last,
            @RequestParam String password,
            @RequestParam(defaultValue = "") String email,
            @RequestParam(defaultValue = "false") boolean botHandler,
            @RequestParam(defaultValue = "") String region,
            @RequestParam(defaultValue = "-1") int x,
            @RequestParam(defaultValue = "-1") int y) {
        ensureGridLoginServiceAvailable();
        
        var uuid = openSimService.createUser(first, last, password, x, y, region, email);
        if (botHandler) {
            botProvisioningService.addHandler("*", "*", first, last);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("created", true);
        response.put("first", first);
        response.put("last", last);
        response.put("email", email);
        response.put("botHandler", botHandler);
        response.put("uuid", uuid);
        return response;
    }

    @GetMapping(path = "/active", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, String>> listActiveUsers(@RequestParam(defaultValue = "false") boolean showAllAgents) {
        ensureGridLoginServiceAvailable();
        var users = openSimService.showActiveUsers();
        if (showAllAgents) {
            return users;
        }
        return users.stream()
                .filter(user -> "root".equalsIgnoreCase(user.getOrDefault("type", "")))
                .toList();
    }

    @GetMapping(path = "/handlers", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, String>> listHandlers() {
        ensureGridLoginServiceAvailable();
        return botProvisioningService.listHandlers().stream()
                .map((handler) -> {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("botFirst", handler.getBotFirst());
                    item.put("botLast", handler.getBotLast());
                    item.put("handlerFirst", handler.getHandlerFirst());
                    item.put("handlerLast", handler.getHandlerLast());
                    return item;
                })
                .toList();
    }

    @PatchMapping(path = "/{first}/{last}/handler", consumes = {
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setHandler(@PathVariable String first,
            @PathVariable String last,
            @RequestParam(defaultValue = "*") String botFirst,
            @RequestParam(defaultValue = "*") String botLast,
            @RequestParam boolean enabled) {
        ensureGridLoginServiceAvailable();
        if (enabled) {
            botProvisioningService.addHandler(botFirst, botLast, first, last);
        } else {
            botProvisioningService.removeHandler(botFirst, botLast, first, last);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("first", first);
        response.put("last", last);
        response.put("enabled", enabled);
        return response;
    }

    @GetMapping(path = "/{first}/{last}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> findUser(@PathVariable String first, @PathVariable String last) {
        ensureGridLoginServiceAvailable();
        var account = openSimService.showAccount(first, last);
        var found = account.containsKey("Name") || account.containsKey("ID");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("found", found);
        response.put("first", first);
        response.put("last", last);
        response.put("account", account);
        return response;
    }

    @PatchMapping(path = "/{first}/{last}/password", consumes = {
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> resetUserPassword(@PathVariable String first,
            @PathVariable String last,
            @RequestParam String password) {
        ensureGridLoginServiceAvailable();
        openSimService.resetUserPassword(first, last, password);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("first", first);
        response.put("last", last);
        return response;
    }

    private void ensureGridLoginServiceAvailable() {
        if (simulatorProvisioningService.hasActiveGridLoginService()) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "User management is unavailable because no active ROBUST/STANDALONE simulator is providing grid login services.");
    }
}
