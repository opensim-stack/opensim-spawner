package uk.co.bithatch.opensim.spawner.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.sshtools.jini.Data;
import com.sshtools.jini.INI;
import com.sshtools.jini.INI.Section;
import com.sshtools.jini.INIWriter;

import uk.co.bithatch.opensim.jlib.Strings;
import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.AddOn;
import uk.co.bithatch.opensim.spawner.domain.AddOnInstanceData;
import uk.co.bithatch.opensim.spawner.domain.AddOnLevel;
import uk.co.bithatch.opensim.spawner.domain.ContainerSpec;
import uk.co.bithatch.opensim.spawner.domain.DomainObject;
import uk.co.bithatch.opensim.spawner.domain.HookType;
import uk.co.bithatch.opensim.spawner.domain.Manifest;
import uk.co.bithatch.opensim.spawner.domain.ResolvedAddOnPlan;
import uk.co.bithatch.opensim.spawner.domain.ResolvedBotPlan;
import uk.co.bithatch.opensim.spawner.domain.ResolvedSimulatorPlan;
import uk.co.bithatch.opensim.spawner.domain.SimulatorInstanceData;
import uk.co.bithatch.opensim.spawner.domain.SimulatorLevel;
import uk.co.bithatch.opensim.spawner.state.AddOnInstanceStateRepository;
import uk.co.bithatch.opensim.spawner.state.AddOnRepository;
import uk.co.bithatch.opensim.spawner.state.BotStateRepository;
import uk.co.bithatch.opensim.spawner.state.GridStateRepository;
import uk.co.bithatch.opensim.spawner.state.SimulatorStateRepository;

@Service
public class AddOnInstanceProvisioningService extends AbstractContainerGroupProvisioningService<AddOnInstanceStateRepository, AddOnInstanceData> {
	private static final Logger LOG = LoggerFactory.getLogger(AddOnInstanceProvisioningService.class);

	private final AddOnRepository addOnRepository;
	private final SpawnerProperties properties;
	private final AddOnProfileService profileService;
	private final BotStateRepository botStateRepository;
	private final SimulatorStateRepository simulatorStateRepository;
	private final BotLevelProfileService botLevelProfileService;
	private final SimulatorLevelProfileService simulatorLevelProfileService;
	private final ThreadLocal<Path> currentManifestDir = new ThreadLocal<>();
	private final GridStateRepository gridStateRepository;

