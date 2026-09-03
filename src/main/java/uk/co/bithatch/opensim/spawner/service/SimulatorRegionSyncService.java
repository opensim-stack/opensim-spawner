package uk.co.bithatch.opensim.spawner.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.sshtools.jini.INI;

import jakarta.annotation.PostConstruct;
import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.RegionInstanceData;
import uk.co.bithatch.opensim.spawner.domain.SimulatorInstanceData;
import uk.co.bithatch.opensim.spawner.state.SimulatorStateRepository;

@Service
public class SimulatorRegionSyncService {

    private static final Logger LOG = LoggerFactory.getLogger(SimulatorRegionSyncService.class);

    private final SpawnerProperties properties;
    private final SimulatorStateRepository simulatorStateRepository;

    public SimulatorRegionSyncService(SpawnerProperties properties, SimulatorStateRepository simulatorStateRepository) {
        this.properties = properties;
        this.simulatorStateRepository = simulatorStateRepository;
    }

    @PostConstruct
    public void synchronizeAllOnStartup() {
        synchronizeAll();
    }

    public synchronized void synchronizeAll() {
        for (var simulator : simulatorStateRepository.list()) {
            synchronizeSimulator(simulator.getName());
        }
    }

    public synchronized void synchronizeSimulator(String simulatorName) {
        var simulator = simulatorStateRepository.load(simulatorName).orElse(null);
        if (simulator == null) {
            LOG.warn("Skipping region synchronization for unknown simulator '{}'.", simulatorName);
            return;
        }
        synchronizeSimulator(simulator);
    }

    private void synchronizeSimulator(SimulatorInstanceData simulator) {
        if (simulator.getLevel() == null || !simulator.getLevel().requiresRegion()) {
            return;
        }

        var regionsDir = resolveRegionsDirectory(simulator.getName());
        if (regionsDir == null || !Files.isDirectory(regionsDir)) {
            LOG.info("No regions directory found for simulator {} at {}. Skipping sync.", simulator.getName(), regionsDir);
            return;
        }

        var previousByUuid = new HashMap<String, RegionInstanceData>();
        var previousByName = new HashMap<String, RegionInstanceData>();
        var previousRegions = simulator.getRegions();
        if (previousRegions != null) {
            for (var region : previousRegions) {
                if (region == null) {
                    continue;
                }
                var uuidKey = normalizeKey(region.getUuid());
                if (uuidKey != null) {
                    previousByUuid.put(uuidKey, region);
                }
                var nameKey = normalizeKey(region.getName());
                if (nameKey != null) {
                    previousByName.put(nameKey, region);
                }
            }
        }

        var parsedRegions = parseRegionsFromIniDirectory(regionsDir, previousByUuid, previousByName);
        simulator.setRegions(parsedRegions.toArray(RegionInstanceData[]::new));
        simulatorStateRepository.save(simulator);
        LOG.info("Synchronized {} region(s) from {} for simulator {}.", parsedRegions.size(), regionsDir, simulator.getName());
    }

    private Path resolveRegionsDirectory(String simulatorName) {
        var configDir = properties.getConfigDir();
        if (configDir == null || simulatorName == null || simulatorName.isBlank()) {
            return null;
        }
        return configDir.resolve("sims").resolve(simulatorName).resolve("Regions");
    }

    private List<RegionInstanceData> parseRegionsFromIniDirectory(Path regionsDir,
            Map<String, RegionInstanceData> previousByUuid,
            Map<String, RegionInstanceData> previousByName) {
        var synchronizedRegions = new LinkedHashMap<String, RegionInstanceData>();
        try (var files = Files.list(regionsDir)) {
            var iniFiles = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ini"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();

            for (var iniFile : iniFiles) {
                var ini = INI.fromFile(iniFile);
                for (var sections : ini.sections().values()) {
                    if (sections == null) {
                        continue;
                    }
                    for (var section : sections) {
                        if (section == null) {
                            continue;
                        }
                        var synchronizedRegion = toRegion(section, previousByUuid, previousByName);
                        if (synchronizedRegion == null) {
                            continue;
                        }
                        var regionKey = regionMapKey(synchronizedRegion);
                        synchronizedRegions.put(regionKey, synchronizedRegion);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list simulator regions directory " + regionsDir + ".", e);
        }

        var ordered = new ArrayList<>(synchronizedRegions.values());
        ordered.sort(Comparator.comparingInt(RegionInstanceData::getX)
                .thenComparingInt(RegionInstanceData::getY)
                .thenComparing(region -> region.getName() == null ? "" : region.getName(), String.CASE_INSENSITIVE_ORDER));
        return ordered;
    }

    private RegionInstanceData toRegion(INI.Section section,
            Map<String, RegionInstanceData> previousByUuid,
            Map<String, RegionInstanceData> previousByName) {
        var name = nonBlank(section.key());
        var uuid = nonBlank(section.get("RegionUUID", ""));
        var location = parseLocationValue(section);
        var port = parseInt(nonBlank(section.get("InternalPort", "")), 0);

        var existing = resolveExistingRegion(uuid, name, previousByUuid, previousByName);
        if (existing == null && name == null && uuid == null) {
            return null;
        }

        var region = new RegionInstanceData();
        if (existing != null) {
            region.setWidth(existing.getWidth());
            region.setHeight(existing.getHeight());
            region.setOar(existing.getOar());
        }

        region.setName(name != null ? name : existing == null ? null : existing.getName());
        region.setUuid(uuid != null ? uuid : existing == null ? null : existing.getUuid());

        var xy = parseLocation(location);
        if (xy == null && existing != null) {
            region.setX(existing.getX());
            region.setY(existing.getY());
        } else if (xy != null) {
            region.setX(xy[0]);
            region.setY(xy[1]);
        }

        if (port > 0) {
            region.setPort(port);
        } else if (existing != null) {
            region.setPort(existing.getPort());
        }

        return region;
    }

    private static RegionInstanceData resolveExistingRegion(String uuid,
            String name,
            Map<String, RegionInstanceData> previousByUuid,
            Map<String, RegionInstanceData> previousByName) {
        var uuidKey = normalizeKey(uuid);
        if (uuidKey != null) {
            var byUuid = previousByUuid.get(uuidKey);
            if (byUuid != null) {
                return byUuid;
            }
        }

        var nameKey = normalizeKey(name);
        if (nameKey != null) {
            return previousByName.get(nameKey);
        }

        return null;
    }

    private static int[] parseLocation(String location) {
        if (location == null) {
            return null;
        }
        var split = location.split(",");
        if (split.length < 2) {
            return null;
        }
        return new int[] { parseInt(split[0], 0), parseInt(split[1], 0) };
    }

    private static String parseLocationValue(INI.Section section) {
        var values = section.getAll("Location");
        if (values != null && values.length >= 2) {
            return values[0] + "," + values[1];
        }
        return nonBlank(section.get("Location", ""));
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String nonBlank(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeKey(String value) {
        var normalized = nonBlank(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private static String regionMapKey(RegionInstanceData region) {
        var uuid = normalizeKey(region.getUuid());
        if (uuid != null) {
            return "uuid:" + uuid;
        }
        return "name:" + normalizeKey(region.getName());
    }
}
