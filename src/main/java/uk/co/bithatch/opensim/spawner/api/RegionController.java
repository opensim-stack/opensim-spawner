package uk.co.bithatch.opensim.spawner.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import uk.co.bithatch.opensim.spawner.service.OpenSimService;
import uk.co.bithatch.opensim.spawner.service.SimulatorProvisioningService;

@RestController
@RequestMapping("/api/simulator/{name}/regions/{regionId}")
public class RegionController {

    private final SimulatorProvisioningService provisioningService;
    private final OpenSimService openSimService;

    public RegionController(SimulatorProvisioningService provisioningService, OpenSimService openSimService) {
        this.provisioningService = provisioningService;
        this.openSimService = openSimService;
    }

    @PatchMapping(path = "/options", consumes = {
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public OpenSimService.RegionData updateRegionOptions(@PathVariable String name,
            @PathVariable String regionId,
            @RequestParam(defaultValue = "true") boolean isPublic,
            @RequestParam(defaultValue = "true") boolean enableVoice) {
        requireSimulator(name);
        try {
            return openSimService.modifyRegion(name, regionId, new OpenSimService.RegionOptionsData(isPublic, enableVoice));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PatchMapping(path = "/actions", consumes = {
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> runRegionAction(@PathVariable String name,
            @PathVariable String regionId,
            @RequestParam String action) {
        requireSimulator(name);
        var normalizedAction = action == null ? "" : action.trim().toLowerCase();
        try {
            switch (normalizedAction) {
                case "restart" -> openSimService.restartRegion(name, regionId);
                case "close" -> openSimService.closeRegion(name, regionId);
                case "delete" -> openSimService.deleteRegion(name, regionId);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unsupported action '" + action + "'. Supported actions: restart, close, delete.");
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }

        var response = new LinkedHashMap<String, Object>();
        response.put("name", name);
        response.put("regionId", regionId);
        response.put("action", normalizedAction);
        response.put("ok", true);
        return response;
    }

    @DeleteMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> deleteRegion(@PathVariable String name,
            @PathVariable String regionId) {
        requireSimulator(name);
        try {
            openSimService.deleteRegion(name, regionId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        var response = new LinkedHashMap<String, Object>();
        response.put("name", name);
        response.put("regionId", regionId);
        response.put("deleted", true);
        return response;
    }

    private void requireSimulator(String name) {
        if (!provisioningService.exists(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulator not found.");
        }
    }
}
