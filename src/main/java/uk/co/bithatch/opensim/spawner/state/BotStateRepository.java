package uk.co.bithatch.opensim.spawner.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.BotInstanceData;

@Component
public class BotStateRepository {

    private final ObjectMapper objectMapper;
    private final Path dataDir;

    @Autowired
    public BotStateRepository(ObjectMapper objectMapper, SpawnerProperties properties) {
        this(objectMapper, properties.getDataDir());
    }

    BotStateRepository(ObjectMapper objectMapper, Path dataDir) {
        this.objectMapper = objectMapper;
        this.dataDir = dataDir;
    }

    public synchronized boolean exists(String first, String last) {
        return Files.exists(filePath(first, last));
    }

    public synchronized Optional<BotInstanceData> load(String first, String last) {
        var path = filePath(first, last);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(path.toFile(), BotInstanceData.class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load bot state from " + path + ".", e);
        }
    }

    public synchronized void save(BotInstanceData data) {
        try {
            Files.createDirectories(dataDir);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(filePath(data.getFirst(), data.getLast()).toFile(), data);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save bot state for " + data.displayName() + ".", e);
        }
    }

    public synchronized List<BotInstanceData> list() {
        if (!Files.isDirectory(dataDir)) {
            return List.of();
        }
        var result = new ArrayList<BotInstanceData>();
        try (var stream = Files.list(dataDir)) {
            stream
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> {
                        try {
                            result.add(objectMapper.readValue(path.toFile(), BotInstanceData.class));
                        } catch (IOException e) {
                            throw new IllegalStateException("Failed to parse state file " + path + ".", e);
                        }
                    });
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list bot state directory " + dataDir + ".", e);
        }
    }

    public synchronized void delete(String first, String last) {
        var path = filePath(first, last);
        if (!Files.exists(path)) {
            return;
        }
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete bot state file " + path + ".", e);
        }
    }

    private Path filePath(String first, String last) {
        return dataDir.resolve(first + "-" + last + ".json");
    }
}
