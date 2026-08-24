package uk.co.bithatch.opensim.spawner.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import uk.co.bithatch.opensim.spawner.domain.SimulatorLevel;
import uk.co.bithatch.opensim.spawner.service.OARs;
import uk.co.bithatch.opensim.spawner.service.SimulatorProvisioningService;

@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {

    private static final Logger LOG = LoggerFactory.getLogger(SimulatorController.class);

    private final SimulatorProvisioningService provisioningService;
    private final OARs oars;

    public SimulatorController(SimulatorProvisioningService provisioningService, OARs oars) {
        this.provisioningService = provisioningService;
        this.oars = oars;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> listSimulators() {
        return provisioningService.listNames();
    }

    @GetMapping(path = "/grid-service", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> gridServiceStatus() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("available", provisioningService.hasActiveGridLoginService());
        return response;
    }

    @GetMapping(path = "/levels", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> listLevels() {
        return List.of(SimulatorLevel.values()).stream()
                .map((level) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", level.name());
                    item.put("regionRequired", level.requiresRegion());
                    return item;
                })
                .toList();
    }

    @GetMapping(path = "/oars", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> listOars() {
        return oars.listDescriptors().stream()
                .map((descriptor) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("key", descriptor.key());
                    item.put("name", descriptor.name());
                    item.put("x", descriptor.sx());
                    item.put("y", descriptor.sy());
                    return item;
                })
                .toList();
    }

    @GetMapping(path = "/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getSimulatorStatus(@PathVariable String name) {
        return provisioningService.getContainerGroupStatus(name);
    }

    @PostMapping(path = "/{name}", consumes = {
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> createSimulator(@PathVariable String name,
            @RequestParam(required = false) String level,
            @RequestParam Map<String, String> fields) {
        try {
            var bot = provisioningService.createSim(name, level, fields);
            return provisioningService.toResponse(bot);
        } catch (IllegalArgumentException e) {
            LOG.error("Failed to create OpenSim user {}.", name, e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @DeleteMapping(path = "/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> deleteSimulator(@PathVariable String name) {
        provisioningService.deleteContainerGroup(name);
        var response = new LinkedHashMap<String, Object>();
        response.put("name", name);
        response.put("deleted", true);
        return response;
    }

    @PatchMapping(path = "/{name}", consumes = {
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> patchBot(@PathVariable String name,
            @RequestParam String action) {
        var normalizedAction = action == null ? "" : action.trim().toLowerCase();
        switch (normalizedAction) {
            case "restart" -> provisioningService.restart(name);
            case "start" -> provisioningService.start(name);
            case "stop" -> provisioningService.stop(name);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported action '" + action + "'. Supported actions: start, stop, restart.");
        }

        var response = new LinkedHashMap<String, Object>();
        response.put("name", name);
        response.put("action", normalizedAction);
        response.put("ok", true);
        return response;
    }

}
