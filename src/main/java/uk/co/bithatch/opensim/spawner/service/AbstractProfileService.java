package uk.co.bithatch.opensim.spawner.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.Component;
import uk.co.bithatch.opensim.spawner.domain.ContainerGroupInstanceData;
import uk.co.bithatch.opensim.spawner.domain.ContainerSpec;
import uk.co.bithatch.opensim.spawner.domain.ManagedFile;
import uk.co.bithatch.opensim.spawner.state.StackStateRepository;

public abstract class AbstractProfileService<COM extends Component<LVL>, T extends ContainerGroupInstanceData<LVL>, P, LVL extends Enum<LVL>> {
	private static final Logger LOG = LoggerFactory.getLogger(AbstractProfileService.class);

    private final TemplateResolver templateResolver;

    protected final ObjectMapper objectMapper;
    protected final SpawnerProperties properties;
	protected final StackStateRepository gridStateRepository;

    public AbstractProfileService(
    		ObjectMapper objectMapper, 
    		SpawnerProperties properties, 
    		TemplateResolver templateResolver,
    		StackStateRepository gridStateRepository) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.templateResolver = templateResolver;
        this.gridStateRepository = gridStateRepository;
    }
    
    protected abstract Class<COM> getComponentClass();

    public final P resolvePlan(T bot, Map<String, String> requestFields) {
        var baseVariables = Collections.unmodifiableMap(buildBaseVariables(bot,  new LinkedHashMap<String, String>(), requestFields));
		return createPlan(
			bot, 
			parseContainers(getLevelNode(bot.getLevel(),bot.getName()), baseVariables),
			baseVariables
		);
    }
    
	protected abstract P createPlan(T bot, List<ContainerSpec> containers, Map<String, String> baseVariables);

    protected abstract Map<String, Object> getLevelNode(LVL level, String name);

    @SuppressWarnings("unchecked")
	private List<ContainerSpec> parseContainers(Map<String, Object> levelNode, Map<String, String> variables) {
        var containersNode = levelNode.get("containers");
        if(containersNode instanceof Map map) {
            var result = new ArrayList<ContainerSpec>();
            var iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = (Map.Entry<String, Object>)iterator.next();
                var image = resolve(entry.getKey(), variables);
                var containerNode = entry.getValue();
                if (containerNode instanceof Map) {
                    result.add(parseContainerSpec(image, (Map<String, Object>) containerNode, variables, false, null));
                }
                else {
                    throw new IllegalArgumentException("Container definition for image " + image + " must be an object.");
                }
            }
            return result;
		}
        else {
            throw new IllegalArgumentException("Profile must contain an object field named 'containers'.");
        }
        

    }

    @SuppressWarnings("unchecked")
	private ContainerSpec parseContainerSpec(String image,
            Map<String, Object> containerNode,
            Map<String, String> variables,
            boolean nestedInit,
            String defaultName) {
    	
    	if(image == null || image.isBlank()) {
			throw new IllegalArgumentException("Container spec must have a non-empty image.");
		}

    	if(LOG.isDebugEnabled()) {
			LOG.debug("Parsing container spec for image {} with variables {}", image, variables);
		}
		else {
			LOG.info("Parsing container spec for image {}.", image);
		}
    	
        var spec = new ContainerSpec();
        spec.setImage(image);

        var nameNode = containerNode.get("name");
        if (nameNode != null && nameNode instanceof String && !nameNode.toString().isBlank()) {
            spec.setName(resolve(nameNode.toString(), variables));
        } else if (defaultName != null && !defaultName.isBlank()) {
            spec.setName(defaultName);
        } else {
            spec.setName(resolve(requiredText(containerNode, "name", "container.name"), variables));
        }

        spec.setEnvironment(resolveMap(asMap(containerNode.get("environment")), variables));
        spec.setExtraHosts(resolveMap(asMap(containerNode.get("extraHosts")), variables));
        spec.setAliases(resolveList(asList(containerNode.get("aliases")), variables));
        spec.setDirectories(resolveList(asList(containerNode.get("directories")), variables));
        spec.setManagedFiles(resolveListOfObjects(asList(containerNode.get("managed")), (map) -> {
        	return new ManagedFile(
        			resolve(requiredText(map, "resource", "container.managed.resource"), variables),
        			resolve((String)map.get("target"), variables),
        			resolve(requiredText(map, "dropIns", "container.managed.dropIns"), variables));
        }));

        var hostnameNode = (String)containerNode.get("hostname");
        if (hostnameNode != null && !hostnameNode.isBlank()) {
            spec.setHostname(resolve(hostnameNode, variables));
        }

        spec.setVolumes(resolveMap(asMap(containerNode.get("volumes")), variables));
        spec.setEntrypoint(resolveList(asList(containerNode.get("entrypoint")), variables));
        spec.setCommand(resolveList(asList(containerNode.get("command")), variables));
        spec.setFiles(resolveMap(asMap(containerNode.get("files")), variables));
        spec.setPorts(resolveMap(asMap(containerNode.get("ports")), variables));
        spec.setHealthCheck(resolveObject(asMap(containerNode.get("healthcheck")), (map) -> {
			return new ContainerSpec.HealthCheck(
					resolveList(asList(map.get("test")), variables),
					parseDurationSec(resolve(String.valueOf(map.getOrDefault("interval", "10")), variables)),
					parseDurationSec(resolve(String.valueOf(map.getOrDefault("timeout", "5")), variables)),
					parseDurationSec(resolve(String.valueOf(map.getOrDefault("startPeriod", "0")), variables)),
					parseDurationSec(resolve(String.valueOf(map.getOrDefault("startInterval", "0")), variables)),
					Integer.parseInt(resolve(String.valueOf(map.getOrDefault("retries", "5")), variables))
					);
		}));

        var initNode = containerNode.get("init");
        if (initNode != null) {
            if (nestedInit) {
                throw new IllegalArgumentException("Nested init containers are not supported.");
            }
            if (initNode instanceof Map initMap) {
            	var initSpecs = new LinkedHashMap<String, ContainerSpec>();
                var initIterator = ((Map<String, Object>)initMap).keySet().iterator();
                while (initIterator.hasNext()) {
                    var initKey = initIterator.next();
                    var initImage = resolve(initKey, variables);
                    var initContainerNode = initMap.get(initKey);
                    if (initContainerNode instanceof Map initContainerMap) {

                        var initSpec = parseContainerSpec(initImage,
                        		(Map<String, Object>)initContainerMap,
                                variables,
                                true,
                                spec.getName() + "-init");
                        initSpecs.put(initImage, initSpec);
                    }
                    else {
                        throw new IllegalArgumentException("Init container definition for image " + initImage + " must be an object.");
                    }

                }
                spec.setInit(initSpecs);
            }
            else {
                throw new IllegalArgumentException("Container init definition must be an object.");
            }

            
        }

        return spec;
    }
    
    private long parseDurationSec(String value) {
		try {
			long dur = 0;
			for(var part : value.split(",")) {
				part = part.trim();
				if(part.endsWith("s")) {
					dur += Long.parseLong(part.substring(0, part.length()-1));
				}
				else if(part.endsWith("m")) {
					dur += Long.parseLong(part.substring(0, part.length()-1)) * 60;
				}
				else if(part.endsWith("h")) {
					dur += Long.parseLong(part.substring(0, part.length()-1)) * 3600;
				}
				else {
					dur += Long.parseLong(part);
				}
			}
			return dur;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Expected a numeric value for duration but got: " + value);
		}
	}

    @SuppressWarnings("unchecked")
	private <LT> LT resolveObject(Object node, Function<Map<String, Object>, LT> mapper) {

		if (!(node instanceof Map)) {
			throw new IllegalArgumentException("Expected object in array while resolving profile list.");
		}
		return mapper.apply((Map<String, Object>) node);
		
    }

    @SuppressWarnings("unchecked")
	private <LT> List<LT> resolveListOfObjects(List<Object> node, Function<Map<String, Object>, LT> mapper) {
    	return node.stream()
				.map(itemNode -> {
					if (!(itemNode instanceof Map)) {
						throw new IllegalArgumentException("Expected object in array while resolving profile list.");
					}
					return mapper.apply((Map<String, Object>) itemNode);
				})
				.collect(Collectors.toList());
    }
    
    @SuppressWarnings("unchecked")
	private List<String> resolveList(List<Object> node, Map<String, String> variables) {
    	var result = new ArrayList<String>();
		if (node == null) {
			return result;
		}
		if (node instanceof List list) {
			return (List<String>) list.stream()
					.map(itemNode -> resolve(String.valueOf(itemNode), variables))
					.collect(Collectors.toList());
		}
		else {
			throw new IllegalArgumentException("Expected array field while resolving profile list.");
		}
    }
    
    @SuppressWarnings("unchecked")
	private Map<String, String> resolveMap(Map<String, Object> node, Map<String, String> variables) {
        var result = new LinkedHashMap<String, String>();
        if (node == null) {
            return result;
        }
        if (node instanceof Map map) {
        	return ((Map<String,Object>)map).entrySet().stream().
        			collect(Collectors.toMap(
							entry -> resolve(entry.getKey(), variables), 
							entry -> resolve(String.valueOf(entry.getValue()), variables)));
        }
        else {
            throw new IllegalArgumentException("Expected object field while resolving profile map.");
        }
    }


    public final Map<String, String> buildBaseVariables(T bot, Map<String, String> variables, Map<String, String> environment) {

		for (var envEntry : environment.entrySet()) {
			variables.put("env." + envEntry.getKey(), envEntry.getValue());
		}
		
    	var grid = gridStateRepository.get();
		variables.putAll(properties.buildVariables());

        variables.put("grid.name", grid.getName());
        variables.put("grid.nick", grid.getNick());
        variables.put("grid.welcomeMessage", grid.getWelcomeMessage());
        variables.put("grid.consoleUser", grid.getConsoleUser());
        variables.put("grid.consolePass", grid.getConsolePass());
		variables.put("grid.updates.tag",
				grid.getUpdates().getTag() == null || grid.getUpdates().getTag().isBlank() 
					? properties.getOpensimTag()
					: grid.getUpdates().getTag());
        
    	grid.getTokens().forEach((key, value) -> variables.put("token." + key, value));    	
    	
    	return buildTypeVariables(bot, variables);
    }
    

    public abstract Map<String, String> buildTypeVariables(T bot, Map<String, String> variables);

    protected String resolve(String value, Map<String, String> variables) {
        return templateResolver.resolve(value, variables);
    }

    private static String requiredText(Map<String, Object> node, String fieldName, String context) {
        var child = node.get(fieldName);
        if (child == null || !(child instanceof String) || child.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required string field '" + fieldName + "' in " + context + ".");
        }
        return child.toString();
    }

    @SuppressWarnings({ "unchecked" })
	private static Map<String, Object> asMap(Object obj) {
		if (obj instanceof Map) {
			return (Map<String, Object>) obj;
		} else {
			return new LinkedHashMap<>();
		}
	}
    
    @SuppressWarnings({ "unchecked" })
	private static List<Object> asList(Object obj) {
		if (obj instanceof List) {
			return (List<Object>) obj;
		} else {
			return new ArrayList<>();
		}
	}
}
