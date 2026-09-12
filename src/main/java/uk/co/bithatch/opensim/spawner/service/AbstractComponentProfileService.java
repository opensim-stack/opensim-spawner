package uk.co.bithatch.opensim.spawner.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.Component;
import uk.co.bithatch.opensim.spawner.domain.ContainerGroupInstanceData;
import uk.co.bithatch.opensim.spawner.state.StackStateRepository;

public abstract class AbstractComponentProfileService<COM extends Component<LVL>, T extends ContainerGroupInstanceData<LVL>, P, LVL extends Enum<LVL>> extends AbstractProfileService<COM, T, P, LVL> {


    private static final Logger LOG = LoggerFactory.getLogger(AbstractComponentProfileService.class);

	private final String profileFileName;
	private final String defaultProfileResourceName;

    protected AbstractComponentProfileService(
    		ObjectMapper objectMapper, 
    		SpawnerProperties properties, 
    		TemplateResolver templateResolver,
    		String profileFileName,
    		String defaultProfileResourceName,
    		StackStateRepository gridStateRepository) {
    	super(objectMapper, properties, templateResolver, gridStateRepository);
    	this.profileFileName = profileFileName;
    	this.defaultProfileResourceName = defaultProfileResourceName;
    }

    @Override
	public final Map<String, String> buildTypeVariables(T bot, Map<String, String> variables) {
    	var vars = onBuildTypeVariables(bot, variables);
		return vars;
	}

	protected Map<String, String> onBuildTypeVariables(T bot, Map<String, String> variables) {
		return variables;
	}

	public String resolveLevelField(LVL level,  String fieldName) {
        var levelNode = getLevelNode(level, null);
        var fieldNode = levelNode.get(fieldName);
        if (fieldNode == null ) {
            return null;
        }
        return String.valueOf(fieldNode).trim();
    }
    
    public COM component() {
    	return loadComponent();
    }
    
    private COM loadComponent() {
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
            return objectMapper.readValue(json, getComponentClass());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load level profiles.", e);
        }
    }

	@Override
    protected Map<String, Object> getLevelNode(LVL level, @Deprecated String name) {
        LOG.info("Loaded level {} profiles from {}.", level, name);
        var root = loadComponent();
        var levelNode = root.getExtensions().get(level);
        if (levelNode == null || !(levelNode instanceof Map)) {
            throw new IllegalArgumentException("No level profile found for " + level.name() + ".");
        }
        return levelNode;
    }

    
}
