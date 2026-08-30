package uk.co.bithatch.opensim.spawner.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.AddOn;
import uk.co.bithatch.opensim.spawner.domain.AddOnInstanceData;
import uk.co.bithatch.opensim.spawner.domain.AddOnLevel;
import uk.co.bithatch.opensim.spawner.domain.ResolvedAddOnPlan;
import uk.co.bithatch.opensim.spawner.state.AddOnInstanceStateRepository;
import uk.co.bithatch.opensim.spawner.state.AddOnRepository;

@Service
public class AddOnInstanceProvisioningService extends AbstractContainerGroupProvisioningService<AddOnInstanceStateRepository, AddOnInstanceData> {
	private static final Logger LOG = LoggerFactory.getLogger(AddOnInstanceProvisioningService.class);

	private final AddOnRepository addOnRepository;
	private final SpawnerProperties properties;
	private final AddOnProfileService profileService;

	public AddOnInstanceProvisioningService(AddOnRepository addOnRepository,
			AddOnInstanceStateRepository addOnInstanceStateRepository, 		
			SpawnerProperties properties,
			StackContainerService stackContainerService,
			TemplateResolver templateResolver,
			AddOnProfileService profileService,
			DockerService dockerService) {
		super(addOnInstanceStateRepository, dockerService, templateResolver, properties		);
		this.addOnRepository = addOnRepository;
		this.properties = properties;
		this.profileService = profileService;

		if (properties.isAddOnsRefreshAtStartup()) {
			try {
				reload();
			} catch (RuntimeException e) {
				// Startup refresh is best-effort; API-driven reload can still be used later.
				LOG.warn("Failed to refresh add-ons at startup: {}", e.getMessage());
			}
		}
		
	}

	@Override
	public Map<String, Object> toResponse(AddOnInstanceData bot) {
        var status = new LinkedHashMap<String, Object>();
        status.put("name", bot.getName());
        status.put("level", bot.getLevel() == null ? null : bot.getLevel().name());
        return status;
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
		return addOnRepository.list().stream().map(mf -> {
			return new AddOn(mf, stateRepository.exists(mf.getName()));
			
		}).toList();
	}
	
	public void enableAddOn(String addOnName) {
		LOG.info("Enabling add-on {}.", addOnName);
		if(!exists(addOnName)) {
			installAddOn(addOnName, Map.of());
		}
	}
	
	public void disableAddOn(String addOnName) {
		if(exists(addOnName)) {
			deleteContainerGroup(addOnName);
		}
	}
	
	private synchronized AddOnInstanceData installAddOn(String name, Map<String, String> requestFields) {
        if (stateRepository.exists(name)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Simulator already exists.");
        }
        
        var createRequestFields = requestFields == null ? Map.<String, String>of() : requestFields;

        var addOnInstant = new AddOnInstanceData();
        addOnInstant.setName(name);
        addOnInstant.setLevel(AddOnLevel.STACK);
        
        var materializedFiles = new ArrayList<java.nio.file.Path>();
        var createdContainerIds = new ArrayList<String>();
        var containerRequestFields = new LinkedHashMap<>(createRequestFields);
        
        try {

            stateRepository.save(addOnInstant);
            
            var plan = profileService.resolvePlan(addOnInstant, containerRequestFields);
            LOG.info("Resolved {} container spec(s) for add-on {}.", plan.containers().size(), name);
            materializeFiles(plan, addOnInstant, materializedFiles);

            createdContainerIds.addAll(dockerService.createContainers(plan.containers()));
            LOG.info("Created {} container(s) for add-on {}.", createdContainerIds.size(), addOnInstant);
            addOnInstant.setContainerIds(createdContainerIds);
            stateRepository.save(addOnInstant);

            dockerService.startContainers(createdContainerIds);
            LOG.info("Started {} container(s) for add-on {}.", createdContainerIds.size(), name);
            waitForStartupWindow(createdContainerIds, Duration.ofMinutes(1), Duration.ofSeconds(2));
            LOG.info("Add-on {} provisioned successfully.", name);
            

            return addOnInstant;
        } catch (RuntimeException e) {
            LOG.error("Provisioning failed for add-on {}. Starting rollback.", name, e);
            rollbackFailedProvision(name, createdContainerIds, materializedFiles);
            throw e;
        }
    }

    private void materializeFiles(ResolvedAddOnPlan plan, AddOnInstanceData bot, List<java.nio.file.Path> writtenFiles) {
        materializeFiles(plan, writtenFiles, profileService.buildBaseVariables(bot));
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
}