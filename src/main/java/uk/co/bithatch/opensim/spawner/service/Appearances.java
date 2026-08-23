package uk.co.bithatch.opensim.spawner.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import uk.co.bithatch.opensim.spawner.domain.Gender;

@Service
public class Appearances {

    private final List<String> appearanceNames;
    private final Map<String, String> archivesByKey;

    public Appearances() {
        var properties = loadProperties();
        this.appearanceNames = List.copyOf(parseAppearances(properties));
        this.archivesByKey = Map.copyOf(parseArchiveMap(properties, appearanceNames));
    }

    public List<String> listAppearanceNames() {
        return appearanceNames;
    }

    public String getInventoryArchive(String name, Gender gender) {
        var normalizedName = normalize(name);
        if (normalizedName.isEmpty()) {
            return null;
        }

        if (gender != null) {
            var value = archivesByKey.get(normalizedName + "." + gender.name().toLowerCase(Locale.ROOT));
            if (value != null) {
                return value;
            }
        } else {
            var value = archivesByKey.get(normalizedName);
            if (value != null) {
                return value;
            }
        }

        return findFirstByPrefix(normalizedName);
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    private String findFirstByPrefix(String normalizedName) {
        for (var appearanceName : appearanceNames) {
            if (!appearanceName.startsWith(normalizedName)) {
                continue;
            }

            var direct = archivesByKey.get(appearanceName);
            if (direct != null) {
                return direct;
            }
            var male = archivesByKey.get(appearanceName + ".male");
            if (male != null) {
                return male;
            }
            var female = archivesByKey.get(appearanceName + ".female");
            if (female != null) {
                return female;
            }
            var neutral = archivesByKey.get(appearanceName + ".neutral");
            if (neutral != null) {
                return neutral;
            }
        }
        return null;
    }

    private static Properties loadProperties() {
        var properties = new Properties();
        var resource = new ClassPathResource("appearances.properties");
        try (var input = resource.getInputStream()) {
            properties.load(input);
            return properties;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load appearances.properties.", e);
        }
    }

    private static List<String> parseAppearances(Properties properties) {
        var value = properties.getProperty("appearances", "");
        var names = new ArrayList<String>();
        for (var part : value.split(",")) {
            var normalized = normalize(part);
            if (!normalized.isEmpty()) {
                names.add(normalized);
            }
        }
        return names;
    }

    private static Map<String, String> parseArchiveMap(Properties properties, List<String> appearanceNames) {
        var map = new LinkedHashMap<String, String>();
        for (var appearanceName : appearanceNames) {
            putIfPresent(properties, map, appearanceName);
            putIfPresent(properties, map, appearanceName + ".male");
            putIfPresent(properties, map, appearanceName + ".female");
            putIfPresent(properties, map, appearanceName + ".neutral");
        }
        return map;
    }

    private static void putIfPresent(Properties properties, Map<String, String> map, String key) {
        var value = properties.getProperty(key);
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }
}