	public AddOnInstanceProvisioningService(AddOnRepository addOnRepository,
			AddOnInstanceStateRepository addOnInstanceStateRepository, 		
			SpawnerProperties properties,
			TemplateResolver templateResolver,
			AddOnProfileService profileService,
			BotStateRepository botStateRepository,
			SimulatorStateRepository simulatorStateRepository,
			BotLevelProfileService botLevelProfileService,
			SimulatorLevelProfileService simulatorLevelProfileService,
			GridStateRepository gridStateRepository,
			DockerService dockerService) {
		super(addOnInstanceStateRepository, dockerService, templateResolver, properties		);
		this.addOnRepository = addOnRepository;
		this.properties = properties;
		this.profileService = profileService;
		this.botStateRepository = botStateRepository;
		this.simulatorStateRepository = simulatorStateRepository;
		this.botLevelProfileService = botLevelProfileService;
		this.simulatorLevelProfileService = simulatorLevelProfileService;
		this.gridStateRepository = gridStateRepository;

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
			var created = installAddOn(addOnName, Map.of());
			var contributions = resolveAddOnManagedContributions(created);
			reconcileParentConfigurations(contributions, "enabled", addOnName);
		}
	}
	
	public void disableAddOn(String addOnName) {
		if(exists(addOnName)) {
			var addOn = stateRepository.load(addOnName)
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Add-on not found."));
			var contributions = resolveAddOnManagedContributions(addOn);
			
			var mfOpt = addOnRepository.load(addOnName);
			mfOpt.ifPresent(mf -> {
	            runHooks(HookType.PRE_UNINSTALL, mf, addOn);	
			});
			
			if (addOn.getLevel() == AddOnLevel.SIMULATOR && !addOn.getContainerIds().isEmpty()) {
				detachAddOnContainersFromGridSimulator(addOn.getGridServiceSimulatorName(), addOn.getContainerIds());
			}
			deleteContainerGroup(addOnName);
			removeAddOnManagedContributions(contributions);
			reconcileParentConfigurations(contributions, "disabled", addOnName);
			mfOpt.ifPresent(mf -> {
	            runHooks(HookType.POST_UNINSTALL, mf, addOn);	
			});
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
			var variables = botLevelProfileService.buildBaseVariables(bot,  new LinkedHashMap<>());
			var plan = botLevelProfileService.resolvePlan(bot, Map.of());
			if (!hasManagedTargetResourceOverlap(plan.containers(), variables, changedResources)) {
				continue;
			}

			LOG.info("Re-materializing bot '{}' due to managed resource overlap with {}.", bot.displayName(), changedResources);
			materializeFiles(plan, variables);
			restartContainerIds("bot " + bot.displayName(), bot.getContainerIds());
			refreshed++;
		}
		return refreshed;
	}

	private int refreshSimulatorsForManagedResources(Set<String> changedResources) {
		var refreshed = 0;
		for (var sim : simulatorStateRepository.list()) {
			var variables = simulatorLevelProfileService.buildBaseVariables(sim,  new LinkedHashMap<>());
			var plan = simulatorLevelProfileService.resolvePlan(sim, Map.of());
			if (!hasManagedTargetResourceOverlap(plan.containers(), variables, changedResources)) {
				continue;
			}

			LOG.info("Re-materializing simulator '{}' due to managed resource overlap with {}.", sim.displayName(), changedResources);
			materializeFiles(plan, variables);
			restartContainerIds("simulator " + sim.displayName(), sim.getContainerIds());
			refreshed++;
		}
		return refreshed;
	}

	private int refreshStackAddOnsForManagedResources(Set<String> changedResources) {
		var refreshed = 0;
		for (var addOnInstance : stateRepository.list()) {
			var variables = profileService.buildBaseVariables(addOnInstance,  new LinkedHashMap<>());
			var plan = profileService.resolvePlan(addOnInstance, Map.of());
			if (!hasManagedTargetResourceOverlap(plan.containers(), variables, changedResources)) {
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

	private void materializeFiles(ResolvedBotPlan plan, Map<String, String> variables) {
		materializeFiles(plan, new ArrayList<>(), variables);
	}

	private void materializeFiles(ResolvedSimulatorPlan plan, Map<String, String> variables) {
		materializeFiles(plan, new ArrayList<>(), variables);
	}

	private List<ManagedContribution> resolveAddOnManagedContributions(AddOnInstanceData addOn) {
		var variables = profileService.buildBaseVariables(addOn,  new LinkedHashMap<>());
		var plan = profileService.resolvePlan(addOn, Map.of());
		var contributions = new ArrayList<ManagedContribution>();

		withManifestContext(addOn.getName(), () -> {
			for (var container : plan.containers()) {
				for (var managedFile : container.getManagedFiles()) {
					var targetName = templateResolver.resolve(managedFile.target(), variables);
					if (targetName != null && !targetName.isBlank()) {
						continue;
					}
					var templateName = managedFile.resource();
					var dropInDir = Path.of(managedFile.dropIns());
					var template = loadManagedFileTemplate(templateName, targetName);
					var resolved = templateResolver.resolve(template, variables);
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
        
        var createRequestFields = requestFields == null ? Map.<String, String>of() : requestFields;

        var addOnInstance = new AddOnInstanceData();
        addOnInstance.setName(name);
		var manifest = addOnRepository.load(name)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Add-on not found."));
		var addOnLevel = resolveAddOnLevel(manifest.getExtensions());
		addOnInstance.setLevel(addOnLevel);

		SimulatorInstanceData attachedGridSimulator = null;
		if (addOnLevel == AddOnLevel.SIMULATOR) {
		  attachedGridSimulator = requireGridServiceSimulator();
		  addOnInstance.setGridServiceSimulatorName(attachedGridSimulator.getName());
		  LOG.info("GRID add-on '{}' attached to grid-service simulator '{}'.", name, attachedGridSimulator.getName());
		}
        
        var materializedFiles = new ArrayList<java.nio.file.Path>();
        var createdContainerIds = new ArrayList<String>();
        var containerRequestFields = new LinkedHashMap<>(createRequestFields);
        
        try {
        	if(!manifest.getTokens().isEmpty()) {
        		var gridState = gridStateRepository.get();
				LOG.info("Add-on {} has {} token(s) defined in manifest.", name, manifest.getTokens().size());
				var changes = new AtomicBoolean(false);
	        	manifest.getTokens().forEach(key -> {
	        		if(!gridState.getTokens().containsKey(key)) {
	        			var tokenValue = java.util.UUID.randomUUID().toString();
	        			gridState.getTokens().put(key, tokenValue);
	        			LOG.info("Add-on {} token '{}' generated and added to grid state.", name, key);
	        			changes.set(true);
	        		}
	        	});
	        	if(changes.get()) {
	        		gridStateRepository.save();
	        		LOG.info("Grid state updated with new token(s) for add-on {}.", name);
	        	}
			}

            stateRepository.save(addOnInstance);
            
            runHooks(HookType.PRE_INSTALL, manifest, addOnInstance);
            
            var plan = profileService.resolvePlan(addOnInstance, containerRequestFields);
            LOG.info("Resolved {} container spec(s) for add-on {}.", plan.containers().size(), name);
            materializeFiles(plan, addOnInstance, materializedFiles);
			wireInitScripts(plan, addOnInstance);

            createdContainerIds.addAll(dockerService.createContainers(plan.containers()));
            LOG.info("Created {} container(s) for add-on {}.", createdContainerIds.size(), addOnInstance);
            addOnInstance.setContainerIds(createdContainerIds);
            stateRepository.save(addOnInstance);

			if (attachedGridSimulator != null && !createdContainerIds.isEmpty()) {
			  attachAddOnContainersToGridSimulator(attachedGridSimulator.getName(), createdContainerIds);
			}
            
            runHooks(HookType.POST_INSTALL, manifest, addOnInstance);

            dockerService.startContainers(createdContainerIds);
            LOG.info("Started {} container(s) for add-on {}.", createdContainerIds.size(), name);
            waitForStartupWindow(createdContainerIds, Duration.ofMinutes(1), Duration.ofSeconds(2));
            LOG.info("Add-on {} provisioned successfully.", name);
            

            return addOnInstance;
        } catch (RuntimeException e) {
            LOG.error("Provisioning failed for add-on {}. Starting rollback.", name, e);
            if (addOnInstance.getLevel() == AddOnLevel.SIMULATOR && !createdContainerIds.isEmpty()) {
            	detachAddOnContainersFromGridSimulator(addOnInstance.getGridServiceSimulatorName(), createdContainerIds);
            }
            rollbackFailedProvision(name, createdContainerIds, materializedFiles);
            throw e;
        }
    }
	
	private void runHooks(HookType hookType, Manifest manifest, AddOnInstanceData addOnInstance) {
		var hookScript = manifest.getHooks().get(hookType);
		var variables = profileService.buildBaseVariables(addOnInstance,  new LinkedHashMap<>());
		if(hookScript == null) {
			LOG.info("No hook scripts for type {} in add-on {}", hookType, manifest.getName());
		}
		else {
			LOG.info("Starting hook script for type {} in add-on {}", hookType, manifest.getName());
			try {
				for(var scriptDef : hookScript) {
					var addOn = AddOnLevel.valueOf((String)scriptDef.getOrDefault("addOn", AddOnLevel.STACK.name()));
					switch(addOn) {
					case SIMULATOR:
						var simType = SimulatorLevel.valueOf((String)scriptDef.getOrDefault("level", SimulatorLevel.STANDALONE.name()));
						for(var sim : simulatorStateRepository.list().stream().filter(s -> s.getLevel() == simType).toList()) {
							LOG.info("Executing {} hook script for add-on {} on simulator {}.", hookType, manifest.getName(), sim.getName());
							runHooksForSimulator(sim, scriptDef, manifest, new LinkedHashMap<>(variables));
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
	
	private void runHooksForSimulator(SimulatorInstanceData sim, Map<String, Object> def, Manifest manifest, Map<String, String> variables) throws IOException {
		var addOnDir = addOnRepository.resolve(manifest.getName())
				.orElseThrow(() -> new IllegalStateException("Add-on manifest directory not found for " + manifest.getName() + ".")).toAbsolutePath().getParent();

		variables = simulatorLevelProfileService.buildBaseVariables(sim, variables);
		
		switch((String)def.getOrDefault("type", "throw")) {
		case "copy":
		{
			copyFile(def, addOnDir, variables);
			break;
		}
		case "delete":
		{
			deleteFile(def, variables);
			break;
		}
		case "deleteIni":
		{
			deleteIni(sim, def, variables);
			break;
		}
		case "createIniKey":
		{
			createIniKey(sim, def, variables);
			break;
		}
		case "exec":
		{
			exec(def, addOnDir, variables);
			break;
		}
		case "throw":
			throw new UnsupportedOperationException("Hook script type not specified for add-on install on simulator " + sim.getName() + ".");
		}
	}
	
	private void exec(Map<String, Object> def, Path addOnDir, Map<String, String> variables) throws IOException {
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
			var scriptPath = addOnDir.resolve(getHookOpPath("script", def, variables)).normalize();
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
	
	private void copyFile(Map<String, Object> def, Path addOnsDir, Map<String, String> variables) throws IOException {
		var target = getHookOpPath("target", def, variables);
		if(!target.isAbsolute()) {
			throw new IllegalArgumentException("Hook script for add-on specifies non-absolute target path: " + target);
		}
		
		var source = addOnsDir.resolve(getHookOpPath("source", def, variables)).normalize();
		mkdirsForPath(target);
		if(!def.containsKey("process") || (Boolean)def.get("process")) {
			Files.writeString(target, templateResolver.resolve(loadFileTemplate(source.toAbsolutePath().toString()), variables), StandardCharsets.UTF_8);
		}
		else {
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

	private void deleteFile(Map<String, Object> def, Map<String, String> variables) throws IOException {
		var path = getHookOpPath("path", def, variables);
		Files.deleteIfExists(path);
	}

	private void createIniKey(DomainObject dobj, Map<String, Object> def, Map<String, String> variables) throws IOException {
		
		var path = getHookOpPath("path", def, variables);
		
		var ini = INI.fromFile(path);
		var section = (String)def.get("section");
		Data sectionData = ini;
		if(section != null && !section.isBlank()) {
			sectionData = ini.sectionOr(templateResolver.resolve(section,variables)).orElse(null);
		}
		if(sectionData == null) {
			LOG.info("Hook script for add-on hook on {} specifies section '{}' in INI file {}, but section does not exist. Creataing.", dobj.getName(), section, path);
			sectionData = ini.section(templateResolver.resolve(section,variables));
		}
		else {
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
		
		var path = getHookOpPath("path", def, variables);
		
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

	private Path getHookOpPath(String key, Map<String, Object> def, Map<String, String> variables) {
		return Optional.ofNullable((String)def.get(key))
				.map(template -> templateResolver.resolve(template, variables))
				.map(Path::of)
				.orElseThrow(() -> new IllegalArgumentException("Hook script for add-on does not specify `" + key + "`."));
	}

	private AddOnLevel resolveAddOnLevel(Map<AddOnLevel, Map<String, ContainerSpec>> extensions) {
		if (extensions == null || extensions.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add-on has no extensions.");
		}

		if (extensions.size() > 1) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Add-on has multiple extension types. Only one extension type per add-on is currently supported.");
		}

		var addOnType = extensions.keySet().iterator().next();
		return AddOnLevel.valueOf(addOnType.name());
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
		withManifestContext(bot.getName(), () -> materializeFiles(plan, writtenFiles, profileService.buildBaseVariables(bot,  new LinkedHashMap<>())));
    }

	private void wireInitScripts(ResolvedAddOnPlan plan, AddOnInstanceData addOnInstance) {
		var containersWithInit = plan.containers().stream()
				.filter(container -> container.getInit() != null && !container.getInit().isEmpty())
				.toList();
		if (containersWithInit.isEmpty()) {
			return;
		}

		var manifestDir = properties.getAddOnsDir().resolve(addOnInstance.getName()).toAbsolutePath().normalize();
		var initScript = manifestDir.resolve("init.sh").normalize();
		if (!initScript.startsWith(manifestDir) || !Files.isRegularFile(initScript)) {
			throw new IllegalStateException("Add-on '" + addOnInstance.getName()
					+ "' defines init containers, but manifest-local init.sh was not found at " + initScript + ".");
		}

		for (var parent : containersWithInit) {
			for (var initSpec : parent.getInit().values()) {
				if (initSpec.getInit() != null && !initSpec.getInit().isEmpty()) {
					throw new IllegalArgumentException("Nested init containers are not supported.");
				}
				initSpec.getVolumes().putIfAbsent(initScript.toString(), "/init.sh");
			}
		}

		LOG.info("Wired init.sh '{}' into {} init container specification(s) for add-on '{}'.",
				initScript,
				containersWithInit.stream().mapToInt(parent -> parent.getInit().size()).sum(),
				addOnInstance.getName());
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

	private record ManagedContribution(String resource, Path dropInsDir, String resolvedContent) {
	}
}