package uk.co.bithatch.opensim.spawner.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.AddOnInstanceData;
import uk.co.bithatch.opensim.spawner.domain.ContainerLevel;
import uk.co.bithatch.opensim.spawner.domain.ContainerSpec;
import uk.co.bithatch.opensim.spawner.domain.Manifest;
import uk.co.bithatch.opensim.spawner.domain.ResolvedAddOnPlan;
import uk.co.bithatch.opensim.spawner.domain.SimulatorInstanceData;
import uk.co.bithatch.opensim.spawner.domain.SimulatorLevel;
import uk.co.bithatch.opensim.spawner.state.AddOnRepository;
import uk.co.bithatch.opensim.spawner.state.SimulatorStateRepository;
import uk.co.bithatch.opensim.spawner.state.StackStateRepository;

@Service
public class AddOnProfileService extends AbstractProfileService<Manifest, AddOnInstanceData, ResolvedAddOnPlan, ContainerLevel> {

	private final AddOnRepository addOnRepository;
	private final SimulatorStateRepository simulatorStateRepository;

	public AddOnProfileService(ObjectMapper objectMapper, StackStateRepository gridStateRepository,
			SpawnerProperties properties, TemplateResolver templateResolver, AddOnRepository addOnRepository,
			SimulatorStateRepository simulatorStateRepository) {
		super(objectMapper, properties, templateResolver, gridStateRepository);
		this.addOnRepository = addOnRepository;
		this.simulatorStateRepository = simulatorStateRepository;
	}

	@Override
	public Map<String, String> buildTypeVariables(AddOnInstanceData addOnInstance, Map<String, String> variables) {


		if (addOnInstance.getLevel() == ContainerLevel.SIMULATOR) {
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

		return variables;
	}

	private Optional<SimulatorInstanceData> findGridServiceSimulator() {
		return simulatorStateRepository.list().stream()
				.filter(sim -> sim.getLevel() == SimulatorLevel.ROBUST || sim.getLevel() == SimulatorLevel.STANDALONE)
				.findFirst();
	}

	@Override
	protected ResolvedAddOnPlan createPlan(AddOnInstanceData addOn, List<ContainerSpec> containers, Map<String, String> variables) {
		return new ResolvedAddOnPlan(addOn.getLevel(), containers, variables);
	}

	@Override
	protected Map<String, Object> getLevelNode(ContainerLevel level, String name) {
		return addOnRepository.load(name)
				.orElseThrow(
						() -> new IllegalStateException("Add-on level " + level.name() + " not found in " + name + "."))
				.getExtensions().get(level);
	}

	@Override
	protected Class<Manifest> getComponentClass() {
		return Manifest.class;
	}
}
