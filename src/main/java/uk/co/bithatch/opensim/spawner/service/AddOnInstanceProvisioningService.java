package uk.co.bithatch.opensim.spawner.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sshtools.jini.Data;
import com.sshtools.jini.INI;
import com.sshtools.jini.INI.Section;
import com.sshtools.jini.INIWriter;

import uk.co.bithatch.opensim.jlib.Strings;
import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.AddOn;
import uk.co.bithatch.opensim.spawner.domain.AddOnInstanceData;
import uk.co.bithatch.opensim.spawner.domain.BotInstanceData;
import uk.co.bithatch.opensim.spawner.domain.BotLevel;
import uk.co.bithatch.opensim.spawner.domain.ContainerGroupInstanceData;
import uk.co.bithatch.opensim.spawner.domain.ContainerLevel;
import uk.co.bithatch.opensim.spawner.domain.ContainerSpec;
import uk.co.bithatch.opensim.spawner.domain.DomainObject;
import uk.co.bithatch.opensim.spawner.domain.HookType;
import uk.co.bithatch.opensim.spawner.domain.Manifest;
import uk.co.bithatch.opensim.spawner.domain.Plan;
import uk.co.bithatch.opensim.spawner.domain.ResolvedAddOnPlan;
import uk.co.bithatch.opensim.spawner.domain.SimulatorInstanceData;
import uk.co.bithatch.opensim.spawner.domain.SimulatorLevel;
import uk.co.bithatch.opensim.spawner.state.AddOnInstanceStateRepository;
import uk.co.bithatch.opensim.spawner.state.AddOnRepository;
import uk.co.bithatch.opensim.spawner.state.BotStateRepository;
import uk.co.bithatch.opensim.spawner.state.SimulatorStateRepository;
import uk.co.bithatch.opensim.spawner.state.StackStateRepository;

@Service
public class AddOnInstanceProvisioningService extends AbstractContainerGroupProvisioningService<Manifest, ContainerLevel, AddOnInstanceStateRepository, AddOnInstanceData> {
	private static final Logger LOG = LoggerFactory.getLogger(AddOnInstanceProvisioningService.class);
	private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

	private final AddOnRepository addOnRepository;
	private final SpawnerProperties properties;
	private final AddOnProfileService profileService;
	private final BotStateRepository botStateRepository;
	private final SimulatorStateRepository simulatorStateRepository;
	private final BotLevelProfileService botLevelProfileService;
	private final SimulatorLevelProfileService simulatorLevelProfileService;
	private final ThreadLocal<Path> currentManifestDir = new ThreadLocal<>();
	private final BotProvisioningService botProvisioningService;

	public AddOnInstanceProvisioningService(AddOnRepository addOnRepository,
			AddOnInstanceStateRepository addOnInstanceStateRepository, 		
			SpawnerProperties properties,
			TemplateResolver templateResolver,
			AddOnProfileService profileService,
			BotStateRepository botStateRepository,
			SimulatorStateRepository simulatorStateRepository,
			BotLevelProfileService botLevelProfileService,
			SimulatorLevelProfileService simulatorLevelProfileService,
			StackStateRepository stackStateRepository,
			BotProvisioningService botProvisioningService,
			DockerService dockerService,
			RandomPasswordService randomPasswordService) {
		super(stackStateRepository, addOnInstanceStateRepository, dockerService, templateResolver, properties, randomPasswordService, "add-ons");
		this.addOnRepository = addOnRepository;
		this.botProvisioningService = botProvisioningService;
		this.properties = properties;
		this.profileService = profileService;
		this.botStateRepository = botStateRepository;
		this.simulatorStateRepository = simulatorStateRepository;
		this.botLevelProfileService = botLevelProfileService;
		this.simulatorLevelProfileService = simulatorLevelProfileService;

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
		var repository = resolveConfiguredAddOnsRepository();
		var branch = resolveConfiguredAddOnsBranch();
		if (repository == null || repository.isBlank()) {
			return;
		}

		var addOnsDir = properties.getAddOnsDir().toAbsolutePath().normalize();
		if (!Files.exists(addOnsDir)) {
			cloneRepository(repository, branch, addOnsDir);
			return;
		}

		if (!Files.isDirectory(addOnsDir.resolve(".git"))) {
			return;
		}

		switchToBranch(addOnsDir, branch);
		pullRepository(addOnsDir, branch);
	}

	private String resolveConfiguredAddOnsRepository() {
		var gridState = stackStateRepository.get();
		var configured = normalize(gridState.getAddOnsRepository());
		if (!configured.isBlank()) {
			return configured;
		}
		return normalize(properties.getAddOnsRepository());
	}

	private String resolveConfiguredAddOnsBranch() {
		var gridState = stackStateRepository.get();
		var configured = normalize(gridState.getAddOnsBranch());
		if (!configured.isBlank()) {
			return configured;
		}
		return normalize(properties.getAddOnsBranch());
	}

	private void switchToBranch(Path addOnsDir, String branch) {
		if (branch == null || branch.isBlank()) {
			return;
		}
		try {
			git(addOnsDir.getParent(), "-C", addOnsDir.toString(), "checkout", branch);
		} catch (IllegalStateException checkoutError) {
			git(addOnsDir.getParent(), "-C", addOnsDir.toString(), "fetch", "origin", branch);
			git(addOnsDir.getParent(), "-C", addOnsDir.toString(), "checkout", "-B", branch, "origin/" + branch);
		}
	}

	private void pullRepository(Path addOnsDir, String branch) {
		if (branch == null || branch.isBlank()) {
			git(addOnsDir.getParent(), "-C", addOnsDir.toString(), "pull", "--ff-only");
			return;
		}
		git(addOnsDir.getParent(), "-C", addOnsDir.toString(), "pull", "--ff-only", "origin", branch);
	}
	
	public List<AddOn> getAddOns() {
		return addOnRepository.list().stream().map(mf -> {
			return new AddOn(mf, stateRepository.exists(mf.getName()));
			
		}).toList();
	}
	
	public void enableAddOn(String addOnName) {
		if(!exists(addOnName)) {
			LOG.info("Enabling add-on {}.", addOnName);
			var created = installAddOn(addOnName, Map.of());
			var contributions = resolveAddOnManagedContributions(created);
			reconcileParentConfigurations(contributions, "enabled", addOnName);
		}
		else {
			LOG.warn("{} add-on already enabled.", addOnName);
		}
	}

