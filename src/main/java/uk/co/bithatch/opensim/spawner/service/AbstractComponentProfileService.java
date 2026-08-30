package uk.co.bithatch.opensim.spawner.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.ContainerGroupInstanceData;

public abstract class AbstractComponentProfileService<T extends ContainerGroupInstanceData<LVL>, P, LVL extends Enum<LVL>> extends AbstractProfileService<T, P, LVL> {


    private static final Logger LOG = LoggerFactory.getLogger(AbstractComponentProfileService.class);

	private final String profileFileName;
	private final String defaultProfileResourceName;

    protected AbstractComponentProfileService(
    		ObjectMapper objectMapper, 
    		SpawnerProperties properties, 
    		TemplateResolver templateResolver,
    		String profileFileName,
    		String defaultProfileResourceName) {
    	super(objectMapper, properties, templateResolver);
    	this.profileFileName = profileFileName;
    	this.defaultProfileResourceName = defaultProfileResourceName;
    }

    public String resolveLevelField(LVL level, String fieldName) {
        var levelNode = getLevelNode(level, null);
        var fieldNode = levelNode.get(fieldName);
        if (fieldNode == null || fieldNode.isNull()) {
            return null;
        }
        var value = fieldNode.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private JsonNode loadProfilesRoot() {
        LOG.info("Load  profiles from {}.", profileFileName);
        var configPath = properties.getConfigDir().resolve(profileFileName);
        String json;
        try {
            if (Files.exists(configPath)) {
                json = Files.readString(configPath, StandardCharsets.UTF_8);
            } else {
                LOG.info("Load default profiles from {}.", defaultProfileResourceName);
                var resource = new ClassPathResource(defaultProfileResourceName);
                try (var input = resource.getInputStream()) {
                    json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return objectMapper.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load level profiles.", e);
        }
    }

    @Override
    protected JsonNode getLevelNode(LVL level, String name) {
        LOG.info("Loaded level {} profiles from {}.", level, name);
        var root = loadProfilesRoot();
        var levelNode = root.get(level.name());
        if (levelNode == null || !levelNode.isObject()) {
            throw new IllegalArgumentException("No level profile found for " + level.name() + ".");
        }
        return levelNode;
    }

    
}
