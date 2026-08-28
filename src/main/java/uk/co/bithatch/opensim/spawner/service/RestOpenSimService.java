package uk.co.bithatch.opensim.spawner.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import uk.co.bithatch.opensim.jlib.OpensimRESTConsole;
import uk.co.bithatch.opensim.jlib.OpensimRemoteAdminClient;
import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.SimulatorInstanceData;
import uk.co.bithatch.opensim.spawner.state.GridStateRepository;
import uk.co.bithatch.opensim.spawner.state.SimulatorStateRepository;

@Service
public class RestOpenSimService implements OpenSimService {

    private static final Logger LOG = LoggerFactory.getLogger(RestOpenSimService.class);
    private static final Pattern ESTATE_LINE_PATTERN = Pattern
            .compile("^(?<name>.+?)\\s+(?<id>\\d+)\\s+(?<ownerFirst>\\S+)\\s+(?<ownerLast>\\S+)\\s*$");

    private final SpawnerProperties properties;
    private final SimulatorStateRepository simStateRepository;
    private final GridStateRepository gridStateRepository;
    private final Object consoleOperationLock = new Object();

    @FunctionalInterface
    private interface ConsoleCallback<T> {
        T run(OpensimRESTConsole console);
    }

    public RestOpenSimService(
    		SpawnerProperties properties,
			SimulatorStateRepository stateRepository,
			GridStateRepository gridStateRepository
    	) {
        this.properties = properties;
        this.simStateRepository = stateRepository;
        this.gridStateRepository = gridStateRepository;
    }

    @Override
    public void createUser(String first, String last, String password, String email, String uuid, String model) {
        try {
            LOG.info("Creating OpenSim user {} {} (email={}, uuid={}, model={}).", first, last, email, uuid, model);
            withConsole(console -> {
                console.executeCommand("create", "user", first, last, password, email, uuid, model).toList();
                return null;
            });
            LOG.info("Created OpenSim user {} {}.", first, last);
        } catch (RuntimeException e) {
            throw new ExternalDependencyException("Failed to create OpenSimulator user via REST console. " + e.getMessage(), e);
        }
    }

    @Override
	public void loadRegionArchive(String archivePath) {
	    	 try {
             LOG.info("Loading opensimulator archive '{}''.", archivePath);
             withConsole(console -> {
                 console.executeCommand(
	            		"load", "oar",
	            		archivePath).toList();
                 return null;
             });
             LOG.info("Loaded opensimulator archive '{}'.", archivePath);
         } catch (RuntimeException e) {
             throw new ExternalDependencyException("Failed to load OpenSimulator inventory archive via REST console. " + e.getMessage(), e);
         }
		
	}