	public synchronized void reconfigureAddOn(String addOnName, Map<String, String> requestFields) {
		var addOn = stateRepository.load(addOnName)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Add-on not found."));
		var manifest = addOnRepository.load(addOnName)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Add-on manifest not found."));

		addOn.setRequestFields(requestFields == null ? Map.of() : new LinkedHashMap<>(requestFields));
		stateRepository.save(addOn);
		reprovisionAddOn(addOn, manifest);
		var contributions = resolveAddOnManagedContributions(addOn);
		reconcileParentConfigurations(contributions, "reconfigured", addOnName);
	}
	
	public void disableAddOn(String addOnName) {
		if(exists(addOnName)) {
			var addOn = stateRepository.load(addOnName)
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Add-on not found."));
			var contributions = resolveAddOnManagedContributions(addOn);
			var mfOpt = addOnRepository.load(addOnName);
			var saveVars =  new LinkedHashMap<String, String>();
			mfOpt.ifPresent(mf -> {
				var variables = profileService.buildBaseVariables(addOn,  new LinkedHashMap<>(), resolveEnvironment(mfOpt.get().getConstants(), mf.getConstants()));
	            runHooks(HookType.PRE_UNINSTALL, mf, addOn, variables);	
	        	removeExports(mf, stackStateRepository);
	        	saveVars.putAll(variables);
			});
			
			if (addOn.getLevel() == ContainerLevel.SIMULATOR && !addOn.getContainerIds().isEmpty()) {
				detachAddOnContainersFromGridSimulator(addOn.getGridServiceSimulatorName(), addOn.getContainerIds());
			}
			deleteContainerGroup(addOnName);
            
			removeAddOnManagedContributions(contributions);
			reconcileParentConfigurations(contributions, "disabled", addOnName);
			mfOpt.ifPresent(mf -> {
				reprovisionContainersWithVariablesOrLabelMatches(mf, addOn.getContainerIds());
	            runHooks(HookType.POST_UNINSTALL, mf, addOn, saveVars);	
			});
		}
	}
    
    private ContainerGroupInstanceData<?> instanceDataForContainer(String containerName) {
    	var botInstance = botStateRepository.list().stream().filter(bot -> bot.getContainerIds() != null && bot.getContainerIds().contains(containerName)).findFirst()
			.orElse(null);
    	if(botInstance == null) {
        	var simInstance = simulatorStateRepository.list().stream().filter(sim -> sim.getContainerIds() != null && 
        		sim.getContainerIds().contains(containerName)).findFirst()
    			.orElse(null);
        	if(simInstance == null) {
        		return null;
        	}
        	else {
				return simInstance;
			}
    	}
    	else {
			return botInstance;
		}
	}

	private void reconcileParentConfigurations(List<ManagedContribution> contributions, String action, String addOnName) {
		var resources = contributions.stream().map(ManagedContribution::resource).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		if (resources.isEmpty()) {
			LOG.info("Add-on {} {} with no managed drop-in resources. No parent reconfiguration required.", addOnName, action);
			return;
		}

		LOG.info("Add-on {} {} resources {}. Re-materializing affected parent container groups.", addOnName, action, resources);
		var refreshedBots = refreshBotsForManagedResources(resources);
		var refreshedSims = refreshSimulatorsForManagedResources(resources);
		var refreshedStack = refreshStackAddOnsForManagedResources(resources);
		LOG.info("Managed reconfiguration after add-on {} {} complete. bots={}, simulators={}, stack={}",
				addOnName,
				action,
				refreshedBots,
				refreshedSims,
				refreshedStack);
	}

	private int refreshBotsForManagedResources(Set<String> changedResources) {
		var refreshed = 0;
		for (var bot : botStateRepository.list()) {
			var plan = botLevelProfileService.resolvePlan(bot, 
					resolveEnvironment(botLevelProfileService.component().getConstants(),  bot.getRequestFields()));
			if (!hasManagedTargetResourceOverlap(plan.containers(), plan.variables(), changedResources)) {
				continue;
			}

			LOG.info("Re-materializing bot '{}' due to managed resource overlap with {}.", bot.displayName(), changedResources);
			materializeFiles(plan);
			restartContainerIds("bot " + bot.displayName(), bot.getContainerIds());
			refreshed++;
		}
		return refreshed;
	}

	private int refreshSimulatorsForManagedResources(Set<String> changedResources) {
		var refreshed = 0;
		for (var sim : simulatorStateRepository.list()) {
			LOG.info("Resolving simulator '{}' plan for managed resource overlap check.", sim.displayName());
			var plan = simulatorLevelProfileService.resolvePlan(sim, 
					resolveEnvironment(simulatorLevelProfileService.component().getConstants(),  sim.getRequestFields()));
			if (!hasManagedTargetResourceOverlap(plan.containers(), plan.variables(), changedResources)) {
				continue;
			}

			LOG.info("Re-materializing simulator '{}' due to managed resource overlap with {}.", sim.displayName(), changedResources);
			materializeFiles(plan);
			restartContainerIds("simulator " + sim.displayName(), sim.getContainerIds());
			refreshed++;
		}
		return refreshed;
	}

	private int refreshStackAddOnsForManagedResources(Set<String> changedResources) {
		var refreshed = 0;
		for (var addOnInstance : stateRepository.list()) {
			LOG.info("Resolving stack add-on '{}' plan for managed resource overlap check.", addOnInstance.getName());
			var mfOpt = addOnRepository.load(addOnInstance.getName()).get();
			var plan = profileService.resolvePlan(addOnInstance, 
					resolveEnvironment(mfOpt.getConstants(),  Map.of()));
			if (!hasManagedTargetResourceOverlap(plan.containers(), plan.variables(), changedResources)) {
				continue;
			}

			LOG.info("Re-materializing stack add-on '{}' due to managed resource overlap with {}.", addOnInstance.getName(), changedResources);
			materializeFiles(plan, addOnInstance, new ArrayList<>());
			restartContainerIds("stack add-on " + addOnInstance.getName(), addOnInstance.getContainerIds());
			refreshed++;
		}
		return refreshed;
	}

	private void restartContainerIds(String groupName, List<String> containerIds) {
		if (containerIds == null || containerIds.isEmpty()) {
			LOG.info("Skipping restart for {} because no containers are tracked.", groupName);
			return;
		}
		LOG.info("Restarting {} container(s) for {}.", containerIds.size(), groupName);
		dockerService.restartContainers(containerIds);
	}

