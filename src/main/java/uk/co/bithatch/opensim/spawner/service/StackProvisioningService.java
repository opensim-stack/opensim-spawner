package uk.co.bithatch.opensim.spawner.service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.StackComponent;
import uk.co.bithatch.opensim.spawner.domain.StackLevel;
import uk.co.bithatch.opensim.spawner.domain.StackState;
import uk.co.bithatch.opensim.spawner.state.StackStateRepository;

@Service
public class StackProvisioningService extends AbstractContainerGroupProvisioningService<StackComponent, StackLevel, StackStateRepository, StackState> {

	private static final Logger LOG = LoggerFactory.getLogger(StackProvisioningService.class);
    
	private final StackLevelProfileService profileService;
	
	public StackProvisioningService(
			StackStateRepository stateRepository, 
			StackLevelProfileService profileService,
			DockerService dockerService,
			TemplateResolver templateResolver, 
			SpawnerProperties properties,
			RandomPasswordService randomPasswordService) {
		super(stateRepository, stateRepository, dockerService, templateResolver, properties, randomPasswordService);
		this.profileService = profileService;
	}
	
	public StackState provisionStack() {
		var state = stateRepository.get();
        state.setLevel(StackLevel.STACK);
        var name = state.getName();
        var materializedFiles = new ArrayList<Path>();
        var createdContainerIds = new ArrayList<String>();
        var stack = profileService.component();
        List<String> previousContainerIds = state.getContainerIds() != null ? new ArrayList<>(state.getContainerIds()) : Collections.emptyList();
		
		try { 
			
			installTokens(name, stack);
			installExports(name, stack);
			
            var env = resolveEnvironment(stack.getConstants(), Collections.emptyMap());
            env.forEach((k, v) -> LOG.info("Environment variable {}={}", k, v));
            
			var plan = profileService.resolvePlan(state, env);
            
            LOG.info("Resolved {} container spec(s) for stack {}.", plan.containers().size(), name);
            materializeFiles(plan, materializedFiles);

            createdContainerIds.addAll(dockerService.createContainers(plan.containers()));
            LOG.info("Created {} container(s) for stack {}.", createdContainerIds.size(), state);
            state.setContainerIds(createdContainerIds);
            stateRepository.save();

            dockerService.startContainers(createdContainerIds);
            LOG.info("Started {} container(s) for stack {}.", createdContainerIds.size(), name);
            waitForStartupWindow(createdContainerIds, Duration.ofMinutes(1), Duration.ofSeconds(2));
            LOG.info("Stack {} provisioned successfully.", name);
            

            return state;
        } catch (RuntimeException e) {
            LOG.error("Provisioning failed for stack {}. Starting rollback.", name, e);
            rollbackFailedProvision(name, createdContainerIds, materializedFiles);
            state.setContainerIds(previousContainerIds);
            state.setLevel(null);
            stateRepository.save();
            throw e;
        }
	}

	@Override
	protected Map<String, Object> toResponse(StackState stack) {
        var status = new LinkedHashMap<String, Object>();
        status.put("name", stack.getName());
        status.put("level", stack.getLevel() == null ? null : stack.getLevel().name());
        status.put("nick", stack.getNick());
        status.put("welcomeMessage", stack.getWelcomeMessage());
        return status;
	}
}
