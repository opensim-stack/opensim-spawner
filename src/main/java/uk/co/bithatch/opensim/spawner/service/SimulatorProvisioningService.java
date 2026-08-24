package uk.co.bithatch.opensim.spawner.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.RegionInstanceData;
import uk.co.bithatch.opensim.spawner.domain.ResolvedSimulatorPlan;
import uk.co.bithatch.opensim.spawner.domain.SimulatorInstanceData;
import uk.co.bithatch.opensim.spawner.domain.SimulatorLevel;
import uk.co.bithatch.opensim.spawner.state.SimulatorStateRepository;

@Service
public class SimulatorProvisioningService extends AbstractContainerGroupProvisioningService<SimulatorStateRepository, SimulatorInstanceData> {

    private static final Logger LOG = LoggerFactory.getLogger(SimulatorProvisioningService.class);
    
    private final OARs oars;
	private final RandomPasswordService passwordService;
	private final OpenSimService openSimService;
	private final SimulatorLevelProfileService profileService;
    
	public SimulatorProvisioningService(
			SimulatorStateRepository stateRepository,
			DockerService dockerService,
            OpenSimService openSimService,
            SimulatorLevelProfileService profileService,
            RandomPasswordService passwordService,
            TemplateResolver templateResolver,
			SpawnerProperties properties,
            OARs oars
			) {
		super(stateRepository, dockerService, templateResolver, properties);
		this.oars = oars;
		this.openSimService = openSimService;
		this.passwordService = passwordService;
		this.profileService = profileService;
	}

	public int nextPort(SimulatorLevel level) {
		if (level == SimulatorLevel.ROBUST) {
			return properties.getOpensimRobustPublicPort();
		}
		else {
			var usedPorts = new HashSet<Integer>();
			stateRepository.list().stream()
				.filter(sim -> sim.getLevel() == level)
				.forEach(sim -> {
					if (sim.getPort() != 0) {
						usedPorts.add(sim.getPort());
					}
				});
			if(!usedPorts.contains(properties.getFirstPort())) {
				return properties.getFirstPort();
			}
			else {
				return usedPorts.stream().max(Integer::compareTo).get() + 1;
			}
		}
	}

  public boolean hasActiveGridLoginService() {
    var candidates = stateRepository.list().stream()
        .filter((sim) -> sim.getLevel() != null && sim.getLevel().providesGridService())
        .toList();
    if (candidates.isEmpty()) {
      return false;
    }

    for (var sim : candidates) {
      try {
        var statuses = dockerService.getContainerStatuses(sim.getContainerIds());
        if (statuses.stream().anyMatch(ContainerStatus::running)) {
          return true;
        }
      } catch (RuntimeException e) {
        LOG.warn("Could not resolve container state while checking grid login availability for simulator {}.",
            sim.getName(),
            e);
      }
    }

    return false;
  }

