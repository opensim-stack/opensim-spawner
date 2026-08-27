package uk.co.bithatch.opensim.spawner.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.BotHandlerAssignment;

@Component
public class HandlerStateRepository extends AbstractStateRepository<BotHandlerAssignment> {

    private static final String FILE_NAME = "handlers.json";

    @Autowired
    public HandlerStateRepository(ObjectMapper objectMapper, SpawnerProperties properties) {
        super(objectMapper, properties.getConfigDir(), BotHandlerAssignment.class);
    }

    public synchronized List<BotHandlerAssignment> listHandlers() {
        var path = handlersFile();
        if (!Files.exists(path)) {
            return List.of();
        }

        try {
            CollectionType listType = objectMapper().getTypeFactory()
                    .constructCollectionType(ArrayList.class, BotHandlerAssignment.class);
            List<BotHandlerAssignment> loaded = objectMapper().readValue(path.toFile(), listType);
            return loaded == null ? List.of() : List.copyOf(loaded);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read handlers state from " + path + ".", e);
        }
    }

    public synchronized void saveHandlers(List<BotHandlerAssignment> handlers) {
        var safeHandlers = handlers == null ? List.<BotHandlerAssignment>of() : List.copyOf(handlers);
        try {
            Files.createDirectories(dataDir());
            objectMapper().writerWithDefaultPrettyPrinter().writeValue(handlersFile().toFile(), safeHandlers);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save handlers state to " + handlersFile() + ".", e);
        }
    }

    private Path handlersFile() {
        return dataDir().resolve(FILE_NAME);
    }

    public static boolean isWildcard(String value) {
        return normalize(value).equals("*");
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
