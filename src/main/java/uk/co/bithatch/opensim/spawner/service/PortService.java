package uk.co.bithatch.opensim.spawner.service;

import java.util.Arrays;
import java.util.HashSet;

import org.springframework.stereotype.Service;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.SimulatorLevel;
import uk.co.bithatch.opensim.spawner.state.SimulatorStateRepository;

@Service
public class PortService {
	
	private final SimulatorStateRepository stateRepository;
	private final SpawnerProperties properties;
	
	public PortService(SimulatorStateRepository stateRepository, SpawnerProperties properties) {
		this.stateRepository = stateRepository;
		this.properties = properties;
	}


	public int nextPort(SimulatorLevel level) {
		if (SimulatorLevel.ROBUST == level) {
			return properties.getOpensimRobustPublicPort();
		}
		else {
			var usedPorts = new HashSet<Integer>();
			stateRepository.list().stream()
				.filter(sim -> level == null || sim.getLevel() == level)
				.forEach(sim -> {
					if (sim.getPort() != 0) {
						usedPorts.add(sim.getPort());
					}
					Arrays.asList(sim.getRegions()).stream()
						.filter(region -> region != null && region.getPort() != 0)
						.forEach(region -> usedPorts.add(region.getPort()));
				});
			if(!usedPorts.contains(properties.getFirstPort())) {
				return properties.getFirstPort();
			}
			else {
				int nextPort = usedPorts.stream().max(Integer::compareTo).get() + 1;
				if(nextPort > properties.getLastPort()) {
					throw new RuntimeException("No available ports in range " + properties.getFirstPort() + "-" + properties.getLastPort());
				}
				return nextPort;
			}
		}
	}
}
