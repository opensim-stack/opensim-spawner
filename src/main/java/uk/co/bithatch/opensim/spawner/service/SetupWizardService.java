package uk.co.bithatch.opensim.spawner.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.BotInstanceData;
import uk.co.bithatch.opensim.spawner.domain.BotLevel;
import uk.co.bithatch.opensim.spawner.domain.SimulatorInstanceData;
import uk.co.bithatch.opensim.spawner.domain.SimulatorLevel;

@Service
public class SetupWizardService {

    private static final String DEFAULT_USER_MODEL = "Ruth";

    private final SimulatorProvisioningService simulatorProvisioningService;
    private final BotProvisioningService botProvisioningService;
    private final OpenSimService openSimService;
    private final RandomPasswordService passwordService;

    public SetupWizardService(SimulatorProvisioningService simulatorProvisioningService,
            BotProvisioningService botProvisioningService,
            OpenSimService openSimService,
            RandomPasswordService passwordService,
            SpawnerProperties properties) {
        this.simulatorProvisioningService = simulatorProvisioningService;
        this.botProvisioningService = botProvisioningService;
        this.openSimService = openSimService;
        this.passwordService = passwordService;
        
        if(!properties.getOpensimProvisionMode().equalsIgnoreCase("guided")) {
        	runSetup(Map.of(
					"mode", properties.getOpensimProvisionMode().equalsIgnoreCase("auto") || 
							properties.getOpensimProvisionMode().equalsIgnoreCase("standalone") 
							? "STANDALONE" 
							: "ROBUST",
					"simulator", Map.of(
							"primaryName", properties.getOpensimGridName(),
							"regionName", properties.getOpensimEstateName(),
							"oar", properties.getOpensimEstateArchive(),
							"port", String.valueOf(properties.getFirstPort()),
							"regionX", String.valueOf(properties.getOpensimRegionX()),
							"regionY", String.valueOf(properties.getOpensimRegionY())),
					"user", Map.of(
							"first", properties.getOpensimUserFirst(),
							"last", properties.getOpensimUserLast(),
							"email", properties.getOpensimUserEmail(),
							"password", properties.getOpensimUserPassword()),
					"bot", Map.of(
							"create", properties.isOpensimCreateBotUser(),
							"first", properties.getOpensimBotFirst(),
							"last", properties.getOpensimBotLast(),
			                "level", BotLevel.GOVERNOR.name(),
							"email", properties.getOpensimBotEmail(),
							"appearance", properties.getOpensimBotAppearance(),
							"gender", properties.getOpensimBotGender())));
		}
    }