	private boolean hasManagedTargetResourceOverlap(List<ContainerSpec> containers,
			Map<String, String> variables,
			Set<String> changedResources) {
		for (var container : containers) {
			for (var managedFile : container.getManagedFiles()) {
				if (!changedResources.contains(managedFile.resource())) {
					continue;
				}
				var resolvedTarget = templateResolver.resolve(managedFile.target(), variables);
				if (resolvedTarget != null && !resolvedTarget.isBlank()) {
					return true;
				}
			}
		}
		return false;
	}

	private void materializeFiles(Plan plan) {
		materializeFiles(plan, new ArrayList<>());
	}

	private List<ManagedContribution> resolveAddOnManagedContributions(AddOnInstanceData addOn) {
		var mfOpt = addOnRepository.load(addOn.getName());
		var plan = profileService.resolvePlan(addOn, resolveEnvironment(mfOpt.get().getConstants(), Map.of()));
		var contributions = new ArrayList<ManagedContribution>();

		withManifestContext(addOn.getName(), () -> {
			for (var container : plan.containers()) {
				for (var managedFile : container.getManagedFiles()) {
					var targetName = templateResolver.resolve(managedFile.target(), plan.variables());
					if (targetName != null && !targetName.isBlank()) {
						continue;
					}
					var templateName = managedFile.resource();
					var dropInDir = Path.of(managedFile.dropIns());
					var template = loadManagedFileTemplate(templateName, targetName);
					var resolved = templateResolver.resolve(template, plan.variables());
					contributions.add(new ManagedContribution(templateName, dropInDir, resolved));
				}
			}
		});

		LOG.info("Resolved {} managed drop-in contribution(s) for add-on '{}'.", contributions.size(), addOn.getName());
		return contributions;
	}

	private void removeAddOnManagedContributions(List<ManagedContribution> contributions) {
		for (var contribution : contributions) {
			if (!Files.isDirectory(contribution.dropInsDir())) {
				continue;
			}
			try (var files = Files.list(contribution.dropInsDir())) {
				var candidates = files
						.filter(Files::isRegularFile)
						.filter(path -> path.getFileName().toString().matches("\\d{2}-.*\\.json"))
						.filter(path -> path.getFileName().toString().endsWith(contribution.resource()))
						.toList();

				for (var candidate : candidates) {
					var current = Files.readString(candidate, StandardCharsets.UTF_8);
					if (!current.equals(contribution.resolvedContent())) {
						continue;
					}
					LOG.info("Removing managed add-on drop-in file '{}'.", candidate);
					Files.deleteIfExists(candidate);
				}
			} catch (IOException e) {
				throw new IllegalStateException("Failed to remove managed add-on drop-ins from "
						+ contribution.dropInsDir() + ".", e);
			}
		}
	}

	private void withManifestContext(String addOnName, Runnable operation) {
		var manifestDir = properties.getAddOnsDir().resolve(addOnName).toAbsolutePath().normalize();
		currentManifestDir.set(manifestDir);
		try {
			operation.run();
		} finally {
			currentManifestDir.remove();
		}
	}
	
	private synchronized AddOnInstanceData installAddOn(String name, Map<String, String> requestFields) {
        if (stateRepository.exists(name)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Simulator already exists.");
        }
        var addOnInstance = new AddOnInstanceData();
        addOnInstance.setName(name);
		var manifest = addOnRepository.load(name)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Add-on not found."));
		
		List<SimulatorInstanceData> attachedGridSimulators = new ArrayList<>();
		addOnInstance.setLevel(ContainerLevel.SIMULATOR);
		for (var addOnLevel : resolveAddOnLevels(manifest.getExtensions())) {

			addOnInstance.setLevel(addOnLevel);

			if (addOnLevel == ContainerLevel.SIMULATOR) {
				var sim = requireGridServiceSimulator();
				attachedGridSimulators.add(sim);
				addOnInstance.setGridServiceSimulatorName(sim.getName());
				LOG.info("GRID add-on '{}' attached to grid-service simulator '{}'.", name, sim.getName());
			}
		}

		addOnInstance.setRequestFields(requestFields == null ? Map.of() : new LinkedHashMap<>(requestFields));
        
        var materializedFiles = new ArrayList<java.nio.file.Path>();
        var createdContainerIds = new ArrayList<String>();
        Map<String, String> variables = profileService.buildBaseVariables(addOnInstance, resolveEnvironment(manifest.getConstants(), requestFields), requestFields);
		
        try {
    		installTokens(name, manifest);
			installExports(name, manifest, resolveEnvironment(manifest.getConstants(), addOnInstance.getRequestFields())); 

            stateRepository.save(addOnInstance);
            
            var plan = profileService.resolvePlan(addOnInstance, resolveEnvironment(manifest.getConstants(), requestFields));
            variables = plan.variables();
            
            runHooks(HookType.PRE_INSTALL, manifest, addOnInstance, variables);
            
            LOG.info("Resolved {} container spec(s) for add-on {}.", plan.containers().size(), name);
            materializeFiles(plan, addOnInstance, materializedFiles);

            createdContainerIds.addAll(dockerService.createContainers(plan.containers()));
            LOG.info("Created {} container(s) for add-on {}.", createdContainerIds.size(), addOnInstance);
            addOnInstance.setContainerIds(createdContainerIds);
            stateRepository.save(addOnInstance);

            if(!createdContainerIds.isEmpty()) {
	            for(var attachedGridSimulator : attachedGridSimulators) {
	            	attachAddOnContainersToGridSimulator(attachedGridSimulator.getName(), createdContainerIds);
				}
            }
            
            runHooks(HookType.POST_INSTALL, manifest, addOnInstance, variables);

            dockerService.startContainers(createdContainerIds);
            LOG.info("Started {} container(s) for add-on {}.", createdContainerIds.size(), name);
            waitForStartupWindow(createdContainerIds, Duration.ofMinutes(1), Duration.ofSeconds(2));
            LOG.info("Add-on {} provisioned successfully.", name);
            
            reprovisionContainersWithVariablesOrLabelMatches(manifest, createdContainerIds);

            return addOnInstance;
        } catch (RuntimeException e) {
        	removeExports(manifest, stackStateRepository);
            LOG.error("Provisioning failed for add-on {}. Starting rollback.", name, e);
            runHooks(HookType.PRE_UNINSTALL, manifest, addOnInstance, variables);
            if (addOnInstance.getLevel() == ContainerLevel.SIMULATOR && !createdContainerIds.isEmpty()) {
            	detachAddOnContainersFromGridSimulator(addOnInstance.getGridServiceSimulatorName(), createdContainerIds);
            }
            rollbackFailedProvision(name, createdContainerIds, materializedFiles);
            runHooks(HookType.POST_UNINSTALL, manifest, addOnInstance, variables);
            throw e;
        }
    }

