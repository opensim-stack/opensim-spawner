package uk.co.bithatch.opensim.spawner.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.GridState;

@Component
public class GridStateRepository  {
	
	private final ObjectMapper objectMapper;
	private final Path file;
	private final SpawnerProperties properties;
	
	private GridState state;

    @Autowired
    public GridStateRepository(ObjectMapper objectMapper, SpawnerProperties properties) {
    	this.properties = properties;
    	var dataDir = properties.getDataDir();
		var oldGridsFile = dataDir.resolve("grids");
    	var gridsFile = dataDir.resolve("grids.json");
    	if(Files.exists(oldGridsFile) && !Files.exists(gridsFile)) {
    		try {
				Files.move(oldGridsFile, gridsFile);
			} catch (IOException e) {
				throw new IllegalStateException("Failed to move old grids file to new grids.json file.", e);
			}
		}
		this.file = gridsFile;
    	this.objectMapper = objectMapper;
    	load();
    }

    public GridStateRepository(ObjectMapper objectMapper, Path file, SpawnerProperties properties) {
    	this.objectMapper = objectMapper;
    	this.file = file;
    	this.properties = properties;
    	load();
    }
    
    public synchronized GridState get() {
		return state;
	}
    
    private void load() {
		if (Files.exists(file)) {
			try {
				state = objectMapper.readValue(file.toFile(), GridState.class);
				backfillMissingDefaults();
			} catch (IOException e) {
				throw new IllegalStateException("Failed to load grid state from " + file + ".", e);
			}
		}
		else {
			state = new GridState();
			state.setAdminToken(UUID.randomUUID().toString());
			state.setName(properties.getOpensimGridName());
			state.setNick(properties.getOpensimGridNick());
			state.setWelcomeMessage(properties.getOpensimWelcomeMessage());
			if (!isGuidedMode()) {
				state.setConsolePass(properties.getOpensimConsolePass());
				state.setConsoleUser(properties.getOpensimConsoleUser());
			}
			save();
		}
    }

	private void backfillMissingDefaults() {
		if (isGuidedMode()) {
			return;
		}
		var changed = false;
		if (isBlank(state.getConsoleUser()) && !isBlank(properties.getOpensimConsoleUser())) {
			state.setConsoleUser(properties.getOpensimConsoleUser().trim());
			changed = true;
		}
		if (isBlank(state.getConsolePass()) && !isBlank(properties.getOpensimConsolePass())) {
			state.setConsolePass(properties.getOpensimConsolePass().trim());
			changed = true;
		}
		if (changed) {
			save();
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	private boolean isGuidedMode() {
		return "guided".equalsIgnoreCase(properties.getOpensimProvisionMode());
	}
    
    public synchronized void save() {
		try {
			Files.createDirectories(file.getParent());
			objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), state);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to save grid state to " + file + ".", e);
		}
    }
}
