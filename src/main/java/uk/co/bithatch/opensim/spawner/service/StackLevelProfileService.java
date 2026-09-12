package uk.co.bithatch.opensim.spawner.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.ContainerSpec;
import uk.co.bithatch.opensim.spawner.domain.ResolvedStackPlan;
import uk.co.bithatch.opensim.spawner.domain.StackComponent;
import uk.co.bithatch.opensim.spawner.domain.StackLevel;
import uk.co.bithatch.opensim.spawner.domain.StackState;
import uk.co.bithatch.opensim.spawner.state.StackStateRepository;

@Service
public class StackLevelProfileService  extends AbstractComponentProfileService<StackComponent, StackState, ResolvedStackPlan, StackLevel> {


    public StackLevelProfileService(
    		ObjectMapper objectMapper, 
    		SpawnerProperties properties, 
    		TemplateResolver templateResolver,
    		StackStateRepository gridStateRepository) {
    	super(objectMapper, properties, templateResolver, "stack-levels.json", "default-stack-levels.json", gridStateRepository);
    }

	@Override
	protected Class<StackComponent> getComponentClass() {
		return StackComponent.class;
	}

	@Override
	public Map<String, String> onBuildTypeVariables(StackState bot, Map<String, String> variables) {
        return variables;
    }

	@Override
	protected ResolvedStackPlan createPlan(StackState bot, List<ContainerSpec> containers, Map<String, String> variables) {
        return new ResolvedStackPlan(bot.getLevel(), containers, variables);
	}
}
