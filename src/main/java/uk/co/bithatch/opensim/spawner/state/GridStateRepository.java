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
    	this.file = properties.getDataDir().resolve("grids");
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
			} catch (IOException e) {
				throw new IllegalStateException("Failed to load grid state from " + file + ".", e);
			}
		}
		else {
			state = new GridState();
			state.setAdminToken(UUID.randomUUID().toString());
			state.setName(properties.getOpensimGridName());
			state.setNick(properties.getOpensimGridNick());
			save();
		}
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