	private void reprovisionAddOn(AddOnInstanceData addOn, Manifest manifest) {
		var oldContainerIds = addOn.getContainerIds() == null ? List.<String>of() : new ArrayList<>(addOn.getContainerIds());
		var requestFields = addOn.getRequestFields() == null ? Map.<String, String>of() : addOn.getRequestFields();
		installExports(addOn.getName(), manifest, resolveEnvironment(manifest.getConstants(), requestFields));
		var plan = profileService.resolvePlan(addOn, resolveEnvironment(manifest.getConstants(), requestFields));
		var attachedGridSimulatorName = addOn.getGridServiceSimulatorName();
		var simulatorScoped = addOn.getLevel() == ContainerLevel.SIMULATOR;

		if (!oldContainerIds.isEmpty()) {
			try {
				dockerService.stopContainers(oldContainerIds);
			} catch (RuntimeException e) {
				LOG.warn("Failed to stop existing containers for add-on {} before reprovisioning.", addOn.getName(), e);
			}
			if (simulatorScoped) {
				detachAddOnContainersFromGridSimulator(attachedGridSimulatorName, oldContainerIds);
			}
			dockerService.removeContainers(oldContainerIds);
		}

		var materializedFiles = new ArrayList<Path>();
		materializeFiles(plan, addOn, materializedFiles);

		var createdContainerIds = dockerService.createContainers(plan.containers());
		if (simulatorScoped && !createdContainerIds.isEmpty()) {
			attachAddOnContainersToGridSimulator(attachedGridSimulatorName, createdContainerIds);
		}
		addOn.setContainerIds(createdContainerIds);
		stateRepository.save(addOn);

		dockerService.startContainers(createdContainerIds);
		waitForStartupWindow(createdContainerIds, Duration.ofMinutes(1), Duration.ofSeconds(2));
		reprovisionContainersWithVariablesOrLabelMatches(manifest, createdContainerIds);
	}
	

	private void reprovisionContainersWithVariablesOrLabelMatches(Manifest manifest, Collection<String> ignoredIds) {
		var restartableContainers = new ArrayList<String>();
		for(var ref : dockerService.listStackContainers()) {
			if(ignoredIds.contains(ref)) {
				continue;
			}
			var env = dockerService.getContainerVars(ref);
			if(containsAny(env.keySet(), manifest.getExports())) {
				LOG.info("Add-on export(s) detected in container {} environment: {}", ref, env);
				restartableContainers.add(ref);
			}
			else {
				var label = dockerService.getContainerLabels(ref).get("configured.by");
				if(manifest.getReconfigures().contains(label)) {
					LOG.info("Add-on label match configured.by detected in container {} label: {}", ref, label);
					restartableContainers.add(ref);
				}
				else {
					LOG.info("No add-on label match configured.by detected in container {} environment or labels.", ref);
				}
			}
		}
		
		if(!restartableContainers.isEmpty()) {
			LOG.info("Re-provision {} container(s) to propagate add-on {} exports.", restartableContainers.size(), manifest.getName());
			for(var ref : restartableContainers) {
				LOG.info("Re-provision container {} to propagate add-on {} exports.", ref, manifest.getName());
				var cntr = instanceDataForContainer(ref);
				if(cntr instanceof BotInstanceData bot) {
					LOG.info("Re-provisioning bot {} to propagate add-on {} exports.", bot.displayName(), manifest.getName());
					botProvisioningService.reprovisionBot(bot);
				}
				else {
					throw new UnsupportedOperationException("Reprovisioning for this type of container not yet supported.");
				}
			}
		}
	}
	
	private boolean containsAny(Collection<String> collection, Collection<String> candidates) {
		for(var candidate : candidates) {
			if(collection.contains(candidate)) {
				return true;
			}
		}
		return false;
	}
	
	private void runHooks(HookType hookType, Manifest manifest, AddOnInstanceData addOnInstance, Map<String, String> variables) {
		var hookScript = manifest.getHooks().get(hookType);
		if(hookScript == null) {
			LOG.info("No hook scripts for type {} in add-on {}", hookType, manifest.getName());
		}
		else {
			LOG.info("Starting hook script for type {} in add-on {}", hookType, manifest.getName());
			try {
				for(var scriptDef : hookScript) {
					var addOn = ContainerLevel.valueOf((String)scriptDef.getOrDefault("addOn", ContainerLevel.STACK.name()));
					switch(addOn) {
					case SIMULATOR:
						var simType = SimulatorLevel.valueOf((String)scriptDef.getOrDefault("level", SimulatorLevel.STANDALONE.name()));
						for(var sim : simulatorStateRepository.list().stream().filter(s -> s.getLevel() == simType).toList()) {
							LOG.info("Executing {} hook script for add-on {} on simulator {}.", hookType, manifest.getName(), sim.getName());
							
							var vars = simulatorLevelProfileService.buildBaseVariables(
									sim, new LinkedHashMap<>(variables), 
									resolveEnvironment(manifest.getConstants(), Map.of()));
							
							runHooksForInstance(sim, scriptDef, manifest, vars);
						}
						break;
					case BOT:
						var botType = BotLevel.valueOf((String)scriptDef.getOrDefault("level", BotLevel.ACTOR.name()));
						for(var bot : botStateRepository.list().stream().filter(s -> s.getLevel() == botType).toList()) {
							LOG.info("Executing {} hook script for add-on {} on bot {}.", hookType, manifest.getName(), bot.getName());
							
							var vars = botLevelProfileService.buildBaseVariables(
									bot, new LinkedHashMap<>(variables), 
									resolveEnvironment(manifest.getConstants(), Map.of()));
							
							runHooksForInstance(bot, scriptDef, manifest, vars);
						}
						break;
					default:
						throw new UnsupportedOperationException("`addOn` type " + addOn + " not yet supported for install scripts.");
					}
				}
			}
			catch(Exception e) {
				LOG.error("Failed to execute hook script for type {} in add-on {}.", hookType, manifest.getName(), e);
			}
		}
	}
	
