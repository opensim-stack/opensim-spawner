package uk.co.bithatch.opensim.spawner.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.AddOn;
import uk.co.bithatch.opensim.spawner.state.AddOnRepository;
import uk.co.bithatch.opensim.spawner.state.GridStateRepository;

@Service
public class AddOnService {
	private static final Logger LOG = LoggerFactory.getLogger(AddOnService.class);

	private final AddOnRepository addOnRepository;
	private final GridStateRepository gridStateRepository;
	private final SpawnerProperties properties;

	public AddOnService(AddOnRepository addOnRepository,
			GridStateRepository gridStateRepository,
			SpawnerProperties properties) {
		this.addOnRepository = addOnRepository;
		this.gridStateRepository = gridStateRepository;
		this.properties = properties;

		if (properties.isAddOnsRefreshAtStartup()) {
			try {
				reload();
			} catch (RuntimeException e) {
				// Startup refresh is best-effort; API-driven reload can still be used later.
				LOG.warn("Failed to refresh add-ons at startup: {}", e.getMessage());
			}
		}
	}
	
	public synchronized void reload() {
		var repository = properties.getAddOnsRepository();
		if (repository == null || repository.isBlank()) {
			return;
		}

		var addOnsDir = properties.getAddOnsDir().toAbsolutePath().normalize();
		if (!Files.exists(addOnsDir)) {
			cloneRepository(repository, addOnsDir);
			return;
		}

		if (!Files.isDirectory(addOnsDir.resolve(".git"))) {
			return;
		}

		git(addOnsDir.getParent(), "-C", addOnsDir.toString(), "pull", "--ff-only");
	}
	
	public List<AddOn> getAddOns() {
		var gridState = gridStateRepository.get();
		var enabledAddOns = mutableAddOns(gridState);
		return addOnRepository.list().stream().map(mf -> {
			return new AddOn(mf, enabledAddOns.contains(mf.getName()));
			
		}).toList();
	}
	
	public void enableAddOn(String addOnName) {
		var gridState = gridStateRepository.get();
		var addOns = mutableAddOns(gridState);
		if(!addOns.contains(addOnName)) {
			addOns.add(addOnName);
			gridStateRepository.save();
		}
	}
	
	public void disableAddOn(String addOnName) {
		var gridState = gridStateRepository.get();
		var addOns = mutableAddOns(gridState);
		if(addOns.contains(addOnName)) {
			addOns.remove(addOnName);
			gridStateRepository.save();
		}
	}

	private void cloneRepository(String repository, Path addOnsDir) {
		var parent = addOnsDir.getParent();
		if (parent == null) {
			throw new IllegalStateException("Invalid add-ons directory '" + addOnsDir + "'.");
		}
		try {
			Files.createDirectories(parent);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to create add-ons directory parent '" + parent + "'.", e);
		}
		git(parent, "clone", repository, addOnsDir.toString());
	}

	private static void git(Path workingDirectory, String... arguments) {
		var command = new ArrayList<String>();
		command.add("git");
		command.addAll(List.of(arguments));

		var builder = new ProcessBuilder(command);
		builder.directory(workingDirectory.toFile());
		builder.redirectErrorStream(true);

		try {
			var process = builder.start();
			var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			var exitCode = process.waitFor();
			if (exitCode != 0) {
				throw new IllegalStateException("Git command failed ('" + String.join(" ", command) + "')."
						+ (output.isEmpty() ? "" : " Output: " + output));
			}
		} catch (IOException e) {
			throw new IllegalStateException("Failed to execute git command '" + String.join(" ", command) + "'.", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while running git command '" + String.join(" ", command) + "'.", e);
		}
	}

	private static List<String> mutableAddOns(uk.co.bithatch.opensim.spawner.domain.GridState gridState) {
		if (gridState.getAddOns() == null) {
			gridState.setAddOns(new ArrayList<>());
		}
		return gridState.getAddOns();
	}
}