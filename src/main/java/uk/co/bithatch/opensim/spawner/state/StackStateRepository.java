package uk.co.bithatch.opensim.spawner.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.co.bithatch.opensim.jlib.Maps;
import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.StackState;
import uk.co.bithatch.opensim.spawner.service.RandomPasswordService;
import uk.co.bithatch.opensim.spawner.service.TemplateResolver;

@Component
public class StackStateRepository implements  StateRepository<StackState> {
	
	public static final String SPAWNER_TOKEN = "SPAWNER_TOKEN";
	
	private final ObjectMapper objectMapper;
	private final Path file;
	private final SpawnerProperties properties;
	private final TemplateResolver templateResolver;
	private final RandomPasswordService randomPasswordService;
	
	private StackState state; 

    @Autowired
    public StackStateRepository(
    		ObjectMapper objectMapper, 
    		SpawnerProperties properties,
    		RandomPasswordService randomPasswordService,
    		TemplateResolver templateResolver) {
    	this.randomPasswordService = randomPasswordService;
    	this.properties = properties;
    	this.templateResolver = templateResolver;
    	var dataDir = properties.getDataDir();
		var oldGridsFile = dataDir.resolve("grids");
    	var gridsFile = dataDir.resolve("grids.json");
    	if(Files.exists(oldGridsFile) && !Files.exists(gridsFile)) {
    		try {
				Files.move(oldGridsFile, gridsFile);
			} catch (IOException e) {
				throw new IllegalStateException("Failed to move old grids file to new grids.json file.", e);
			}
		}
		this.file = gridsFile;
    	this.objectMapper = objectMapper;
    	load();
    	
    	if((state.getAdminToken() != null && !state.getAdminToken().isBlank()) && !state.getTokens().containsKey(StackState.ADMIN_TOKEN)) {
    		state.getTokens().put(StackState.ADMIN_TOKEN, state.getAdminToken());
    		state.setAdminToken(null);
    		save();
		}
    	
    	if(!state.getTokens().containsKey(SPAWNER_TOKEN)) {
			state.getTokens().put(SPAWNER_TOKEN, randomPasswordService.nextPassword());
			save();
		}
    }

    public StackStateRepository(ObjectMapper objectMapper, Path file, SpawnerProperties properties, TemplateResolver templateResolver, RandomPasswordService randomPasswordService) {
    	this.objectMapper = objectMapper;
    	this.file = file;
    	this.properties = properties;
    	this.templateResolver = templateResolver;
    	this.randomPasswordService = randomPasswordService;
    	load();
    }
    

	public Map<String, String> resolveEnvironment(Map<String, String> defaultVariables, Map<String, String> requestVariables) {
		var envMap  = new HashMap<String, String>();
		
		/* Default variables are the lowest priority, so they are added first.
		 * They most likely come from the add-on or stack/sim/bot "const" definitions  */
		if(defaultVariables != null) {
			envMap.putAll(defaultVariables);
		}
		
		/* Next, we add the system environment variables, which can override the default variables. */
		envMap.putAll(System.getenv());
		
		/* Then, we add the request variables, which can override both the default and system environment variables. */
		if(requestVariables != null) {
			envMap.putAll(requestVariables);
		}
		
		/* Finally, we add the global variables from the stack state, add any variables not otherwise already present.
		 * These are also resolved again the variables we already know */
		var processedEnvMap = 
				Maps.of(envMap.entrySet().stream().collect(Collectors.toMap(e -> "env." + e.getKey(), Map.Entry::getValue)),
						properties.buildVariables()
						);
		envMap.putAll(state.getGlobal().entrySet().stream().
				filter(e -> !envMap.containsKey(e.getKey())).
				collect(Collectors.toMap(e -> e.getKey(), v -> templateResolver.resolve(v.getValue(), processedEnvMap))));
		return envMap;	
	}

	@Override
	public Optional<StackState> load(String name) {
		if(state.getName().equals(name)) {
			return Optional.of(state);
		}
		return Optional.empty();
	}

	@Override
	public void delete(String name) {
		throw new UnsupportedOperationException("Delete operation is not supported for StackStateRepository.");
	}

	@Override
	public Collection<StackState> list() {
		return List.of(state);
	}

	public synchronized StackState get() {
		return state;
	}
    
    private void load() {
		if (Files.exists(file)) {
			try {
				state = objectMapper.readValue(file.toFile(), StackState.class);
				backfillMissingDefaults();
			} catch (IOException e) {
				throw new IllegalStateException("Failed to load grid state from " + file + ".", e);
			}
		}
		else {
			state = new StackState();
			state.getTokens().put(StackState.ADMIN_TOKEN, randomPasswordService.nextPassword());
			state.setName(properties.getOpensimGridName());
			state.setNick(properties.getOpensimGridNick());
			state.setWelcomeMessage(properties.getOpensimWelcomeMessage());
			if (!isGuidedMode()) {
				state.setConsolePass(properties.getOpensimConsolePass());
				state.setConsoleUser(properties.getOpensimConsoleUser());
			}
			save();
		}
    }

	private void backfillMissingDefaults() {
		var changed = false;

		if (!isGuidedMode()) {
			if (isBlank(state.getConsoleUser()) && !isBlank(properties.getOpensimConsoleUser())) {
				state.setConsoleUser(properties.getOpensimConsoleUser().trim());
				changed = true;
			}
			if (isBlank(state.getConsolePass()) && !isBlank(properties.getOpensimConsolePass())) {
				state.setConsolePass(properties.getOpensimConsolePass().trim());
				changed = true;
			}
		}
		if (changed) {
			save();
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	private boolean isGuidedMode() {
		return "guided".equalsIgnoreCase(properties.getOpensimProvisionMode());
	}
    
    public synchronized void save() {
		try {
			Files.createDirectories(file.getParent());
			objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), state);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to save grid state to " + file + ".", e);
		}
    }
}