	@Override
    public void loadInventoryArchive(String first, String last, String inventoryPath, String password, String archivePath) {
        try {
            LOG.info("Loading inventory archive '{}' into {} {} at '{}'.", archivePath, first, last, inventoryPath);
            withConsole(console -> {
                console.executeCommand(
	            		"load", "iar",
	            		first,
	            		last,
	            		inventoryPath,
	            		password,
	            		archivePath).toList();
                return null;
            });
            LOG.info("Loaded inventory archive '{}' into {} {}.", archivePath, first, last);
        } catch (RuntimeException e) {
            LOG.error("Failed to load inventory archive '{}' into {} {}.", archivePath, first, last, e);
            throw new ExternalDependencyException("Failed to load OpenSimulator inventory archive via REST console. " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteUser(String first, String last) {
        // OpenSimulator user deletion is intentionally not implemented yet.
        LOG.warn("OpenSim delete user requested for {} {}, but deletion is not implemented.", first, last);
        throw new UnsupportedOperationException("OpenSimulator user deletion is not currently supported.");
    }

    @Override
    public Map<String, String> showAccount(String first, String last) {
        try {
            LOG.info("Looking up OpenSim account {} {}.", first, last);
            var output = withConsole(console -> console.executeCommand("show", "account", first, last).toList());
            return parseAccountDetails(output);
        } catch (RuntimeException e) {
            throw new ExternalDependencyException("Failed to query OpenSimulator user via REST console. " + e.getMessage(), e);
        }
    }

    @Override
    public List<Map<String, String>> showActiveUsers() {
        try {
            LOG.info("Listing active OpenSim users.");
            var output = withConsole(console -> console.executeCommand("show", "users", "full").toList());
            return parseActiveUsers(output);
        } catch (RuntimeException e) {
            throw new ExternalDependencyException("Failed to list active OpenSimulator users via REST console. " + e.getMessage(), e);
        }
    }

    @Override
    public void resetUserPassword(String first, String last, String password) {
        try {
            LOG.info("Resetting OpenSim password for {} {}.", first, last);
            withConsole(console -> {
                console.executeCommand("reset", "user", "password", first, last, password).toList();
                return null;
            });
            LOG.info("Reset OpenSim password for {} {}.", first, last);
        } catch (RuntimeException e) {
            throw new ExternalDependencyException("Failed to reset OpenSimulator password via REST console. " + e.getMessage(), e);
        }
    }

    @Override
    public boolean authenticate(String first, String last, char[] password) {
        try {
        	var admin= openRemoteAdmin();
            LOG.info("Authenticating for {} {}.", first, last);
            admin.authenticateUser(first, last, password, 10);
            LOG.info("Authenticated OpenSim user {} {}.", first, last);
            return true;
        } catch (RuntimeException e) {
        	LOG.error("Failed to authenticate OpenSimulator user {} {}.", first, last, e);
        	return false;
        }
    }

    @Override
    public List<RegionData> showRegions(String simulatorName) {
        var simulator = resolveSimulator(simulatorName);
        validateRegionCapableSimulator(simulator);
        try {
            LOG.info("Listing regions for simulator {}.", simulator.getName());
            var output = withConsole(simulator, console -> console.executeCommand("show", "regions").toList());
            return parseRegions(output);
        } catch (RuntimeException e) {
            throw new ExternalDependencyException(
                    "Failed to list OpenSimulator regions via REST console. " + e.getMessage(),
                    e);
        }
    }

    @Override
    public List<EstateData> showEstates(String simulatorName) {
        var simulator = resolveSimulator(simulatorName);
        validateRegionCapableSimulator(simulator);
        try {
            LOG.info("Listing estates for simulator {}.", simulator.getName());
            var output = withConsole(simulator, console -> console.executeCommand("estate", "show").toList());
            return parseEstates(output);
        } catch (RuntimeException e) {
            throw new ExternalDependencyException(
                    "Failed to list OpenSimulator estates via REST console. " + e.getMessage(),
                    e);
        }
    }

    @Override
    public RegionData createRegion(String simulatorName, CreateRegionData request) {
        if (request == null) {
            throw new IllegalArgumentException("Create region request is required.");
        }
        var simulator = resolveSimulator(simulatorName);
        validateRegionCapableSimulator(simulator);

        var regionName = normalizeRequired(request.name(), "Region name is required.");
        var estateName = defaultString(request.estateName(), properties.getOpensimEstateName());
        var ownerFirst = nonBlankOrNull(request.estateOwnerFirst());
        var ownerLast = nonBlankOrNull(request.estateOwnerLast());
        var existingRegions = showRegions(simulatorName);
        var listenPort = nextRegionPort(simulator, existingRegions.size());

        try {
            LOG.info("Creating region '{}' on simulator {} at {},{} in estate {}.",
                    regionName,
                    simulator.getName(),
                    request.x(),
                    request.y(),
                    estateName);
            var builder = OpensimRemoteAdminClient
                    .createRegionBuilder(regionName,
                            "0.0.0.0",
                            listenPort,
                            "127.0.0.1",
                            request.x(),
                            request.y(),
                            estateName)
                    .isPublic(request.isPublic())
                    .enableVoice(request.enableVoice());
            if (ownerFirst != null && ownerLast != null) {
                builder.estateOwnerFirst(ownerFirst).estateOwnerLast(ownerLast);
            }

            var created = openRemoteAdmin(simulator).createRegion(builder.build());
            var refreshed = showRegions(simulatorName);
            return refreshed.stream()
                    .filter(region -> region.id().equalsIgnoreCase(created.regionUuid()))
                    .findFirst()
                    .orElseGet(() -> new RegionData(created.regionName(),
                            created.regionUuid(),
                            request.x(),
                            request.y(),
                            request.x() + "," + request.y(),
                            "",
                            listenPort,
                            false,
                            estateName,
                            List.of()));
        } catch (RuntimeException e) {
            throw new ExternalDependencyException(
                    "Failed to create OpenSimulator region via RemoteAdmin. " + e.getMessage(),
                    e);
        }
    }

    private static Map<String, String> parseAccountDetails(List<String> lines) {
        var details = new LinkedHashMap<String, String>();
        for (var rawLine : lines) {
            if (rawLine == null || rawLine.isBlank()) {
                continue;
            }
            for (var line : rawLine.split("\\R")) {
                var trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                var delimiter = trimmed.indexOf(':');
                if (delimiter <= 0) {
                    continue;
                }
                var key = trimmed.substring(0, delimiter).trim();
                var value = trimmed.substring(delimiter + 1).trim();
                if (!key.isEmpty()) {
                    details.put(key, value);
                }
            }
        }
        return details;
    }

    private static List<Map<String, String>> parseActiveUsers(List<String> lines) {
        var users = new ArrayList<Map<String, String>>();
        for (var rawLine : lines) {
            if (rawLine == null || rawLine.isBlank()) {
                continue;
            }
            for (var line : rawLine.split("\\R")) {
                var trimmed = line.trim();
                if (trimmed.isEmpty()
                        || trimmed.startsWith("Total agents in region")
                        || trimmed.startsWith("Firstname")) {
                    continue;
                }

                var columns = trimmed.split("\\s{2,}");
                if (columns.length < 5) {
                    continue;
                }

                var user = new LinkedHashMap<String, String>();
                user.put("first", columns[0].trim());
                user.put("last", columns[1].trim());
                user.put("agentId", columns[2].trim());
                user.put("type", columns[3].trim());
                user.put("position", columns[4].trim());
                users.add(user);
            }
        }
        return users;
    }

    private static List<RegionData> parseRegions(List<String> lines) {
    	
    	/*
    	 * See weird output here. Seems to double up the header and then the data. Example output:
    	 * 
    	 * Region (LocalSim) #show regions
		 * Name                  ID                                    Position     Size         Port   Ready?  Estate              
		 * LocalSim              31b909f5-8651-4a41-b3d7-56fca5a9d9da  1000,1000    256x256      9000   Yes     LocalSim
		 * Name                  ID                                    Position     Size         Flags                                                       
		 * LocalSim              31b909f5-8651-4a41-b3d7-56fca5a9d9da  1000,1000    256x256      RegionOnline
    	 */
        var regions = new HashMap<String, RegionData>();
        for (var rawLine : lines) {
            if (rawLine == null || rawLine.isBlank()) {
                continue;
            }
            for (var line : rawLine.split("\\R")) {
                var trimmed = line.trim();
                if (trimmed.isEmpty()
                        || trimmed.startsWith("Name")
                        || trimmed.startsWith("---")
                        || trimmed.startsWith("Total regions")) {
                    continue;
                }

                var columns = trimmed.split("\\s{2,}");
                if (columns.length < 5) {
                    continue;
                }

                var position = columns[2].trim();
                var coords = position.split(",");
                var x = parseCoordinate(coords, 0);
                var y = parseCoordinate(coords, 1);
                var uuid = columns[0].trim();
                var region = regions.get(uuid);
                if (region == null && columns.length > 6) {
                	region = new RegionData(uuid,
                            columns[1].trim(),
                            x,
                            y,
                            position,
                            columns[3].trim(),
                            Integer.parseInt(columns[4].trim()),
                            columns[5].equalsIgnoreCase("yes"),
                            columns[6].trim(),
                            Collections.emptyList());
                	regions.put(uuid, region);
                }
                else if(region != null && columns.length > 4) {
                    var flagText = String.join(" ", Arrays.copyOfRange(columns, 4, columns.length)).trim();
                    var flags = Arrays.stream(flagText.split("[,\\s]+"))
                            .map(String::trim)
                            .filter(value -> !value.isEmpty())
                            .toList();
                	regions.put(uuid, region.withFlags(flags));
                }
                else {
                	LOG.error("Failed to parse region line (out of sync?): '{}'.", trimmed);
                }
                
            }
        }
        var lregions = new ArrayList<>(regions.values());
        lregions.sort(Comparator.comparingInt(RegionData::x)
                .thenComparingInt(RegionData::y)
                .thenComparing(RegionData::name, String.CASE_INSENSITIVE_ORDER));
        return lregions;
    }

    private static List<EstateData> parseEstates(List<String> lines) {
        var estates = new ArrayList<EstateData>();
        for (var rawLine : lines) {
            if (rawLine == null || rawLine.isBlank()) {
                continue;
            }
            for (var line : rawLine.split("\\R")) {
                var trimmed = line.trim();
                if (trimmed.isEmpty()
                        || trimmed.startsWith("Estate information for region")
                        || trimmed.startsWith("Estate Name ID Owner")) {
                    continue;
                }

                var matcher = ESTATE_LINE_PATTERN.matcher(trimmed);
                if (!matcher.matches()) {
                    continue;
                }

                estates.add(new EstateData(matcher.group("name").trim(),
                        matcher.group("id").trim(),
                        matcher.group("ownerFirst").trim(),
                        matcher.group("ownerLast").trim()));
            }
        }
        return estates;
    }

    private SimulatorInstanceData resolveSimulator(String simulatorName) {
        var normalizedName = normalizeRequired(simulatorName, "Simulator name is required.");
        return simStateRepository.load(normalizedName)
                .orElseThrow(() -> new IllegalArgumentException("Simulator '" + normalizedName + "' not found."));
    }

    private static void validateRegionCapableSimulator(SimulatorInstanceData simulator) {
        if (simulator.getLevel() == null || !simulator.getLevel().requiresRegion()) {
            throw new IllegalArgumentException("Regions are only supported for GRID and STANDALONE simulators.");
        }
    }

    private int nextRegionPort(SimulatorInstanceData simulator, int knownRegionCount) {
        var basePort = simulator.getPort() <= 0 ? properties.getFirstPort() : simulator.getPort();
        var candidate = basePort + Math.max(0, knownRegionCount);
        if (candidate <= 0 || candidate > 65535) {
            throw new IllegalArgumentException("Calculated region listen port is invalid.");
        }
        return candidate;
    }

    private static int parseCoordinate(String[] coords, int index) {
        if (coords == null || coords.length <= index) {
            return 0;
        }
        try {
            return Integer.parseInt(coords[index].trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String defaultString(String value, String fallback) {
        var normalized = nonBlankOrNull(value);
        if (normalized != null) {
            return normalized;
        }
        var defaultValue = nonBlankOrNull(fallback);
        if (defaultValue != null) {
            return defaultValue;
        }
        throw new IllegalArgumentException("Estate name is required.");
    }

    private static String normalizeRequired(String value, String message) {
        var normalized = nonBlankOrNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private static String nonBlankOrNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private OpensimRemoteAdminClient openRemoteAdmin() {
        var sim = simStateRepository.list().stream().filter(s -> s.getLevel().providesGridService()).findFirst().orElse(null);
		if(sim == null) {
        	throw new IllegalStateException("No login services found. Cannot open OpenSim console.");
        }

        return openRemoteAdmin(sim);
	}   

    private OpensimRemoteAdminClient openRemoteAdmin(SimulatorInstanceData simulator) {
        return new OpensimRemoteAdminClient(simulatorBaseUrl(simulator.getPort()),
                gridStateRepository.get().getAdminToken());
    }

    private OpensimRESTConsole openConsole() {
	    var sim = simStateRepository.list().stream().filter(s -> s.getLevel().providesGridService()).findFirst().orElse(null);
		if(sim == null) {
	        throw new IllegalStateException("No login services found. Cannot open OpenSim console.");
        }

        return openConsole(sim);
    }

    private OpensimRESTConsole openConsole(SimulatorInstanceData simulator) {
        var user = Optional.ofNullable(properties.getOpensimConsoleUser()).filter(value -> !value.isBlank());
        var pass = Optional.ofNullable(properties.getOpensimConsolePass())
                .filter(value -> !value.isBlank())
                .map(String::toCharArray);

        return new OpensimRESTConsole(simulatorBaseUrl(simulator.getPort()), user, pass);
    }

    private <T> T withConsole(ConsoleCallback<T> callback) {
        synchronized (consoleOperationLock) {
            try (var console = openConsole()) {
                return callback.run(console);
            }
        }
    }

    private <T> T withConsole(SimulatorInstanceData simulator, ConsoleCallback<T> callback) {
        synchronized (consoleOperationLock) {
            try (var console = openConsole(simulator)) {
                return callback.run(console);
            }
        }
    }

    private String simulatorBaseUrl(int port) {
        var host = normalizeServiceHost(properties.getOpensimGridServices());
        return "http://" + host + ":" + port;
    }

    private static String normalizeServiceHost(String configuredValue) {
        var raw = configuredValue == null ? "" : configuredValue.trim();
        if (raw.isEmpty()) {
            return "localhost";
        }
        var value = raw.replaceFirst("^https?://", "");
        var slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        if (value.startsWith("[") && value.contains("]")) {
            return value;
        }
        var colon = value.indexOf(':');
        if (colon > 0 && value.indexOf(':', colon + 1) < 0) {
            return value.substring(0, colon);
        }
        return value;
    }
}
