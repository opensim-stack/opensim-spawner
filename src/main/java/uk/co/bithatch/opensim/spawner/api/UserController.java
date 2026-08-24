package uk.co.bithatch.opensim.spawner.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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

import uk.co.bithatch.opensim.spawner.service.OpenSimService;
import uk.co.bithatch.opensim.spawner.service.SimulatorProvisioningService;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final OpenSimService openSimService;
    private final SimulatorProvisioningService simulatorProvisioningService;

    public UserController(OpenSimService openSimService, SimulatorProvisioningService simulatorProvisioningService) {
        this.openSimService = openSimService;
        this.simulatorProvisioningService = simulatorProvisioningService;
    }

    @PostMapping(consumes = {
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> createUser(@RequestParam String first,
            @RequestParam String last,
            @RequestParam String password,
            @RequestParam String email,
            @RequestParam String model) {
        ensureGridLoginServiceAvailable();
        var uuid = UUID.randomUUID().toString();
        openSimService.createUser(first, last, password, email, uuid, model);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("created", true);
        response.put("first", first);
        response.put("last", last);
        response.put("email", email);
        response.put("model", model);
        response.put("uuid", uuid);
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
