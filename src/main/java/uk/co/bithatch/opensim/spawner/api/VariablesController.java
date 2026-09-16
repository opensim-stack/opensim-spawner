package uk.co.bithatch.opensim.spawner.api;

import static uk.co.bithatch.opensim.spawner.state.BotStateRepository.key;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import uk.co.bithatch.opensim.spawner.config.ConfigItem;
import uk.co.bithatch.opensim.spawner.config.VariableType;
import uk.co.bithatch.opensim.spawner.domain.AddOnInstanceData;
import uk.co.bithatch.opensim.spawner.domain.BotInstanceData;
import uk.co.bithatch.opensim.spawner.domain.Component;
import uk.co.bithatch.opensim.spawner.domain.ContainerGroupInstanceData;
import uk.co.bithatch.opensim.spawner.domain.SimulatorInstanceData;
import uk.co.bithatch.opensim.spawner.domain.StackState;
import uk.co.bithatch.opensim.spawner.service.AddOnInstanceProvisioningService;
import uk.co.bithatch.opensim.spawner.service.BotLevelProfileService;
import uk.co.bithatch.opensim.spawner.service.BotProvisioningService;
import uk.co.bithatch.opensim.spawner.service.SimulatorLevelProfileService;
import uk.co.bithatch.opensim.spawner.service.SimulatorProvisioningService;
import uk.co.bithatch.opensim.spawner.service.StackLevelProfileService;
import uk.co.bithatch.opensim.spawner.service.StackProvisioningService;
import uk.co.bithatch.opensim.spawner.state.AddOnInstanceStateRepository;
import uk.co.bithatch.opensim.spawner.state.AddOnRepository;
import uk.co.bithatch.opensim.spawner.state.BotStateRepository;
import uk.co.bithatch.opensim.spawner.state.SimulatorStateRepository;
import uk.co.bithatch.opensim.spawner.state.StackStateRepository;

@RestController
@RequestMapping("/ui/api/variables")
public class VariablesController {

    private final AddOnRepository addOnRepository;
    private final AddOnInstanceStateRepository addOnInstanceStateRepository;
    private final BotStateRepository botStateRepository;
    private final SimulatorStateRepository simulatorStateRepository;
    private final StackStateRepository stackStateRepository;

    private final BotLevelProfileService botLevelProfileService;
    private final SimulatorLevelProfileService simulatorLevelProfileService;
    private final StackLevelProfileService stackLevelProfileService;

    private final AddOnInstanceProvisioningService addOnService;
    private final BotProvisioningService botProvisioningService;
    private final SimulatorProvisioningService simulatorProvisioningService;
    private final StackProvisioningService stackProvisioningService;

