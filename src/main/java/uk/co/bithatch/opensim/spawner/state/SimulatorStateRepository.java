package uk.co.bithatch.opensim.spawner.state;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.SimulatorInstanceData;

@Component
public class SimulatorStateRepository extends AbstractStateRepository<SimulatorInstanceData> {


    @Autowired
    public SimulatorStateRepository(ObjectMapper objectMapper, SpawnerProperties properties) {
        super(objectMapper, properties.getDataDir().resolve("simulators"), SimulatorInstanceData.class);
    }

    SimulatorStateRepository(ObjectMapper objectMapper, Path dataDir) {
    	super(objectMapper, dataDir, SimulatorInstanceData.class);
    }

}
