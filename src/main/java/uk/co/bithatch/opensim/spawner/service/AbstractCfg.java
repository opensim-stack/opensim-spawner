package uk.co.bithatch.opensim.spawner.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.springframework.core.io.ClassPathResource;

public class AbstractCfg {


	protected static List<String> parseNames(String key, Properties properties) {
        var value = properties.getProperty(key, "");
        var names = new ArrayList<String>();
        for (var part : value.split(",")) {
            var normalized = normalize(part);
            if (!normalized.isEmpty()) {
                names.add(normalized);
            }
        }
        return names;
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    protected static Properties loadProperties(String resourcePath) {
        var properties = new Properties();
        var resource = new ClassPathResource(resourcePath);
        try (var input = resource.getInputStream()) {
            properties.load(input);
            return properties;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + resourcePath + ".", e);
        }
    }

    protected static void putIfPresent(Properties properties, Map<String, String> map, String key) {
        var value = properties.getProperty(key);
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }
}