	private void runHooksForInstance(DomainObject instance, Map<String, Object> def, Manifest manifest, Map<String, String> variables) throws IOException {
		var addOnDir = addOnRepository.resolve(manifest.getName())
				.orElseThrow(() -> new IllegalStateException("Add-on manifest directory not found for " + manifest.getName() + ".")).toAbsolutePath().getParent();

		switch((String)def.getOrDefault("type", "throw")) {
		case "copy":
		{
			copyFile(instance, def, addOnDir, variables);
			break;
		}
		case "delete":
		{
			deleteFile(instance, def, variables);
			break;
		}
		case "deleteIni":
		{
			deleteIni(instance, def, variables);
			break;
		}
		case "createIniKey":
		{
			createIniKey(instance, def, variables);
			break;
		}
		case "deleteJson":
		{
			deleteJson(instance, def, variables);
			break;
		}
		case "createJson":
		{
			createJson(instance, def, variables);
			break;
		}
		case "exec":
		{
			exec(instance, def, addOnDir, variables);
			break;
		}
		case "throw":
			throw new UnsupportedOperationException("Hook script type not specified for add-on install on simulator " + instance.getName() + ".");
		}
	}
	
	private void exec(DomainObject instance, Map<String, Object> def, Path addOnDir, Map<String, String> variables) throws IOException {
		var cmdArgs = new ArrayList<String>();
		if(def.containsKey("line")) {
			cmdArgs.addAll(Strings.parseQuotedString(templateResolver.resolve((String)def.get("line"), variables)));
		}
		else if(def.containsKey("command")) {
			cmdArgs.add(templateResolver.resolve((String)def.get("command"), variables));
			if(def.containsKey("args")) {
				@SuppressWarnings("unchecked")
				var args = (List<String>)def.get("args");
				for(var arg : args) {
					cmdArgs.add(templateResolver.resolve(arg, variables));
				}
			}
		}

		else if(def.containsKey("script")) {
			var scriptPath = addOnDir.resolve(getHookOpPath(instance, "script", def, variables)).normalize();
			if(!def.containsKey("process") || (Boolean)def.get("process")) {
				var target = Files.createTempFile("osais", "bash");
				Files.writeString(target, templateResolver.resolve(loadFileTemplate(scriptPath.toAbsolutePath().toString()), variables), StandardCharsets.UTF_8);
				cmdArgs.addAll(List.of("bash", target.toAbsolutePath().toString()));
			}
			else {
				cmdArgs.addAll(List.of("bash", scriptPath.toAbsolutePath().toString()));
			}
		}
		else {
			throw new IllegalArgumentException("Hook script for add-on does not specify `line` or `command`.");
		}
		
		var prcbldr = new ProcessBuilder(cmdArgs);
		prcbldr.environment().putAll(variables.entrySet().stream().
				map(e ->  {
					if(e.getKey().startsWith("env.")) {
						return Map.entry(e.getKey().substring(4), e.getValue() == null ? "" : e.getValue());
					}
					else {
						return Map.entry(e.getKey().toUpperCase().replace('.', '_'), e.getValue() == null ? "" : e.getValue());
					}
				}).
				collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
		prcbldr.directory(addOnDir.toFile());
		prcbldr.inheritIO();
		
		LOG.info("Executing hook script for add-on with command: {} in directory: {}.", cmdArgs, addOnDir);
		
		var process = prcbldr.start();
		try {
			var exitCode = process.waitFor();
			if(exitCode != 0) {
				throw new IllegalStateException("Hook script for add-on exited with code " + exitCode + ".");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Hook script for add-on interrupted.", e);
		}
	}
	
	private void copyFile(DomainObject instance, Map<String, Object> def, Path addOnsDir, Map<String, String> variables) throws IOException {
		var target = getHookOpPath(instance, "target", def, variables);
		if(!target.isAbsolute()) {
			throw new IllegalArgumentException("Hook script for add-on specifies non-absolute target path: " + target);
		}
		
		var source = addOnsDir.resolve(getHookOpPath(instance, "source", def, variables)).normalize();
		mkdirsForPath(target);
		if(!def.containsKey("process") || (Boolean)def.get("process")) {
			LOG.info("Processing hook script for add-on copy from {} to {}.", source, target);
			Files.writeString(target, templateResolver.resolve(loadFileTemplate(source.toAbsolutePath().toString()), variables), StandardCharsets.UTF_8);
		}
		else {
			LOG.info("Copying hook script for add-on from {} to {}.", source, target);
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
		
	}
	
	private Path mkdirsForPath(Path path) throws IOException {
		var parent = path.getParent();
		if(parent != null && !Files.exists(parent)) {
			Files.createDirectories(parent);
		}
		return path;
	}

	private void deleteFile(DomainObject instance, Map<String, Object> def, Map<String, String> variables) throws IOException {
		var path = getHookOpPath(instance, "path", def, variables);
		LOG.info("Deleting hook script for add-on file {}.", path);
		Files.deleteIfExists(path);
		LOG.info("Deleted hook script for add-on file {}.", path);
	}

	private void createIniKey(DomainObject dobj, Map<String, Object> def, Map<String, String> variables) throws IOException {
		
		var path = getHookOpPath(dobj, "path", def, variables);
		
		var ini = INI.fromFile(path);
		var section = (String)def.get("section");
		Data sectionData = ini;
		if(section != null && !section.isBlank()) {
			sectionData = ini.sectionOr(templateResolver.resolve(section,variables)).orElse(null);
		}
		if(sectionData == null) {
			LOG.info("Hook script for add-on hook on {} specifies section '{}' in INI file {}, but section does not exist. Creating.", dobj.getName(), section, path);
			sectionData = ini.section(templateResolver.resolve(section,variables));
		}
		else {
			LOG.info("Hook script for add-on hook on {} specifies section '{}' in INI file {}, and section exists. Adding/updating key.", dobj.getName(), section, path);
			var key = Optional.ofNullable((String)def.get("key"))
					.map(template -> templateResolver.resolve(template, variables))
					.orElseThrow(() -> new IllegalArgumentException("Hook script for add-on on " + dobj.getName() + " does not specify INI key."));;
					
			sectionData.put(key, Optional.ofNullable((String)def.get("value"))
					.map(template -> templateResolver.resolve(template, variables))
					.orElseThrow(() -> new IllegalArgumentException("Hook script for add-on on " + dobj.getName() + " does not specify INI value.")));
			
			new INIWriter.Builder().build().write(ini, path);
		}
	}

	private void deleteIni(DomainObject dobj, Map<String, Object> def, Map<String, String> variables) throws IOException {
		
		var path = getHookOpPath(dobj, "path", def, variables);
		
		var ini = INI.fromFile(path);
		var section = (String)def.get("section");
		Data sectionData = ini;
		if(section != null && !section.isBlank()) {
			sectionData = ini.sectionOr(templateResolver.resolve(section,variables)).orElse(null);
		}
		if(sectionData == null) {
			LOG.warn("Hook script for add-on hook on {} specifies section '{}' in INI file {}, but section does not exist. Skipping.", dobj.getName(), section, path);
		}
		else {
			if(def.containsKey("key")) {
				var key = Optional.ofNullable((String)def.get("key"))
						.map(template -> templateResolver.resolve(template, variables))
						.get();
						
				if(sectionData.remove(key)) {
					LOG.info("Hook script for add-on on {} specifies key '{}' in INI file {}, and key exists. Deleting.", dobj.getName(), key, path);
					new INIWriter.Builder().build().write(ini, path);
				}
				else {
					LOG.warn("Hook script for add-on on {} specifies key '{}' in INI file {}, but key does not exist. Skipping.", dobj.getName(), key, path);
				}
			}
			else {
				if(section == null || section.isBlank()) {
					throw new IllegalArgumentException("Hook script for add-on on " + dobj.getName() + " does not specify INI section to delete.");
				}
				else {
					((Section)ini).remove();
					new INIWriter.Builder().build().write(ini, path);
				}
			}
		}
	}

	private void deleteJson(DomainObject dobj, Map<String, Object> def, Map<String, String> variables) throws IOException {
		var path = getHookOpPath(dobj, "path", def, variables);
		if (!Files.exists(path)) {
			LOG.warn("Hook script for add-on on {} references JSON file {}, but file does not exist. Skipping.", dobj.getName(), path);
			return;
		}

		var root = JSON_MAPPER.readTree(path.toFile());
		if (root == null) {
			LOG.warn("Hook script for add-on on {} references empty JSON file {}. Skipping.", dobj.getName(), path);
			return;
		}

		var pathTokenSets = resolveJsonPathTokenSets(def, variables, true, dobj);
		var changed = false;
		for (var tokens : pathTokenSets) {
			var parent = getJsonParentNode(root, tokens, false);
			if (parent == null) {
				LOG.warn("Hook script for add-on on {} references missing JSON path {} in {}. Skipping.", dobj.getName(),
						String.join("/", tokens), path);
				continue;
			}

			var leaf = tokens.get(tokens.size() - 1);
			if (parent.isObject()) {
				changed = ((ObjectNode) parent).remove(leaf) != null || changed;
			} else if (parent.isArray()) {
				var idx = parseArrayIndex(leaf, dobj, path);
				var array = (ArrayNode) parent;
				if (idx >= 0 && idx < array.size()) {
					array.remove(idx);
					changed = true;
					LOG.info("Hook script for add-on on {} removed JSON array index {} in {}.", dobj.getName(), idx, path);
				} else {
					LOG.warn("Hook script for add-on on {} references missing JSON key/index '{}' in {}. Skipping.",
							dobj.getName(), leaf, path);
				}
			} else {
				LOG.warn("Hook script for add-on on {} targets non-container JSON node at path {} in {}. Skipping.",
						dobj.getName(), String.join("/", tokens), path);
			}
		}

		if (changed) {
			JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), root);
		}
	}

	private void createJson(DomainObject dobj, Map<String, Object> def, Map<String, String> variables) throws IOException {
		var path = getHookOpPath(dobj, "path", def, variables);
		mkdirsForPath(path);

		JsonNode root;
		if (!Files.exists(path) || Files.size(path) == 0L) {
			root = JSON_MAPPER.createObjectNode();
		} else {
			root = JSON_MAPPER.readTree(path.toFile());
			if (root == null) {
				root = JSON_MAPPER.createObjectNode();
			}
		}

		var payload = resolveJsonPayload(dobj, def, variables);
		var tokens = resolveJsonPathTokens(def, variables, false, dobj);

		if (tokens.isEmpty()) {
			root = mergeOrReplaceNode(root, payload, resolveJsonMode(def, variables));
			LOG.info("Hook script for add-on on {} replaced root JSON node in {}.", dobj.getName(), path);
		} else {
			var parent = getJsonParentNode(root, tokens, true);
			if (parent == null) {
				throw new IllegalArgumentException("Hook script for add-on on " + dobj.getName()
						+ " has an invalid JSON target path.");
			}
			var leaf = tokens.get(tokens.size() - 1);
			if (parent.isObject()) {
				var objectParent = (ObjectNode) parent;
				var existing = objectParent.get(leaf);
				objectParent.set(leaf, mergeOrReplaceNode(existing, payload, resolveJsonMode(def, variables)));
				LOG.info("Hook script for add-on on {} set JSON key '{}' in {}.", dobj.getName(), leaf, path);
			} else if (parent.isArray()) {
				var idx = parseArrayIndex(leaf, dobj, path);
				var arrayParent = (ArrayNode) parent;
				ensureArraySize(arrayParent, idx + 1);
				var existing = arrayParent.get(idx);
				arrayParent.set(idx, mergeOrReplaceNode(existing, payload, resolveJsonMode(def, variables)));
				LOG.info("Hook script for add-on on {} set JSON array index {} in {}.", dobj.getName(), idx, path);
			} else {
				throw new IllegalArgumentException("Hook script for add-on on " + dobj.getName()
						+ " targets non-container JSON node at path " + String.join("/", tokens) + ".");
			}
		}

		JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), root);
	}

	private String resolveJsonMode(Map<String, Object> def, Map<String, String> variables) {
		var mode = Optional.ofNullable((String) def.get("mode"))
				.map(template -> templateResolver.resolve(template, variables))
				.map(String::trim)
				.map(String::toLowerCase)
				.orElse("merge");
		if (!mode.equals("merge") && !mode.equals("replace")) {
			throw new IllegalArgumentException("Unsupported JSON hook mode '" + mode + "'. Use 'merge' or 'replace'.");
		}
		return mode;
	}

	private JsonNode resolveJsonPayload(DomainObject dobj, Map<String, Object> def, Map<String, String> variables) throws IOException {
		if (!def.containsKey("json") && !def.containsKey("value")) {
			throw new IllegalArgumentException(
					"Hook script for add-on on " + dobj.getName() + " does not specify JSON payload (`json` or `value`).");
		}

		var payloadDef = def.containsKey("json") ? def.get("json") : def.get("value");
		if (payloadDef instanceof String payloadString) {
			var resolved = templateResolver.resolve(payloadString, variables);
			return JSON_MAPPER.readTree(resolved);
		}

		var resolvedObject = resolveTemplatedJsonValue(payloadDef, variables);
		return JSON_MAPPER.valueToTree(resolvedObject);
	}

	private Object resolveTemplatedJsonValue(Object value, Map<String, String> variables) {
		if (value instanceof String template) {
			return templateResolver.resolve(template, variables);
		}
		if (value instanceof Map<?, ?> mapValue) {
			var resolved = new LinkedHashMap<String, Object>();
			for (var entry : mapValue.entrySet()) {
				resolved.put(String.valueOf(entry.getKey()), resolveTemplatedJsonValue(entry.getValue(), variables));
			}
			return resolved;
		}
		if (value instanceof List<?> listValue) {
			var resolved = new ArrayList<Object>(listValue.size());
			for (var item : listValue) {
				resolved.add(resolveTemplatedJsonValue(item, variables));
			}
			return resolved;
		}
		return value;
	}

	private JsonNode mergeOrReplaceNode(JsonNode existing, JsonNode payload, String mode) {
		if (existing == null || existing.isMissingNode() || mode.equals("replace")) {
			return payload.deepCopy();
		}
		if (existing.isObject() && payload.isObject()) {
			var merged = existing.deepCopy();
			deepMergeObjectNodes((ObjectNode) merged, (ObjectNode) payload);
			return merged;
		}
		return payload.deepCopy();
	}

	private void deepMergeObjectNodes(ObjectNode target, ObjectNode update) {
		var fields = update.fields();
		while (fields.hasNext()) {
			var field = fields.next();
			var existing = target.get(field.getKey());
			if (existing != null && existing.isObject() && field.getValue().isObject()) {
				deepMergeObjectNodes((ObjectNode) existing, (ObjectNode) field.getValue());
			} else {
				target.set(field.getKey(), field.getValue().deepCopy());
			}
		}
	}

	private List<String> resolveJsonPathTokens(Map<String, Object> def,
			Map<String, String> variables,
			boolean required,
			DomainObject dobj) {
		var paths = resolveJsonPathTokenSets(def, variables, required, dobj);
		if (paths.isEmpty()) {
			return Collections.emptyList();
		}
		if (paths.size() > 1) {
			throw new IllegalArgumentException("Hook script for add-on on " + dobj.getName()
					+ " specifies multiple JSON paths where only one is supported.");
		}
		return paths.get(0);
	}

	private List<List<String>> resolveJsonPathTokenSets(Map<String, Object> def,
			Map<String, String> variables,
			boolean required,
			DomainObject dobj) {
		var tokenSets = new ArrayList<List<String>>();

		var rawPaths = def.get("jsonPaths");
		if (rawPaths instanceof List<?> listPaths) {
			for (var item : listPaths) {
				if (item == null) {
					continue;
				}
				var pathSpec = templateResolver.resolve(String.valueOf(item), variables).trim();
				if (!pathSpec.isBlank()) {
					tokenSets.add(parseJsonPathTokens(pathSpec));
				}
			}
		}

		if (tokenSets.isEmpty()) {
			var rawPath = Optional.ofNullable((String) def.get("jsonPath"))
					.or(() -> Optional.ofNullable((String) def.get("pointer")))
					.or(() -> Optional.ofNullable((String) def.get("section")))
					.map(template -> templateResolver.resolve(template, variables))
					.map(String::trim)
					.orElse("");

			if (rawPath.isBlank()) {
				if (required) {
					throw new IllegalArgumentException("Hook script for add-on on " + dobj.getName()
							+ " does not specify JSON path (`jsonPath`, `jsonPaths` or `pointer`).");
				}
				return Collections.emptyList();
			}

			tokenSets.add(parseJsonPathTokens(rawPath));
		}

		return tokenSets;
	}

	private List<String> parseJsonPathTokens(String rawPath) {
		if (rawPath.equals("/")) {
			return Collections.emptyList();
		}

		var slashMode = rawPath.startsWith("/");
		var tokens = new ArrayList<String>();
		var current = new StringBuilder();
		var escaped = false;
		for (var i = slashMode ? 1 : 0; i < rawPath.length(); i++) {
			var c = rawPath.charAt(i);
			if (escaped) {
				current.append(c);
				escaped = false;
				continue;
			}
			if (c == '\\') {
				escaped = true;
				continue;
			}
			if ((slashMode && c == '/') || (!slashMode && (c == '/' || c == '.'))) {
				addJsonPathToken(tokens, current, slashMode);
				continue;
			}
			current.append(c);
		}
		if (escaped) {
			current.append('\\');
		}
		addJsonPathToken(tokens, current, slashMode);
		return tokens;
	}

	private void addJsonPathToken(List<String> tokens, StringBuilder tokenBuilder, boolean decodeJsonPointer) {
		var token = tokenBuilder.toString();
		tokenBuilder.setLength(0);
		if (token.isBlank()) {
			return;
		}
		var normalized = decodeJsonPointer ? token.replace("~1", "/").replace("~0", "~") : token.trim();
		if (!normalized.isBlank()) {
			tokens.add(normalized);
		}
	}

	private JsonNode getJsonParentNode(JsonNode root, List<String> tokens, boolean createMissing) {
		if (tokens.isEmpty()) {
			return null;
		}

		var current = root;
		for (var i = 0; i < tokens.size() - 1; i++) {
			var token = tokens.get(i);
			var next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;

			if (current == null) {
				return null;
			}

			if (current.isObject()) {
				var objectNode = (ObjectNode) current;
				var child = objectNode.get(token);
				if (child == null || child.isNull()) {
					if (!createMissing) {
						return null;
					}
					child = isArrayToken(next) ? JSON_MAPPER.createArrayNode() : JSON_MAPPER.createObjectNode();
					objectNode.set(token, child);
				}
				current = child;
			} else if (current.isArray()) {
				var index = parseArrayIndexSafe(token);
				if (index < 0) {
					return null;
				}
				var arrayNode = (ArrayNode) current;
				if (index >= arrayNode.size()) {
					if (!createMissing) {
						return null;
					}
					ensureArraySize(arrayNode, index + 1);
				}
				var child = arrayNode.get(index);
				if (child == null || child.isNull()) {
					if (!createMissing) {
						return null;
					}
					child = isArrayToken(next) ? JSON_MAPPER.createArrayNode() : JSON_MAPPER.createObjectNode();
					arrayNode.set(index, child);
				}
				current = child;
			} else {
				return null;
			}
		}

		return current;
	}

	private int parseArrayIndex(String token, DomainObject dobj, Path jsonPath) {
		var index = parseArrayIndexSafe(token);
		if (index < 0) {
			throw new IllegalArgumentException("Hook script for add-on on " + dobj.getName()
					+ " contains non-numeric JSON array index '" + token + "' for file " + jsonPath + ".");
		}
		return index;
	}

	private int parseArrayIndexSafe(String token) {
		try {
			return Integer.parseInt(token);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private boolean isArrayToken(String token) {
		return parseArrayIndexSafe(token) >= 0;
	}

	private void ensureArraySize(ArrayNode arrayNode, int targetSize) {
		while (arrayNode.size() < targetSize) {
			arrayNode.addNull();
		}
	}

	private Path getHookOpPath(DomainObject instance, String key, Map<String, Object> def, Map<String, String> variables) {
		return Optional.ofNullable((String)def.get(key))
				.map(template -> templateResolver.resolve(template, variables))
				.map(Path::of)
				.orElseThrow(() -> new IllegalArgumentException("Hook script for add-on does not specify `" + key + "`."));
	}

	private Set<ContainerLevel> resolveAddOnLevels(Map<ContainerLevel, Map<String, Object>> extensions) {
		if (extensions == null || extensions.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add-on has no extensions.");
		}

		if (extensions.size() > 1) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Add-on has multiple extension types. Only one extension type per add-on is currently supported.");
		}

		return extensions.keySet();
	}

	private SimulatorInstanceData requireGridServiceSimulator() {
		var candidates = simulatorStateRepository.list().stream()
				.filter(sim -> sim.getLevel() == SimulatorLevel.STANDALONE || sim.getLevel() == SimulatorLevel.ROBUST)
				.toList();
		for (var sim : candidates) {
			try {
				var statuses = dockerService.getContainerStatuses(sim.getContainerIds());
				if (statuses.stream().anyMatch(ContainerStatus::running)) {
					return sim;
				}
			} catch (RuntimeException e) {
				LOG.warn("Failed to query container status while checking GRID add-on target simulator '{}'.", sim.getName(), e);
			}
		}
		throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
				"GRID add-ons require an active grid-services simulator (STANDALONE or ROBUST)." );
	}

	private void attachAddOnContainersToGridSimulator(String simulatorName, List<String> addOnContainerIds) {
		if (simulatorName == null || simulatorName.isBlank() || addOnContainerIds.isEmpty()) {
			return;
		}

		simulatorStateRepository.load(simulatorName).ifPresentOrElse(sim -> {
			var merged = new LinkedHashSet<>(sim.getContainerIds());
			merged.addAll(addOnContainerIds);
			sim.setContainerIds(new ArrayList<>(merged));
			simulatorStateRepository.save(sim);
			LOG.info("Attached {} GRID add-on container(s) to simulator '{}'.", addOnContainerIds.size(), simulatorName);
		}, () -> {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"GRID add-on attachment failed because simulator '" + simulatorName + "' no longer exists.");
		});
	}

	private void detachAddOnContainersFromGridSimulator(String simulatorName, List<String> addOnContainerIds) {
		if (simulatorName == null || simulatorName.isBlank() || addOnContainerIds == null || addOnContainerIds.isEmpty()) {
			return;
		}

		simulatorStateRepository.load(simulatorName).ifPresent(sim -> {
			var updated = sim.getContainerIds().stream()
					.filter(id -> !addOnContainerIds.contains(id))
					.toList();
			sim.setContainerIds(updated);
			simulatorStateRepository.save(sim);
			LOG.info("Detached {} GRID add-on container(s) from simulator '{}'.", addOnContainerIds.size(), simulatorName);
		});
	}

    private void materializeFiles(ResolvedAddOnPlan plan, AddOnInstanceData bot, List<java.nio.file.Path> writtenFiles) {
		var manifestDir = properties.getAddOnsDir().resolve(bot.getName()).toAbsolutePath().normalize();
		LOG.info("Materializing add-on '{}' using manifest directory '{}'.", bot.getName(), manifestDir);
		withManifestContext(bot.getName(), () -> materializeFiles(plan, writtenFiles));
    }

	@Override
	protected String loadManagedFileTemplate(String name, String targetName) {
		if (targetName == null || targetName.isBlank()) {
			var manifestDir = currentManifestDir.get();
			if (manifestDir != null) {
				var candidate = manifestDir.resolve(name).normalize();
				if (!candidate.startsWith(manifestDir)) {
					throw new IllegalArgumentException("Managed add-on template path escapes manifest directory: " + name);
				}
				if (Files.isRegularFile(candidate)) {
					try {
						LOG.info("Loading add-on managed template '{}' from manifest-relative file '{}'.", name, candidate);
						return Files.readString(candidate, StandardCharsets.UTF_8);
					} catch (IOException e) {
						throw new IllegalStateException("Failed to load add-on managed template file " + candidate + ".", e);
					}
				}
				LOG.info("Managed add-on template '{}' not found at '{}', falling back to default template lookup.", name,
						candidate);
			}
		}
		return super.loadManagedFileTemplate(name, targetName);
	}

	private void cloneRepository(String repository, String branch, Path addOnsDir) {
		var parent = addOnsDir.getParent();
		if (parent == null) {
			throw new IllegalStateException("Invalid add-ons directory '" + addOnsDir + "'.");
		}
		try {
			Files.createDirectories(parent);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to create add-ons directory parent '" + parent + "'.", e);
		}
		if (branch == null || branch.isBlank()) {
			git(parent, "clone", repository, addOnsDir.toString());
			return;
		}
		git(parent, "clone", "--branch", branch, "--single-branch", repository, addOnsDir.toString());
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim();
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

	private record ManagedContribution(String resource, Path dropInsDir, String resolvedContent) {
	}
}