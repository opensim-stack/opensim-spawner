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

import uk.co.bithatch.opensim.spawner.domain.BotInstanceData;
import uk.co.bithatch.opensim.spawner.service.BotProvisioningService;

@RestController
@RequestMapping("/api/bot")
public class BotController {

    private static final Logger LOG = LoggerFactory.getLogger(BotController.class);

    private final BotProvisioningService provisioningService;

    public BotController(BotProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> listBots() {
        return provisioningService.listBotNames();
    }

    @GetMapping(path = "/{first}/{last}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getBotStatus(@PathVariable String first, @PathVariable String last) {
        return provisioningService.getBotContainerStatus(first, last);
    }

    @PostMapping(path = "/{first}/{last}", consumes = {
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> createBot(@PathVariable String first,
            @PathVariable String last,
            @RequestParam(required = false) String level,
            @RequestParam Map<String, String> fields) {
        try {
            var bot = provisioningService.createBot(first, last, level, fields);
            return toResponse(bot);
        } catch (IllegalArgumentException e) {
            LOG.error("Failed to create OpenSim user {} {}.", first, last, e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @DeleteMapping(path = "/{first}/{last}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> deleteBot(@PathVariable String first, @PathVariable String last) {
        provisioningService.deleteBot(first, last);
        var response = new LinkedHashMap<String, Object>();
        response.put("first", first);
        response.put("last", last);
        response.put("deleted", true);
        return response;
    }

    @PatchMapping(path = "/{first}/{last}", consumes = {
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> patchBot(@PathVariable String first,
            @PathVariable String last,
            @RequestParam String action) {
        var normalizedAction = action == null ? "" : action.trim().toLowerCase();
        switch (normalizedAction) {
            case "restart" -> provisioningService.restartBot(first, last);
            case "start" -> provisioningService.startBot(first, last);
            case "stop" -> provisioningService.stopBot(first, last);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported action '" + action + "'. Supported actions: start, stop, restart.");
        }

        var response = new LinkedHashMap<String, Object>();
        response.put("first", first);
        response.put("last", last);
        response.put("action", normalizedAction);
        response.put("ok", true);
        return response;
    }

    private static Map<String, Object> toResponse(BotInstanceData bot) {
        var response = new LinkedHashMap<String, Object>();
        response.put("first", bot.getFirst());
        response.put("last", bot.getLast());
        response.put("level", bot.getLevel().name());
        response.put("parent", bot.getParent());
        response.put("password", bot.getPassword());
        response.put("email", bot.getEmail());
        response.put("uuid", bot.getUuid());
        response.put("model", bot.getModel());
        return response;
    }
}
