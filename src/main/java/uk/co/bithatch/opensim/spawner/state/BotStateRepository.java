package uk.co.bithatch.opensim.spawner.state;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.BotInstanceData;

@Component
public class BotStateRepository extends AbstractStateRepository<BotInstanceData> {
	
	public static String key(String first, String last) {
		return first + "-" + last;
	}

    @Autowired
    public BotStateRepository(ObjectMapper objectMapper, SpawnerProperties properties) {
        super(objectMapper, properties.getDataDir(), BotInstanceData.class);
    }

    BotStateRepository(ObjectMapper objectMapper, Path dataDir) {
    	super(objectMapper, dataDir, BotInstanceData.class);
    }

}
