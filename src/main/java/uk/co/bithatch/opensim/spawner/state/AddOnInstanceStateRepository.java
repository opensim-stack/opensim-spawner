package uk.co.bithatch.opensim.spawner.state;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.AddOnInstanceData;

@Component
public class AddOnInstanceStateRepository extends AbstractStateRepository<AddOnInstanceData> {


    @Autowired
    public AddOnInstanceStateRepository(ObjectMapper objectMapper, SpawnerProperties properties) {
        super(objectMapper, properties.getDataDir().resolve("add-ons"), AddOnInstanceData.class);
    }

    AddOnInstanceStateRepository(ObjectMapper objectMapper, Path dataDir) {
    	super(objectMapper, dataDir, AddOnInstanceData.class);
    }

}