    public synchronized SimulatorInstanceData createSim(String name, String levelName, Map<String, String> requestFields) {
        if (stateRepository.exists(name)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Simulator already exists.");
        }
        
        if(stateRepository.list().size() >= properties.getOpensimMaxSimulators()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Maximum number of simulators reached.");
		}

        var createRequestFields = requestFields == null ? Map.<String, String>of() : requestFields;
        var level = SimulatorLevel.fromNullable(levelName);
        validateRequestedLevel(level);

        var port = defaultValue(createRequestFields.get("port"), () -> String.valueOf(nextPort(level)));

        var sim = new SimulatorInstanceData();
        sim.setName(name);
        sim.setPort(Integer.parseInt(port));
        sim.setLevel(level);
        
        String oarName = null;
        OARs.OAR oar = null;
        
        if(level.requiresRegion()) {

            var password = defaultValue(createRequestFields.get("ownerPassword"),  passwordService::nextPassword);
            var first = valueOrFail(createRequestFields.get("ownerFirst"));
            var last = valueOrFail(createRequestFields.get("ownerLast"));
            var email = defaultEmail(first, last, createRequestFields.get("ownerEmail"));
            var uuid = defaultValue(createRequestFields.get("ownerUuid"), () -> UUID.randomUUID().toString());
            oarName = resolveRequestedOAR(level, createRequestFields);
    		
            oar = oars.getOAR(oarName);
            
            LOG.info("Creating simulator {} (level={}, port={}, ownerEmail={}, ownerUuid={}, ownerFirst={}, ownerLast={}, oar={}).",
                    name,
                    level.name(),
                    port,
                    email,
                    uuid,
                    first,
                    last,
                    oarName);
            
            var region = new RegionInstanceData();
            region.setUuid(defaultValue(createRequestFields.get("regionUuid"), () -> UUID.randomUUID().toString()));
            region.setX(Integer.parseInt(defaultValue(createRequestFields.get("regionX"), () -> "1000")));
            region.setY(Integer.parseInt(defaultValue(createRequestFields.get("regionY"), () -> "1000")));
            if(oar != null) {
	            region.setWidth(oar.sx());
	            region.setHeight(oar.sy());
	            region.setOar(oarName);
			}
            
            sim.setOwnerFirst(first);
            sim.setOwnerLast(last);
            sim.setOwnerPassword(password);
            sim.setOwnerEmail(email);
            sim.setOwnerUuid(uuid);
			sim.setRegions(new RegionInstanceData[] { region });
		}

        var materializedFiles = new ArrayList<java.nio.file.Path>();
        var createdContainerIds = new ArrayList<String>();
        var containerRequestFields = new LinkedHashMap<>(createRequestFields);
        containerRequestFields.remove("ownerFirst");
        containerRequestFields.remove("ownerLast");
        containerRequestFields.remove("ownerEmail");
        containerRequestFields.remove("ownerPassword");
        containerRequestFields.remove("port");
        containerRequestFields.remove("oar");
        containerRequestFields.remove("regionUuid");
        containerRequestFields.remove("regionX");
        containerRequestFields.remove("regionY");
        
        try {

            stateRepository.save(sim);
            
            var plan = profileService.resolvePlan(sim, containerRequestFields);
            LOG.info("Resolved {} container spec(s) for sim {}.", plan.containers().size(), name);
            materializeFiles(plan, sim, materializedFiles);

            createdContainerIds.addAll(dockerService.createContainers(plan.containers()));
            LOG.info("Created {} container(s) for sim {}.", createdContainerIds.size(), sim);
            sim.setContainerIds(createdContainerIds);
            stateRepository.save(sim);

            dockerService.startContainers(createdContainerIds);
            LOG.info("Started {} container(s) for sim {}.", createdContainerIds.size(), name);
            waitForStartupWindow(createdContainerIds, Duration.ofMinutes(1), Duration.ofSeconds(2));
            LOG.info("Sim {} provisioned successfully.", name);
            

        	if(oar != null) {
        		LOG.info("Sim {} requires a region, importing OAR {}.", name, oarName);
	            var workspaceArchivePath = copyArchiveToWorkspace(oar.archivePath(), materializedFiles);
	            openSimService.loadRegionArchive(workspaceArchivePath.toString());
        	}
        	
            return sim;
        } catch (RuntimeException e) {
            LOG.error("Provisioning failed for sim name. Starting rollback.", name, e);
            rollbackFailedProvision(name, createdContainerIds, materializedFiles);
            throw e;
        }
    }

	@Override
	public Map<String, Object> toResponse(SimulatorInstanceData bot) {
        var status = new LinkedHashMap<String, Object>();
        status.put("name", bot.getName());
        status.put("level", bot.getLevel() == null ? null : bot.getLevel().name());
        status.put("ownerUuid", bot.getOwnerUuid());
        status.put("ownerFirst", bot.getOwnerFirst());
        status.put("ownerLast", bot.getOwnerLast());
        status.put("port", bot.getPort());
    if (bot.getRegions() == null) {
      return status;
    }
    for(RegionInstanceData region : bot.getRegions()) {
			var regionStatus = new LinkedHashMap<String, Object>();
			regionStatus.put("uuid", region.getUuid());
			regionStatus.put("x", region.getX());
			regionStatus.put("y", region.getY());
			regionStatus.put("oar", region.getOar());
			status.put("region", regionStatus);
		}
        return status;
	}

    private void validateRequestedLevel(SimulatorLevel requestedLevel) {
        var existingSims = stateRepository.list();
        if (existingSims.isEmpty()) {
            if (requestedLevel == SimulatorLevel.ROBUST || requestedLevel == SimulatorLevel.STANDALONE) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "First simulator level must be ROBUST or STANDALONE.");
        }

        var hasStandalone = existingSims.stream().anyMatch(sim -> sim.getLevel() == SimulatorLevel.STANDALONE);
        if (hasStandalone) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A STANDALONE simulator is already running. No additional simulators can be created.");
        }

        var hasRobust = existingSims.stream().anyMatch(sim -> sim.getLevel() == SimulatorLevel.ROBUST);
        if (hasRobust) {
            if (requestedLevel == SimulatorLevel.GRID) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "When ROBUST exists, only GRID simulators can be created.");
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Cannot create simulator for current topology. Create a ROBUST root simulator first.");
    }

    private void materializeFiles(ResolvedSimulatorPlan plan, SimulatorInstanceData bot, List<java.nio.file.Path> writtenFiles) {
        materializeFiles(plan, writtenFiles, profileService.buildBaseVariables(bot));
    }
    
    private String resolveRequestedOAR(SimulatorLevel level, Map<String, String> requestFields) {
        var value = requestFields.get("oar");
        if("".equals(value)) {
			return null;
		}
		var requested = nonBlankOrNull(value);
        if (requested != null) {
            return requested;
        }

        var profileDefault = nonBlankOrNull(profileService.resolveLevelField(level, "oar"));
        if (profileDefault != null) {
            return profileDefault;
        }

        var configuredOARs = oars.listNames();
        if (configuredOARs.isEmpty()) {
            throw new IllegalStateException("No OARs are configured in oars.properties.");
        }
        return configuredOARs.get(ThreadLocalRandom.current().nextInt(configuredOARs.size()));
    }

}
