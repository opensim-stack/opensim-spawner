package uk.co.bithatch.opensim.spawner.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.ContainerGroupInstanceData;
import uk.co.bithatch.opensim.spawner.domain.ContainerSpec;
import uk.co.bithatch.opensim.spawner.domain.ManagedFile;
import uk.co.bithatch.opensim.spawner.state.GridStateRepository;

public abstract class AbstractProfileService<T extends ContainerGroupInstanceData<LVL>, P, LVL extends Enum<LVL>> {

    private final TemplateResolver templateResolver;

    protected final ObjectMapper objectMapper;
    protected final SpawnerProperties properties;
	protected final GridStateRepository gridStateRepository;

    public AbstractProfileService(
    		ObjectMapper objectMapper, 
    		SpawnerProperties properties, 
    		TemplateResolver templateResolver,
    		GridStateRepository gridStateRepository) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.templateResolver = templateResolver;
        this.gridStateRepository = gridStateRepository;
    }

    public P resolvePlan(T bot, Map<String, String> requestFields) {
        return createPlan(bot, parseContainers(
	        		getLevelNode(bot.getLevel(), 
	        		bot.getName()), 
        		buildBaseVariables(bot,  new LinkedHashMap<String, String>()), requestFields));
    }
    
	protected abstract P createPlan(T bot, List<ContainerSpec> containers);

    protected abstract JsonNode getLevelNode(LVL level, String name);

    private List<ContainerSpec> parseContainers(JsonNode levelNode, Map<String, String> variables, Map<String, String> requestFields) {
        var containersNode = levelNode.get("containers");
        if (containersNode == null || !containersNode.isObject()) {
            throw new IllegalArgumentException("Profile must contain an object field named 'containers'.");
        }
        var result = new ArrayList<ContainerSpec>();
        var iterator = containersNode.fields();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var image = resolve(entry.getKey(), variables);
            var containerNode = entry.getValue();
            if (!containerNode.isObject()) {
                throw new IllegalArgumentException("Container definition for image " + image + " must be an object.");
            }
            result.add(parseContainerSpec(image, containerNode, variables, requestFields, false, null));
        }
        return result;
    }

    private ContainerSpec parseContainerSpec(String image,
            JsonNode containerNode,
            Map<String, String> variables,
            Map<String, String> requestFields,
            boolean nestedInit,
            String defaultName) {
        var spec = new ContainerSpec();
        spec.setImage(image);

        var nameNode = containerNode.get("name");
        if (nameNode != null && nameNode.isTextual() && !nameNode.asText().isBlank()) {
            spec.setName(resolve(nameNode.asText(), variables));
        } else if (defaultName != null && !defaultName.isBlank()) {
            spec.setName(defaultName);
        } else {
            spec.setName(resolve(requiredText(containerNode, "name", "container.name"), variables));
        }

        spec.setEnvironment(resolveEnvironment(containerNode.get("environment"), variables, requestFields));
        spec.setExtraHosts(resolveMap(containerNode.get("extraHosts"), variables));
        spec.setAliases(resolveList(containerNode.get("aliases"), variables));
        spec.setDirectories(resolveList(containerNode.get("directories"), variables));
        spec.setManagedFiles(resolveListOfObjects(containerNode.get("managed"), variables, ManagedFile.class));

        var hostnameNode = containerNode.get("hostname");
        if (hostnameNode != null && !hostnameNode.isNull() && !hostnameNode.asText().isBlank()) {
            spec.setHostname(resolve(hostnameNode.asText(""), variables));
        }

        spec.setVolumes(resolveMap(containerNode.get("volumes"), variables));
        spec.setFiles(resolveMap(containerNode.get("files"), variables));
        spec.setPorts(resolveMap(containerNode.get("ports"), variables));
        spec.setHealthCheck(resolveHealthCheck(containerNode.get("healthcheck"), variables));

        var initNode = containerNode.get("init");
        if (initNode != null && !initNode.isNull()) {
            if (nestedInit) {
                throw new IllegalArgumentException("Nested init containers are not supported.");
            }
            if (!initNode.isObject()) {
                throw new IllegalArgumentException("Container init definition must be an object.");
            }

            var initSpecs = new LinkedHashMap<String, ContainerSpec>();
            var initIterator = initNode.fields();
            while (initIterator.hasNext()) {
                var initEntry = initIterator.next();
                var initImage = resolve(initEntry.getKey(), variables);
                var initContainerNode = initEntry.getValue();
                if (!initContainerNode.isObject()) {
                    throw new IllegalArgumentException("Init container definition for image " + initImage + " must be an object.");
                }

                var initSpec = parseContainerSpec(initImage,
                        initContainerNode,
                        variables,
                        requestFields,
                        true,
                        spec.getName() + "-init");
                initSpecs.put(initImage, initSpec);
            }
            spec.setInit(initSpecs);
        }

        return spec;
    }

    private Map<String, String> resolveEnvironment(JsonNode environmentNode,
            Map<String, String> variables,
            Map<String, String> requestFields) {
        var env = resolveMap(environmentNode, variables);
        var toRemove = new ArrayList<String>();
        for (var envEntry : env.entrySet()) {
            var overrideValue = requestFields.get(envEntry.getKey());
            if (overrideValue != null) {
                envEntry.setValue(overrideValue);
            }

            var val = envEntry.getValue();
            if (val.startsWith("%env.") && val.endsWith("%")) {
                var envVarName = val.substring(5, val.length() - 1);
                var envVarValue = System.getenv(envVarName);
                if (envVarValue == null) {
                    toRemove.add(envEntry.getKey());
                }
            }
        }

        for (var key : toRemove) {
            env.remove(key);
        }
        return env;
    }

    private ContainerSpec.HealthCheck resolveHealthCheck(JsonNode node, Map<String, String> variables) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("Expected object field while resolving healthcheck.");
        }
        try {
            var resolvedNode = objectMapper.createObjectNode();
            node.fields().forEachRemaining(field -> {
                var key = field.getKey();
                var value = field.getValue();
                if (value.isTextual()) {
                    var text = resolve(value.asText(""), variables);
                    resolvedNode.put(key, text);
                } else {
                    resolvedNode.set(key, value);
                }
            });
            return objectMapper.treeToValue(resolvedNode, ContainerSpec.HealthCheck.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse healthcheck from profile.", e);
        }
    }


    private <LT> List<LT> resolveListOfObjects(JsonNode node, Map<String, String> variables, Class<LT> clazz) {
    	var result = new ArrayList<LT>();
		if (node == null) {
			return result;
		}
		if (!node.isArray()) {
			throw new IllegalArgumentException("Expected array field while resolving profile list.");
		}
		for (var itemNode : node) {
			try {
				result.add(objectMapper.treeToValue(itemNode, clazz));
			} catch (JsonProcessingException e) {
				throw new IllegalArgumentException("Failed to parse object of type " + clazz.getSimpleName() + " from profile list.", e);
			}
		}
		return result;
    	
    }
    
    private List<String> resolveList(JsonNode node, Map<String, String> variables) {
    	var result = new ArrayList<String>();
		if (node == null) {
			return result;
		}
		if (!node.isArray()) {
			throw new IllegalArgumentException("Expected array field while resolving profile list.");
		}
		for (var itemNode : node) {
			result.add(resolve(itemNode.asText(""), variables));
		}
		return result;
    	
    }
    private Map<String, String> resolveMap(JsonNode node, Map<String, String> variables) {
        var result = new LinkedHashMap<String, String>();
        if (node == null) {
            return result;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("Expected object field while resolving profile map.");
        }
        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            result.put(resolve(field.getKey(), variables), resolve(field.getValue().asText(""), variables));
        }
        return result;
    }


    public final Map<String, String> buildBaseVariables(T bot, Map<String, String> variables) {

		for (var envEntry : System.getenv().entrySet()) {
			variables.put("env." + envEntry.getKey(), envEntry.getValue());
		}
		
    	var grid = gridStateRepository.get();

        variables.put("grid.adminToken", grid.getAdminToken());
        variables.put("grid.name", grid.getName());
        variables.put("grid.nick", grid.getNick());
        
    	grid.getTokens().forEach((key, value) -> variables.put("token." + key, value));    	
    	
    	return buildTypeVariables(bot, variables);
    }
    

    public abstract Map<String, String> buildTypeVariables(T bot, Map<String, String> variables);

    protected String resolve(String value, Map<String, String> variables) {
        return templateResolver.resolve(value, variables);
    }

    private static String requiredText(JsonNode node, String fieldName, String context) {
        var child = node.get(fieldName);
        if (child == null || !child.isTextual() || child.asText().isBlank()) {
            throw new IllegalArgumentException("Missing required string field '" + fieldName + "' in " + context + ".");
        }
        return child.asText();
    }
}
