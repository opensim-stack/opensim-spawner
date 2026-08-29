package uk.co.bithatch.opensim.spawner.state;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.Manifest;

@Component
public class AddOnRepository extends AbstractStateRepository<Manifest> {
	@Autowired
	public AddOnRepository(ObjectMapper objectMapper, SpawnerProperties properties) {
		super(objectMapper, properties.getAddOnsDir(), Manifest.class);
	}

	AddOnRepository(ObjectMapper objectMapper, Path dataDir) {
		super(objectMapper, dataDir, Manifest.class);
	}

	@Override
	protected Stream<Path> filterStream(Stream<Path> stream) {
		return stream
		        .filter(path -> Files.isDirectory(path) && Files.exists(path.resolve("manifest.json")))
		        .map(path -> path.resolve("manifest.json"));
	}

	@Override
	protected Path filePath(String name) {
		return dataDir.resolve(name).resolve("manifest.json");
	}
}