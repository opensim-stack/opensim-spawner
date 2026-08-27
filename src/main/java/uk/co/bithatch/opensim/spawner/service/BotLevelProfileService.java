package uk.co.bithatch.opensim.spawner.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.BotInstanceData;
import uk.co.bithatch.opensim.spawner.domain.BotLevel;
import uk.co.bithatch.opensim.spawner.domain.ContainerSpec;
import uk.co.bithatch.opensim.spawner.domain.ResolvedBotPlan;

@Service
public class BotLevelProfileService extends AbstractProfileService<BotInstanceData, ResolvedBotPlan, BotLevel> {


    public BotLevelProfileService(ObjectMapper objectMapper, SpawnerProperties properties, TemplateResolver templateResolver) {
    	super(objectMapper, properties, templateResolver, "bot-levels.json", "default-bot-levels.json");
    }

	@Override
	public Map<String, String> buildBaseVariables(BotInstanceData bot) {
        var variables = new LinkedHashMap<String, String>();
        variables.put("bot.first", bot.getFirst());
        variables.put("bot.last", bot.getLast());
        variables.put("bot.password", bot.getPassword());
        variables.put("bot.token", bot.getToken());
        variables.put("bot.parent", bot.getParent() == null ? "" : bot.getParent());
        variables.put("bot.level", bot.getLevel() == null ? "" : bot.getLevel().name());
        variables.put("env.OPENSIM_OPENCODE_IMAGE", properties.getOpencodeImage());
        variables.put("env.OPENSIM_METAVERSE2MCP_IMAGE", properties.getMetaverse2mcpImage());
        for (var envEntry : System.getenv().entrySet()) {
            variables.put("env." + envEntry.getKey(), envEntry.getValue());
        }
        return variables;
    }

	@Override
	protected ResolvedBotPlan createPlan(BotInstanceData bot, List<ContainerSpec> containers) {
        return new ResolvedBotPlan(bot.getLevel(), containers);
	}
}
