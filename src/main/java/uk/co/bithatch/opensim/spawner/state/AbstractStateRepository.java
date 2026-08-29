package uk.co.bithatch.opensim.spawner.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.jlib.IO;
import uk.co.bithatch.opensim.spawner.domain.DomainObject;

public abstract class AbstractStateRepository<T extends DomainObject> {

    private final ObjectMapper objectMapper;
    private final Class<T> clazz;
    
    protected final Path dataDir;

    protected AbstractStateRepository(ObjectMapper objectMapper, Path dataDir, Class<T> clazz) {
        this.objectMapper = objectMapper;
        this.dataDir = dataDir;
        this.clazz = clazz;
    }

    public final synchronized boolean exists(String name) {
        return Files.exists(filePath(name));
    }

    public final synchronized Optional<T> load(String name) {
        var path = filePath(name);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(path.toFile(), clazz));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load simulator state from " + path + ".", e);
        }
    }

    public final synchronized void save(T data) {
        try {
            Files.createDirectories(dataDir);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(filePath(data.displayName()).toFile(), data);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save simulator state for " + data.displayName() + ".", e);
        }
    }

    public final synchronized List<T> list() {
        if (!Files.isDirectory(dataDir)) {
            return List.of();
        }
        var result = new ArrayList<T>();
        try (var stream = Files.list(dataDir)) {
            filterStream(stream)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> {
                        try {
                            result.add(objectMapper.readValue(path.toFile(), clazz));
                        } catch (IOException e) {
                            throw new IllegalStateException("Failed to parse state file " + path + ".", e);
                        }
                    });
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list simulator state directory " + dataDir + ".", e);
        }
    }

    public final  synchronized void delete(String name) {
        var path = filePath(name);
        if (!Files.exists(path)) {
            return;
        }
        try {
        	if(Files.isDirectory(path)) {
        		if(!dataDir.isAbsolute()) {
					throw new IllegalStateException("Refusing to delete non-absolute path " + path + ".");
				}
        		IO.deleteDirectoryQuietly(path);
        	}
            Files.delete(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete simulator state file " + path + ".", e);
        }
    }

	protected Stream<Path> filterStream(Stream<Path> stream) {
		return stream
		        .filter(path -> !Files.isDirectory(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"));
	}

    protected Path filePath(String name) {
        return dataDir.resolve(name.replace(' ', '-') + ".json");
    }

    protected final ObjectMapper objectMapper() {
        return objectMapper;
    }

    protected final Path dataDir() {
        return dataDir;
    }
}