    public VariablesController(AddOnRepository addOnRepository,
            AddOnInstanceStateRepository addOnInstanceStateRepository,
            BotStateRepository botStateRepository,
            SimulatorStateRepository simulatorStateRepository,
            StackStateRepository stackStateRepository,
            BotLevelProfileService botLevelProfileService,
            SimulatorLevelProfileService simulatorLevelProfileService,
            StackLevelProfileService stackLevelProfileService,
            AddOnInstanceProvisioningService addOnService,
            BotProvisioningService botProvisioningService,
            SimulatorProvisioningService simulatorProvisioningService,
            StackProvisioningService stackProvisioningService) {
        this.addOnRepository = addOnRepository;
        this.addOnInstanceStateRepository = addOnInstanceStateRepository;
        this.botStateRepository = botStateRepository;
        this.simulatorStateRepository = simulatorStateRepository;
        this.stackStateRepository = stackStateRepository;
        this.botLevelProfileService = botLevelProfileService;
        this.simulatorLevelProfileService = simulatorLevelProfileService;
        this.stackLevelProfileService = stackLevelProfileService;
        this.addOnService = addOnService;
        this.botProvisioningService = botProvisioningService;
        this.simulatorProvisioningService = simulatorProvisioningService;
        this.stackProvisioningService = stackProvisioningService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getVariables(@RequestParam String type,
            @RequestParam(required = false) String name,
            HttpServletRequest request) {
        requireAdmin(request);

        var target = resolveTarget(type, name);
        var response = new LinkedHashMap<String, Object>();
        response.put("type", target.type().name());
        response.put("name", target.name());
        response.put("displayName", target.displayName());
        response.put("redirect", redirectPathFor(target.type()));

        var requestFields = target.instance().getRequestFields() == null
                ? Map.<String, String>of()
                : target.instance().getRequestFields();

        var configuration = target.component().getConfiguration().stream()
                .map((item) -> toConfigurationItem(item, requestFields, target.component().getConstants()))
                .toList();
        response.put("configuration", configuration);
        return response;
    }

    @PatchMapping(consumes = {
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> saveVariables(@RequestParam String type,
            @RequestParam(required = false) String name,
            @RequestParam Map<String, String> values,
            HttpServletRequest request) {
        requireAdmin(request);

        var target = resolveTarget(type, name);
        var requested = new LinkedHashMap<>(values);
        requested.remove("type");
        requested.remove("name");

        var requestFields = sanitizeRequestFields(target.component().getConfiguration(), requested);

        switch (target.type()) {
            case ADD_ON -> addOnService.reconfigureAddOn(target.name(), requestFields);
            case BOT -> botProvisioningService.reconfigureBot(target.name(), requestFields);
            case SIMULATOR -> simulatorProvisioningService.reconfigureSimulator(target.name(), requestFields);
            case STACK -> stackProvisioningService.reconfigureStack(requestFields);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported variable target type.");
        }

        var response = new LinkedHashMap<String, Object>();
        response.put("ok", true);
        response.put("type", target.type().name());
        response.put("name", target.name());
        response.put("redirect", redirectPathFor(target.type()));
        return response;
    }

    private Target resolveTarget(String typeValue, String nameValue) {
        final VariableTargetType type;
        try {
            type = VariableTargetType.valueOf(normalize(typeValue).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid type. Supported values: ADD_ON, STACK, BOT, SIMULATOR.");
        }

        return switch (type) {
            case ADD_ON -> resolveAddOnTarget(nameValue);
            case BOT -> resolveBotTarget(nameValue);
            case SIMULATOR -> resolveSimulatorTarget(nameValue);
            case STACK -> resolveStackTarget();
        };
    }

    private Target resolveAddOnTarget(String nameValue) {
        var name = requireName(nameValue, "add-on name");
        var component = addOnRepository.load(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Add-on not found."));
        var instance = addOnInstanceStateRepository.load(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Add-on is not enabled. Enable it before configuring variables."));
        return new Target(VariableTargetType.ADD_ON, name, instance.displayName(), component, instance);
    }

    private Target resolveBotTarget(String nameValue) {
        var reference = requireName(nameValue, "bot name");
        var bot = botStateRepository.load(reference)
                .orElseGet(() -> findBotByDisplayName(reference).orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Bot not found.")));
        var component = botLevelProfileService.component();
        return new Target(VariableTargetType.BOT, bot.getName(), bot.displayName(), component, bot);
    }

    private java.util.Optional<BotInstanceData> findBotByDisplayName(String reference) {
        var normalized = normalize(reference);
        if (normalized.isBlank()) {
            return java.util.Optional.empty();
        }
        var split = normalized.split("\\s+", 2);
        if (split.length == 2) {
            var byKey = botStateRepository.load(key(split[0], split[1]));
            if (byKey.isPresent()) {
                return byKey;
            }
        }
        return botStateRepository.list().stream()
                .filter(bot -> normalized.equalsIgnoreCase(bot.displayName()))
                .findFirst();
    }

    private Target resolveSimulatorTarget(String nameValue) {
        var name = requireName(nameValue, "simulator name");
        var simulator = simulatorStateRepository.load(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulator not found."));
        var component = simulatorLevelProfileService.component();
        return new Target(VariableTargetType.SIMULATOR, name, simulator.displayName(), component, simulator);
    }

    private Target resolveStackTarget() {
        var stack = stackStateRepository.get();
        var component = stackLevelProfileService.component();
        return new Target(VariableTargetType.STACK, stack.getName(), stack.displayName(), component, stack);
    }

    private static Map<String, Object> toConfigurationItem(ConfigItem item,
            Map<String, String> requestFields,
            Map<String, String> constants) {
        var response = new LinkedHashMap<String, Object>();
        var configured = requestFields.containsKey(item.name());
        var requestValue = configured ? requestFields.get(item.name()) : "";
        var defaultValue = constants.getOrDefault(item.name(), "");

        response.put("name", item.name());
        response.put("type", item.type().name());
        response.put("description", item.description());
        response.put("choices", choicesFor(item));
        response.put("hasRequestValue", configured);
        response.put("requestValue", requestValue == null ? "" : requestValue);
        response.put("defaultValue", defaultValue == null ? "" : defaultValue);
        return response;
    }

    private static List<String> choicesFor(ConfigItem item) {
        if (item.choices() == null || item.choices().length == 0) {
            return List.of();
        }
        var choices = new java.util.ArrayList<String>();
        for (var choice : item.choices()) {
            if (choice != null && !choice.isBlank()) {
                choices.add(choice);
            }
        }
        return choices;
    }

    private static Map<String, String> sanitizeRequestFields(List<ConfigItem> configuration, Map<String, String> values) {
        var itemsByName = new LinkedHashMap<String, ConfigItem>();
        for (var item : configuration) {
            if (item != null && item.name() != null && !item.name().isBlank()) {
                itemsByName.put(item.name(), item);
            }
        }

        var requestFields = new LinkedHashMap<String, String>();
        for (var itemEntry : itemsByName.entrySet()) {
            var item = itemEntry.getValue();
            var rawValue = values.get(item.name());
            var normalized = normalize(rawValue);
            if (normalized.isBlank()) {
                continue;
            }

            switch (item.type()) {
                case TEXT -> requestFields.put(item.name(), normalized);
                case INTEGER -> {
                    try {
                        Integer.parseInt(normalized);
                    } catch (NumberFormatException e) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Variable '" + item.name() + "' must be an integer.", e);
                    }
                    requestFields.put(item.name(), normalized);
                }
                case BOOLEAN -> requestFields.put(item.name(), String.valueOf(parseBoolean(normalized, item.name())));
                case CHOICE -> requestFields.put(item.name(), resolveChoiceValue(item, normalized));
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unsupported variable type for '" + item.name() + "'.");
            }
        }

        var unknownKeys = new LinkedHashSet<>(values.keySet());
        unknownKeys.removeAll(itemsByName.keySet());
        if (!unknownKeys.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown configuration variables: " + String.join(", ", unknownKeys));
        }

        return requestFields;
    }

    private static String resolveChoiceValue(ConfigItem item, String value) {
        var choices = new java.util.LinkedHashSet<String>();
        for (var choice : choicesFor(item)) {
            choices.add(choice);
        }
        if (choices.contains(value)) {
            return value;
        }

        for (var choice : choices) {
            if (choice.equalsIgnoreCase(value)) {
                return choice;
            }
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Variable '" + item.name() + "' must be one of: " + String.join(", ", choices));
    }

    private static boolean parseBoolean(String value, String name) {
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "on".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value) || "off".equalsIgnoreCase(value)
                || "no".equalsIgnoreCase(value)) {
            return false;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Variable '" + name + "' must be a boolean value (true/false)."
        );
    }

    private static String redirectPathFor(VariableTargetType type) {
        return switch (type) {
            case ADD_ON -> "/ui/add-ons.html";
            case BOT -> "/ui/bots.html";
            case SIMULATOR -> "/ui/simulators.html";
            case STACK -> "/ui/configuration.html";
        };
    }

    private static String requireName(String value, String label) {
        var normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: " + label + ".");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static void requireAdmin(HttpServletRequest request) {
        if (!UiAuthSupport.isAdmin(request.getSession(false))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access is required.");
        }
    }

    private record Target(VariableTargetType type,
            String name,
            String displayName,
            Component<?> component,
            ContainerGroupInstanceData<?> instance) {
    }
}
