package uk.co.bithatch.opensim.spawner.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import uk.co.bithatch.opensim.spawner.domain.SimulatorLevel;
import uk.co.bithatch.opensim.spawner.state.AddOnRepository;
import uk.co.bithatch.opensim.spawner.state.GridStateRepository;
import uk.co.bithatch.opensim.spawner.state.SimulatorStateRepository;

@Service
public class AddOnProfileService extends AbstractProfileService<AddOnInstanceData, ResolvedAddOnPlan, AddOnLevel> {

	private final AddOnRepository addOnRepository;
	private final SimulatorStateRepository simulatorStateRepository;

	public AddOnProfileService(ObjectMapper objectMapper, GridStateRepository gridStateRepository,
			SpawnerProperties properties, TemplateResolver templateResolver, AddOnRepository addOnRepository,
			SimulatorStateRepository simulatorStateRepository) {
		super(objectMapper, properties, templateResolver, gridStateRepository);
		this.addOnRepository = addOnRepository;
		this.simulatorStateRepository = simulatorStateRepository;
	}

	@Override
	public Map<String, String> buildTypeVariables(AddOnInstanceData addOnInstance, Map<String, String> variables) {

		variables.putAll(properties.buildVariables());

		if (addOnInstance.getLevel() == AddOnLevel.SIMULATOR) {
			var attachedName = addOnInstance.getGridServiceSimulatorName();
			var attached = (attachedName == null || attachedName.isBlank()) ? findGridServiceSimulator()
					: simulatorStateRepository.load(attachedName);
			attached.ifPresent(sim -> {
				variables.put("sim.name", sim.getName());
				variables.put("sim.port", String.valueOf(sim.getPort()));
				variables.put("sim.level", sim.getLevel() == null ? "" : sim.getLevel().name());
				variables.put("sim.ownerFirst", sim.getOwnerFirst() == null ? "" : sim.getOwnerFirst());
				variables.put("sim.ownerLast", sim.getOwnerLast() == null ? "" : sim.getOwnerLast());
				variables.put("sim.ownerEmail", sim.getOwnerEmail() == null ? "" : sim.getOwnerEmail());
				variables.put("sim.ownerUuid", sim.getOwnerUuid() == null ? "" : sim.getOwnerUuid());
				var regions = sim.getRegions();
				if (regions != null && regions.length > 0 && regions[0] != null) {
					var region = regions[0];
					variables.put("region.name", region.getName() == null ? "" : region.getName());
					variables.put("region.x", String.valueOf(region.getX()));
					variables.put("region.y", String.valueOf(region.getY()));
					variables.put("region.uuid", String.valueOf(region.getUuid()));
					variables.put("region.port", String.valueOf(region.getPort()));
					variables.put("region.height", String.valueOf(region.getHeight()));
					variables.put("region.width", String.valueOf(region.getWidth()));
				}
			});
		}

		var addOn = addOnRepository.load(addOnInstance.getName())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Simulator already exists."));

		// Resolve add-on constants against cfg/grid/env values and previously-resolved
		// constants.
		var unresolvedConstants = new LinkedHashMap<String, String>();
		addOn.getConstants().forEach((key, value) -> {
			if (System.getenv(key) == null) {
				unresolvedConstants.put("env." + key, value == null ? "" : value);
			}
		});

		var maxPasses = Math.max(1, unresolvedConstants.size());
		for (int pass = 0; pass < maxPasses; pass++) {
			var changed = false;
			for (var entry : unresolvedConstants.entrySet()) {
				var resolved = resolve(entry.getValue(), variables);
				var previous = variables.put(entry.getKey(), resolved);
				if (!resolved.equals(previous)) {
					changed = true;
				}
			}
			if (!changed) {
				break;
			}
		}

		return variables;
	}

	private java.util.Optional<uk.co.bithatch.opensim.spawner.domain.SimulatorInstanceData> findGridServiceSimulator() {
		return simulatorStateRepository.list().stream()
				.filter(sim -> sim.getLevel() == SimulatorLevel.ROBUST || sim.getLevel() == SimulatorLevel.STANDALONE)
				.findFirst();
	}

	@Override
	protected ResolvedAddOnPlan createPlan(AddOnInstanceData addOn, List<ContainerSpec> containers) {
		return new ResolvedAddOnPlan(addOn.getLevel(), containers);
	}

	@Override
	protected JsonNode getLevelNode(AddOnLevel level, String name) {
		return addOnRepository.loadRaw(name)
				.orElseThrow(
						() -> new IllegalStateException("Add-on level " + level.name() + " not found in " + name + "."))
				.get("extensions").get(level.name());
	}
}
