package uk.co.bithatch.opensim.spawner.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.ContainerSpec;
import uk.co.bithatch.opensim.spawner.domain.ResolvedSimulatorPlan;
import uk.co.bithatch.opensim.spawner.domain.SimulatorInstanceData;
import uk.co.bithatch.opensim.spawner.domain.SimulatorLevel;
import uk.co.bithatch.opensim.spawner.state.GridStateRepository;

@Service
public class SimulatorLevelProfileService extends AbstractComponentProfileService<SimulatorInstanceData, ResolvedSimulatorPlan, SimulatorLevel> {

    public SimulatorLevelProfileService(
    		ObjectMapper objectMapper, 
    		SpawnerProperties properties, 
    		TemplateResolver templateResolver,
    		GridStateRepository gridStateRepository) {
    	super(objectMapper, properties, templateResolver, "grid-levels.json", "default-grid-levels.json", gridStateRepository);
    }

	@Override
	public Map<String, String> buildTypeVariables(SimulatorInstanceData sim, Map<String, String> variables) {
        
        variables.put("sim.name", sim.getName());
        variables.put("sim.normalisedName", sim.getName().replace(" ", "-"));
        variables.put("sim.port", String.valueOf(sim.getPort()));
        variables.put("sim.ownerEmail", sim.getOwnerEmail());
        variables.put("sim.ownerFirst", sim.getOwnerFirst());
        variables.put("sim.ownerLast", sim.getOwnerLast());
        variables.put("sim.ownerUuid", sim.getOwnerUuid());
        variables.put("sim.ownerPassword", sim.getOwnerPassword());
        variables.put("sim.level", sim.getLevel() == null ? "" : sim.getLevel().name());
        if(sim.getRegions() != null && sim.getRegions().length > 0) {
			var region = sim.getRegions()[0];
			variables.put("region.name", region.getName());
			variables.put("region.x", String.valueOf(region.getX()));
			variables.put("region.y", String.valueOf(region.getY()));
			variables.put("region.uuid", region.getUuid());
			variables.put("region.port", String.valueOf(region.getPort()));
			variables.put("region.width", String.valueOf(region.getWidth()));
			variables.put("region.height", String.valueOf(region.getHeight()));
		}
        
        return variables;
    }

	@Override
	protected ResolvedSimulatorPlan createPlan(SimulatorInstanceData bot, List<ContainerSpec> containers) {
        return new ResolvedSimulatorPlan(bot.getLevel(), containers);
	}
}
