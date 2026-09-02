package uk.co.bithatch.opensim.spawner.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.BotInstanceData;

@Component
public class BotStateRepository extends AbstractStateRepository<BotInstanceData> {

    private static final String LEGACY_GRID_STATE_FILE = "grids.json";
	
	public static String key(String first, String last) {
		return first + "-" + last;
	}

    @Autowired
    public BotStateRepository(ObjectMapper objectMapper, SpawnerProperties properties) {
        super(objectMapper, properties.getDataDir().resolve("bots"), BotInstanceData.class);
        migrateLegacyBotFiles(objectMapper, properties.getDataDir(), properties.getDataDir().resolve("bots"));
    }

    BotStateRepository(ObjectMapper objectMapper, Path dataDir) {
    	super(objectMapper, dataDir, BotInstanceData.class);
    }

    private static void migrateLegacyBotFiles(ObjectMapper objectMapper, Path legacyDataDir, Path botDataDir) {
        if (legacyDataDir == null || botDataDir == null || !Files.isDirectory(legacyDataDir)) {
            return;
        }

        try {
            Files.createDirectories(botDataDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create bot state directory " + botDataDir + ".", e);
        }

        try (var stream = Files.list(legacyDataDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .filter(path -> !LEGACY_GRID_STATE_FILE.equalsIgnoreCase(path.getFileName().toString()))
                    .forEach(path -> migrateIfBotState(objectMapper, path, botDataDir.resolve(path.getFileName())));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect legacy bot state directory " + legacyDataDir + ".", e);
        }
    }

    private static void migrateIfBotState(ObjectMapper objectMapper, Path source, Path target) {
        if (Files.exists(target)) {
            return;
        }

        try {
            var candidate = objectMapper.readValue(source.toFile(), BotInstanceData.class);
            if (isBlank(candidate.getFirst()) || isBlank(candidate.getLast())) {
                return;
            }
            Files.move(source, target);
        } catch (IOException ignored) {
            // Not a bot state file or cannot be moved; leave as-is.
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
