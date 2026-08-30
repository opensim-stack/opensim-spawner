package uk.co.bithatch.opensim.spawner.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.AddOnInstanceData;
import uk.co.bithatch.opensim.spawner.domain.AddOnLevel;
import uk.co.bithatch.opensim.spawner.domain.ContainerSpec;
import uk.co.bithatch.opensim.spawner.domain.ResolvedAddOnPlan;
import uk.co.bithatch.opensim.spawner.state.AddOnRepository;
import uk.co.bithatch.opensim.spawner.state.GridStateRepository;

@Service
public class AddOnProfileService extends AbstractProfileService<AddOnInstanceData, ResolvedAddOnPlan, AddOnLevel> {
	private static final Logger LOG = LoggerFactory.getLogger(AddOnProfileService.class);

    private final GridStateRepository gridStateRepository;
    private final AddOnRepository addOnRepository;

	public AddOnProfileService(
			ObjectMapper objectMapper, 
			GridStateRepository gridStateRepository, 
			SpawnerProperties properties, 
			TemplateResolver templateResolver,
			AddOnRepository addOnRepository) {
    	super(objectMapper, properties, templateResolver);
    	this.gridStateRepository = gridStateRepository;
    	this.addOnRepository = addOnRepository;
    }

	@Override
	public Map<String, String> buildBaseVariables(AddOnInstanceData addOnInstance) {
        var variables = new LinkedHashMap<String, String>();
        var grid = gridStateRepository.get();
        variables.put("grid.adminToken", grid.getAdminToken());
        variables.put("grid.name", grid.getName());
        variables.put("grid.nick", grid.getNick());
        

        var addOn = addOnRepository.load(addOnInstance.getName()).orElseThrow(() -> 
	        new ResponseStatusException(HttpStatus.FORBIDDEN, "Simulator already exists.")
	    );
        addOn.getConstants().forEach((key, value) -> {
        	var envar = System.getenv(key);
        	if(envar == null) {
        		variables.putIfAbsent("env." + key, value);
        	}
        });

        for (var envEntry : System.getenv().entrySet()) {
            variables.put("env." + envEntry.getKey(), envEntry.getValue());
        }
        
        variables.putAll(properties.buildVariables());
        
        return variables;
    }

	@Override
	protected ResolvedAddOnPlan createPlan(AddOnInstanceData addOn, List<ContainerSpec> containers) {
        return new ResolvedAddOnPlan(addOn.getLevel(), containers);
	}

	@Override
	protected JsonNode getLevelNode(AddOnLevel level, String name) {
		return addOnRepository.loadRaw(name).orElseThrow(() -> new IllegalStateException("Add-on level " + level.name() + " not found in " + name + ".")).
					get("extensions").get(level.name());
	}
}