    public Map<String, Object> runSetup(Map<String, Object> payload) {
        var request = payload == null ? Map.<String, Object>of() : payload;

        var mode = normalize(requiredString(request, "mode", "Setup mode is required."));
        var simulator = mapValue(request.get("simulator"));
        var bot = mapValue(request.get("bot"));
        var user = mapValue(request.get("user"));

        var primarySimulatorName = firstNonBlank(
                stringValue(simulator.get("primaryName")),
                stringValue(simulator.get("name")),
                stringValue(simulator.get("regionName")));
        if (primarySimulatorName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Primary simulator name is required.");
        }

        var regionSimulatorName = firstNonBlank(
                stringValue(simulator.get("regionName")),
                stringValue(simulator.get("name")),
                primarySimulatorName);

        var createBot = boolValue(bot.get("create"));
        var botFirst = firstNonBlank(stringValue(bot.get("first")), normalizeNameFromSimulator(primarySimulatorName));
        var botLast = firstNonBlank(stringValue(bot.get("last")), "Bot");
        var botEmail = stringValue(bot.get("email"));
        var botAppearance = stringValue(bot.get("appearance"));
        var botGender = stringValue(bot.get("gender"));
        var botLevel = firstNonBlank(stringValue(bot.get("level")), BotLevel.GOVERNOR.name());

        var userFirst = firstNonBlank(stringValue(user.get("first")), normalizeNameFromSimulator(primarySimulatorName));
        var userLast = firstNonBlank(stringValue(user.get("last")), "User");
        var userEmail = requiredString(user, "email", "User email is required.");
        var userPassword = requiredString(user, "password", "User password is required.");

        var ownerFirst = createBot ? botFirst : userFirst;
        var ownerLast = createBot ? botLast : userLast;
        var ownerEmail = createBot ? botEmail : userEmail;
        var ownerPassword = createBot ? passwordService.nextPassword() : userPassword;
        var ownerUuid = UUID.randomUUID().toString();

        var regionUuid = UUID.randomUUID().toString();
        var regionX = firstNonBlank(stringValue(simulator.get("regionX")), "1000");
        var regionY = firstNonBlank(stringValue(simulator.get("regionY")), "1000");
        var regionOar = stringValue(simulator.get("oar"));
        var regionPort = stringValue(simulator.get("port"));

        var primaryLevel = parsePrimaryLevel(mode);
        var regionFields = buildRegionOwnerFields(
                regionPort,
                ownerPassword,
                ownerFirst,
                ownerLast,
                ownerEmail,
                ownerUuid,
                regionSimulatorName,
                regionUuid,
                regionX,
                regionY,
                regionOar);

        var created = new LinkedHashMap<String, Object>();
        SimulatorInstanceData primarySimulator;
        SimulatorInstanceData secondaryGridSimulator = null;
        BotInstanceData createdBot = null;

        try {
            if (primaryLevel == SimulatorLevel.STANDALONE) {
                primarySimulator = simulatorProvisioningService.createSim(primarySimulatorName, SimulatorLevel.STANDALONE.name(),
                        regionFields);
            } else {
                primarySimulator = simulatorProvisioningService.createSim(primarySimulatorName, SimulatorLevel.ROBUST.name(),
                        Map.of());

                var effectiveGridSimulatorName = regionSimulatorName.equalsIgnoreCase(primarySimulatorName)
                        ? primarySimulatorName + "-grid"
                        : regionSimulatorName;
                secondaryGridSimulator = simulatorProvisioningService.createSim(
                        effectiveGridSimulatorName,
                        SimulatorLevel.GRID.name(),
                        regionFields);
            }

            if (createBot) {
                var botFields = new LinkedHashMap<String, String>();
                botFields.put("uuid", ownerUuid);
                botFields.put("password", ownerPassword);
                if (!botEmail.isBlank()) {
                    botFields.put("email", botEmail);
                }
                if (!botAppearance.isBlank()) {
                    botFields.put("appearance", botAppearance);
                }
                if (!botGender.isBlank()) {
                    botFields.put("gender", botGender);
                }
                createdBot = botProvisioningService.createBot(botFirst, botLast, botLevel, botFields);
            }

            var userUuid = createBot ? UUID.randomUUID().toString() : ownerUuid;
            openSimService.createUser(userFirst, userLast, userPassword, userEmail, userUuid, DEFAULT_USER_MODEL);
            if (createBot) {
                botProvisioningService.addHandler("*", "*", userFirst, userLast);
            }

            created.put("primarySimulator", simulatorProvisioningService.toResponse(primarySimulator));
            if (secondaryGridSimulator != null) {
                created.put("gridSimulator", simulatorProvisioningService.toResponse(secondaryGridSimulator));
            }
            if (createdBot != null) {
                created.put("bot", botProvisioningService.toResponse(createdBot));
            }

            var response = new LinkedHashMap<String, Object>();
            response.put("ok", true);
            response.put("mode", primaryLevel.name());
            response.put("ownerFirst", ownerFirst);
            response.put("ownerLast", ownerLast);
            response.put("ownerUuid", ownerUuid);
            response.put("regionUuid", regionUuid);
            response.put("userFirst", userFirst);
            response.put("userLast", userLast);
            response.put("created", created);
            return response;
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private static SimulatorLevel parsePrimaryLevel(String mode) {
        var normalized = normalize(mode).toUpperCase();
        return switch (normalized) {
            case "STANDALONE" -> SimulatorLevel.STANDALONE;
            case "ROBUST" -> SimulatorLevel.ROBUST;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported setup mode '" + mode + "'. Supported: STANDALONE, ROBUST.");
        };
    }

    private static Map<String, String> buildRegionOwnerFields(
            String port,
            String ownerPassword,
            String ownerFirst,
            String ownerLast,
            String ownerEmail,
            String ownerUuid,
            String regionName,
            String regionUuid,
            String regionX,
            String regionY,
            String oar) {
        var fields = new LinkedHashMap<String, String>();
        if (!normalize(port).isBlank()) {
            fields.put("port", normalize(port));
        }
        fields.put("ownerPassword", requiredNonBlank(ownerPassword, "Owner password is required."));
        fields.put("ownerFirst", requiredNonBlank(ownerFirst, "Owner first name is required."));
        fields.put("ownerLast", requiredNonBlank(ownerLast, "Owner last name is required."));
        if (!normalize(ownerEmail).isBlank()) {
            fields.put("ownerEmail", normalize(ownerEmail));
        }
        fields.put("ownerUuid", requiredNonBlank(ownerUuid, "Owner UUID is required."));
        fields.put("regionName", requiredNonBlank(regionName, "Region name is required."));
        fields.put("regionUuid", requiredNonBlank(regionUuid, "Region UUID is required."));
        fields.put("regionX", requiredNonBlank(regionX, "Region X is required."));
        fields.put("regionY", requiredNonBlank(regionY, "Region Y is required."));
        if (!normalize(oar).isBlank()) {
            fields.put("oar", normalize(oar));
        }
        return fields;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static String requiredString(Map<String, Object> map, String field, String message) {
        return requiredNonBlank(stringValue(map.get(field)), message);
    }

    private static String requiredNonBlank(String value, String message) {
        var normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(stringValue(value));
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

    private static String normalizeNameFromSimulator(String simulatorName) {
        return normalize(simulatorName).replaceAll("\\s+", "");
    }
}
