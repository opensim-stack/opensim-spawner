package uk.co.bithatch.opensim.spawner.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.BotInstanceData;
import uk.co.bithatch.opensim.spawner.domain.BotLevel;
import uk.co.bithatch.opensim.spawner.domain.ContainerSpec;
import uk.co.bithatch.opensim.spawner.domain.ResolvedBotPlan;

@Service
public class BotLevelProfileService {

    private final ObjectMapper objectMapper;
    private final SpawnerProperties properties;
    private final TemplateResolver templateResolver;

    public BotLevelProfileService(ObjectMapper objectMapper, SpawnerProperties properties, TemplateResolver templateResolver) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.templateResolver = templateResolver;
    }

    public ResolvedBotPlan resolvePlan(BotInstanceData bot, Map<String, String> requestFields) {
        var levelNode = getLevelNode(bot.getLevel());


        var variables = buildBaseVariables(bot);
        var containers = parseContainers(levelNode, variables, requestFields);
        return new ResolvedBotPlan(bot.getLevel(), containers);
    }

    public String resolveLevelField(BotLevel level, String fieldName) {
        var levelNode = getLevelNode(level);
        var fieldNode = levelNode.get(fieldName);
        if (fieldNode == null || fieldNode.isNull()) {
            return null;
        }
        var value = fieldNode.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private JsonNode loadProfilesRoot() {
        var configPath = properties.getConfigDir().resolve("bot-levels.json");
        String json;
        try {
            if (Files.exists(configPath)) {
                json = Files.readString(configPath, StandardCharsets.UTF_8);
            } else {
                var resource = new ClassPathResource("default-bot-levels.json");
                try (var input = resource.getInputStream()) {
                    json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return objectMapper.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load bot level profiles.", e);
        }
    }

    private JsonNode getLevelNode(BotLevel level) {
        var root = loadProfilesRoot();
        var levelNode = root.get(level.name());
        if (levelNode == null || !levelNode.isObject()) {
            throw new IllegalArgumentException("No bot level profile found for " + level.name() + ".");
        }
        return levelNode;
    }

    private List<ContainerSpec> parseContainers(JsonNode levelNode, Map<String, String> variables, Map<String, String> requestFields) {
        var containersNode = levelNode.get("containers");
        if (containersNode == null || !containersNode.isObject()) {
            throw new IllegalArgumentException("Bot level profile must contain an object field named 'containers'.");
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
            var spec = new ContainerSpec();
            spec.setImage(image);
            spec.setName(resolve(requiredText(containerNode, "name", "container.name"), variables));

            var env = resolveMap(containerNode.get("environment"), variables);
            var toRemove = new ArrayList<String>();
            for (var envEntry : env.entrySet()) {
                var overrideValue = requestFields.get(envEntry.getKey());
                if (overrideValue != null) {
                    envEntry.setValue(overrideValue);
                }
                
                var val = envEntry.getValue();
            	if(val.startsWith("%env.") && val.endsWith("%")) {
            		var envVarName = val.substring(5, val.length() - 1);
            		var envVarValue = System.getenv(envVarName);
            		if(envVarValue == null) {
            			toRemove.add(envEntry.getKey());
            		}
            	}
            }
            
            for(var key : toRemove) {
				env.remove(key);
			}
            
            spec.setEnvironment(env);
            spec.setVolumes(resolveMap(containerNode.get("volumes"), variables));
            spec.setFiles(resolveMap(containerNode.get("files"), variables));
            result.add(spec);
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

    private Map<String, String> buildBaseVariables(BotInstanceData bot) {
        var variables = new LinkedHashMap<String, String>();
        variables.put("bot.first", bot.getFirst());
        variables.put("bot.last", bot.getLast());
        variables.put("bot.password", bot.getPassword());
        variables.put("bot.token", bot.getToken());
        variables.put("bot.parent", bot.getParent() == null ? "" : bot.getParent());
        variables.put("bot.level", bot.getLevel() == null ? "" : bot.getLevel().name());
        variables.put("env.OPENSIM_OPENCODE_IMAGE", properties.getOpencodeImage());
        variables.put("env.OPENSIM_METAVERSE2MCP_IMAGE", properties.getMetaverse2mcpImage());
        variables.put("env.OPENSIM_BOT_HANDLER_FIRSTNAME", properties.getOpenCodeHandlerFirstname());
        variables.put("env.OPENSIM_BOT_HANDLER_LASTNAME", properties.getOpenCodeHandlerLastname());
        for (var envEntry : System.getenv().entrySet()) {
            variables.put("env." + envEntry.getKey(), envEntry.getValue());
        }
        return variables;
    }

    private String resolve(String value, Map<String, String> variables) {
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
