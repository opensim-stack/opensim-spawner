package uk.co.bithatch.opensim.spawner.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.springframework.stereotype.Service;

import uk.co.bithatch.opensim.spawner.domain.Gender;

@Service
public class Appearances extends AbstractCfg {

    private final List<String> appearanceNames;
    private final Map<String, String> archivesByKey;

    public Appearances() {
        var properties = loadProperties("appearances.properties");
        this.appearanceNames = List.copyOf(parseNames("appearances", properties));
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
}