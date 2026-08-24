package uk.co.bithatch.opensim.spawner.service;

import static uk.co.bithatch.opensim.spawner.state.BotStateRepository.key;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.annotation.PreDestroy;
import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.BotInstanceData;
import uk.co.bithatch.opensim.spawner.domain.BotLevel;
import uk.co.bithatch.opensim.spawner.domain.Gender;
import uk.co.bithatch.opensim.spawner.domain.ResolvedBotPlan;
import uk.co.bithatch.opensim.spawner.state.BotStateRepository;

@Service
public class BotProvisioningService extends AbstractContainerGroupProvisioningService<BotStateRepository, BotInstanceData> {
	
	private static final int RETRY_COUNT = 10;
	private static final int RETRY_INTERVAL_MS = 5000;

    private static final Logger LOG = LoggerFactory.getLogger(BotProvisioningService.class);

    private final BotLevelProfileService profileService;
    private final OpenSimService openSimService;
    private final RandomPasswordService passwordService;
    private final Appearances appearances;
    private final SimulatorProvisioningService simulatorProvisioningService;

    @Autowired
    public BotProvisioningService(BotStateRepository stateRepository,
            BotLevelProfileService profileService,
            OpenSimService openSimService,
            DockerService dockerService,
            RandomPasswordService passwordService,
            TemplateResolver templateResolver,
            SpawnerProperties properties,
            Appearances appearances,
            SimulatorProvisioningService simulatorProvisioningService) {
    	super(stateRepository, dockerService, templateResolver, properties);
        this.profileService = profileService;
        this.openSimService = openSimService;
        this.passwordService = passwordService;
        this.appearances = appearances;
        this.simulatorProvisioningService = simulatorProvisioningService;
        

        if(properties.isOpensimCreateBotUser()) {
      if (!hasGridLoginService()) {
        LOG.warn("Skipping auto-create login bot because no active ROBUST/STANDALONE simulator is available.");
      }
      else {
        	if(exists(key(properties.getOpensimLoginFirstname(), properties.getOpensimLoginLastname()))) {
				LOG.info("Bot {} {} already exists. Skipping creation.", properties.getOpensimLoginFirstname(), properties.getOpensimLoginLastname());
			}
        	else {
        		BotInstanceData bot = null;
				LOG.info("Spawner is configured to create OpenSimulator users for bots.");
				for(int i = 0 ; i < RETRY_COUNT; i++) {
					LOG.info("Attempting to create bot {} {} (attempt {}/{})", properties.getOpensimLoginFirstname(), properties.getOpensimLoginLastname(), i+1, RETRY_COUNT);
					try {
			            bot = createBot(
		            		properties.getOpensimLoginFirstname(), 
		            		properties.getOpensimLoginLastname(),
		            		"GOVERNOR", 
		            		Map.of(
		        			  "email", properties.getOpensimLoginEmail(), 
		                      "model", properties.getOpensimLoginModel(),
		                      "appearance", System.getenv().getOrDefault("BOT_APPEARANCE", "Cube Bot"),
		                      "gender", System.getenv().getOrDefault("BOT_GENDER", "neutral")
		        			)
		            	);
			            LOG.info("Created bot {} - {} {} successfully.", bot.getUuid(), bot.getFirst(), bot.getLast());
			            break;
			        } catch (IllegalArgumentException | ResponseStatusException e) {
			        	if(e.getMessage().contains("503 SERVICE_UNAVAILABLE") || e.getMessage().contains("Failed to parse XML: empty response body.")) {
			        		try {
								Thread.sleep(RETRY_INTERVAL_MS);
							} catch (InterruptedException e1) {
								throw new RuntimeException("Interrupted while waiting to retry bot creation.", e1);
							}	
			        	}
			        	else {
				            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
			        	}
			        	
			        }
				}
						
				if(bot == null) {
					throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Failed to create bot after " + RETRY_COUNT + " attempts.");
				}
        	}
      }
		} else {
			LOG.info("Spawner is NOT configured to create OpenSimulator users for bots.");
		}

        reconnectKnownBotsOnStartup();
        
    }

    BotProvisioningService(BotStateRepository stateRepository,
            BotLevelProfileService profileService,
            OpenSimService openSimService,
            DockerService dockerService,
            RandomPasswordService passwordService,
            TemplateResolver templateResolver,
            SpawnerProperties properties,
            Appearances appearances) {
        this(stateRepository,
                profileService,
                openSimService,
                dockerService,
                passwordService,
                templateResolver,
                properties,
                appearances,
                null);
    }

    private void reconnectKnownBotsOnStartup() {
        final List<BotInstanceData> knownBots;
        try {
            knownBots = stateRepository.list();
        } catch (RuntimeException e) {
            LOG.warn("Failed to load persisted bot state during startup reconnect.", e);
            return;
        }

        if (knownBots.isEmpty()) {
            LOG.info("No known bots found in persisted state during startup reconnect.");
            return;
        }

        for (var bot : knownBots) {
            var containerIds = bot.getContainerIds() == null ? List.<String>of() : bot.getContainerIds();
            if (containerIds.isEmpty()) {
                LOG.info("Skipping startup reconnect for bot {} {} because no tracked containers are present.",
                        bot.getFirst(),
                        bot.getLast());
                continue;
            }

            try {
                var statuses = dockerService.getContainerStatuses(containerIds);
                var running = statuses.stream().filter(ContainerStatus::running).map(ContainerStatus::containerId).toList();
                var notRunning = statuses.stream().filter(status -> !status.running()).map(ContainerStatus::containerId).toList();

                if (!running.isEmpty()) {
                    dockerService.attachContainerLogs(running);
                    LOG.info("Reattached logs for {} already-running container(s) for bot {} {}.",
                            running.size(),
                            bot.getFirst(),
                            bot.getLast());
                }

                if (!notRunning.isEmpty() && hasGridLoginService()) {
                    dockerService.startContainers(notRunning);
                    LOG.info("Started {} non-running container(s) for bot {} {} during startup reconnect.",
                            notRunning.size(),
                            bot.getFirst(),
                            bot.getLast());
                } else if (!notRunning.isEmpty()) {
                    LOG.warn("Skipping startup reconnect start for bot {} {} because grid login service is unavailable.",
                            bot.getFirst(),
                            bot.getLast());
                }
            } catch (RuntimeException e) {
                LOG.warn("Startup reconnect failed for bot {} {}.", bot.getFirst(), bot.getLast(), e);
            }
        }
    }

    public synchronized BotInstanceData createBot(String first, String last, String levelName, Map<String, String> requestFields) {
        ensureGridLoginServiceAvailable("create bots");
        if (stateRepository.exists(key(first, last))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bot already exists.");
        }
        
        if(stateRepository.list().size() >= properties.getOpensimMaxBots()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Maximum number of bots reached.");
		}

        var createRequestFields = requestFields == null ? Map.<String, String>of() : requestFields;
        var level = BotLevel.fromNullable(levelName);
        var parent = defaultValue(createRequestFields.get("parent"), "");
        validateParentCanCreate(parent, level);
        var password = passwordService.nextPassword();
        var email = defaultEmail(first, last, createRequestFields.get("email"));
        var model = defaultValue(createRequestFields.get("model"), "Ruth");
        var appearance = resolveRequestedAppearance(level, createRequestFields);
        var gender = resolveRequestedGender(level, createRequestFields);
        var uuid = UUID.randomUUID().toString();
        var token = UUID.randomUUID().toString();
        LOG.info("Creating bot {} {} (level={}, parent='{}', email={}, uuid={}, model={}, appearance={}, gender={}).",
                first,
                last,
                level.name(),
                parent,
                email,
                uuid,
                model,
                appearance,
                gender == null ? null : gender.name().toLowerCase(Locale.ROOT));

        try {
            openSimService.createUser(first, last, password, email, uuid, model);
        } catch (ExternalDependencyException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OpenSimulator user creation failed: " + e.getMessage());
        }

        var bot = new BotInstanceData();
        bot.setFirst(first);
        bot.setLast(last);
        bot.setLevel(level);
        bot.setPassword(password);
        bot.setToken(token);
        bot.setParent(parent);
        bot.setEmail(email);
        bot.setUuid(uuid);
        bot.setModel(model);

        var materializedFiles = new ArrayList<java.nio.file.Path>();
        var createdContainerIds = new ArrayList<String>();
        var containerRequestFields = new LinkedHashMap<>(createRequestFields);
        containerRequestFields.remove("parent");
        containerRequestFields.remove("email");
        containerRequestFields.remove("model");
        containerRequestFields.remove("appearance");
        containerRequestFields.remove("gender");
        try {
            var appearanceArchiveResource = appearances.getInventoryArchive(appearance, gender);
            if (appearanceArchiveResource == null) {
                throw new IllegalArgumentException("No appearance archive found for appearance '"
                        + appearance
                        + "' and gender '"
                        + (gender == null ? "" : gender.name().toLowerCase(Locale.ROOT))
                        + "'.");
            }
            var workspaceArchivePath = copyArchiveToWorkspace(appearanceArchiveResource, materializedFiles);
            openSimService.loadInventoryArchive(first, last, "/", password, workspaceArchivePath.toString());
            containerRequestFields.put("WEAR_FOLDER_NAME", extractOutfitNameFromArchivePath(workspaceArchivePath.toString()));

            stateRepository.save(bot);
            var plan = profileService.resolvePlan(bot, containerRequestFields);
            LOG.info("Resolved {} container spec(s) for bot {} {}.", plan.containers().size(), first, last);
            materializeFiles(plan, bot, materializedFiles);

            createdContainerIds.addAll(dockerService.createContainers(plan.containers()));
            LOG.info("Created {} container(s) for bot {} {}.", createdContainerIds.size(), first, last);
            bot.setContainerIds(createdContainerIds);
            stateRepository.save(bot);

            dockerService.startContainers(createdContainerIds);
            LOG.info("Started {} container(s) for bot {} {}.", createdContainerIds.size(), first, last);
            waitForStartupWindow(createdContainerIds, Duration.ofMinutes(1), Duration.ofSeconds(2));
            LOG.info("Bot {} {} provisioned successfully.", first, last);
            return bot;
        } catch (RuntimeException e) {
            LOG.error("Provisioning failed for bot {} {}. Starting rollback.", first, last, e);
            rollbackFailedProvision(key(first, last), createdContainerIds, materializedFiles);
            throw e;
        }
    }
    
    private String extractOutfitNameFromArchivePath(String archivePath) {
		var fileName = java.nio.file.Path.of(archivePath).getFileName();
		if (fileName == null) {
			throw new IllegalArgumentException("Appearance archive path '" + archivePath + "' does not contain a file name.");
		}
		var name = fileName.toString();
		if (!name.endsWith(".iar")) {
			throw new IllegalArgumentException("Appearance archive path '" + archivePath + "' does not have a .iar extension.");
		}
		return name.substring(0, name.length() - 4).replaceAll("-", " ");
	}

    private void materializeFiles(ResolvedBotPlan plan, BotInstanceData bot, List<java.nio.file.Path> writtenFiles) {
    	materializeFiles(plan, writtenFiles, profileService.buildBaseVariables(bot));
    }
    
    private String resolveRequestedAppearance(BotLevel level, Map<String, String> requestFields) {
        var requested = nonBlankOrNull(requestFields.get("appearance"));
        if (requested != null) {
            return requested;
        }

        var profileDefault = nonBlankOrNull(profileService.resolveLevelField(level, "appearance"));
        if (profileDefault != null) {
            return profileDefault;
        }

        var configuredAppearances = appearances.listAppearanceNames();
        if (configuredAppearances.isEmpty()) {
            throw new IllegalStateException("No appearances are configured in appearances.properties.");
        }
        return configuredAppearances.get(ThreadLocalRandom.current().nextInt(configuredAppearances.size()));
    }

    private Gender resolveRequestedGender(BotLevel level, Map<String, String> requestFields) {
        var requested = nonBlankOrNull(requestFields.get("gender"));
        if (requested != null) {
            return parseGender(requested);
        }

        var profileDefault = nonBlankOrNull(profileService.resolveLevelField(level, "gender"));
        if (profileDefault != null) {
            return parseGender(profileDefault);
        }

        var values = Gender.values();
        return values[ThreadLocalRandom.current().nextInt(values.length)];
    }

    private static Gender parseGender(String value) {
        try {
            return Gender.fromNullable(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid gender '" + value + "'. Supported: male, female, neutral.", e);
        }
    }

  @Override
  public synchronized void start(String name) {
    ensureGridLoginServiceAvailable("start bots");
    super.start(name);
  }

  @Override
  public synchronized void stop(String name) {
    ensureGridLoginServiceAvailable("change bot status");
    super.stop(name);
  }

  @Override
  public synchronized void restart(String name) {
    ensureGridLoginServiceAvailable("restart bots");
    super.restart(name);
  }

  private boolean hasGridLoginService() {
    if (simulatorProvisioningService == null) {
      return true;
    }
    return simulatorProvisioningService.hasActiveGridLoginService();
  }

  private void ensureGridLoginServiceAvailable(String action) {
    if (hasGridLoginService()) {
      return;
    }
    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "Cannot " + action + " because no active ROBUST/STANDALONE simulator is providing grid login services.");
  }

    @Override
    protected synchronized void onDeleteContainerGroup(String name) {
        LOG.info("Deleting bot {}.", name);
    	var arr = name.split("-", 2);
        try {
            openSimService.deleteUser(arr[0], arr[1]);
        } catch (RuntimeException ignored) {
            // OpenSimulator user delete is currently unsupported and intentionally ignored.
            LOG.warn("OpenSim delete user skipped for bot {} {} (currently unsupported).", arr[0], arr[1]);
        }
    }

    @Override
    protected void onRollbackFailedProvision(String name, List<String> containerIds, List<java.nio.file.Path> files) {
    	var arr = name.split("-", 2);
    	String first = arr[0];
    	String last = arr[1];
        try {
            openSimService.deleteUser(first, last);
        } catch (RuntimeException ignored) {
            // OpenSimulator user delete is currently unsupported and intentionally ignored.
            LOG.warn("Rollback skipped OpenSim user delete for bot {} {}.", first, last);
        }
    }

    private void validateParentCanCreate(String parent, BotLevel requestedLevel) {
        if (parent.isBlank()) {
            return;
        }

        var parentBot = stateRepository.list().stream()
                .filter(existing -> parent.equals(existing.displayName()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Parent bot not found: " + parent + "."));

        if (parentBot.getLevel() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Parent bot has no level and cannot create children: " + parent + ".");
        }

        if (requestedLevel.ordinal() <= parentBot.getLevel().ordinal()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Parent bot level " + parentBot.getLevel().name()
                            + " cannot create level " + requestedLevel.name() + ".");
        }
    }

    @PreDestroy
    public void stopManagedContainersOnShutdown() {
        LOG.info("Spawner shutdown detected. Attempting best-effort stop of managed containers.");

        final List<String> containerIds;
        try {
            var orderedIds = new LinkedHashSet<String>();
            for (var bot : stateRepository.list()) {
                orderedIds.addAll(bot.getContainerIds());
            }
            containerIds = List.copyOf(orderedIds);
        } catch (RuntimeException e) {
            LOG.warn("Failed to load persisted bot state during shutdown stop hook.", e);
            return;
        }

        if (containerIds.isEmpty()) {
            LOG.info("No managed containers found in persisted state during shutdown.");
            return;
        }

        var stopped = 0;
        var failed = 0;
        for (var containerId : containerIds) {
            try {
                dockerService.stopContainers(List.of(containerId));
                stopped++;
            } catch (RuntimeException e) {
                failed++;
                LOG.warn("Best-effort shutdown stop failed for container {}.", containerId, e);
            }
        }

        LOG.info("Shutdown stop attempt finished for managed containers. attempted={}, stopped={}, failed={}",
                containerIds.size(),
                stopped,
                failed);
    }

	@Override
	protected Map<String, Object> toResponse(BotInstanceData bot) {
		 var children = stateRepository.list().stream()
	                .filter(candidate -> bot.displayName().equals(candidate.getParent()))
	                .map(BotInstanceData::displayName)
	                .toList();

        var status = new LinkedHashMap<String, Object>();
        status.put("first", bot.getFirst());
        status.put("last", bot.getLast());
        status.put("level", bot.getLevel() == null ? null : bot.getLevel().name());
        status.put("parent", bot.getParent());
        status.put("children", children);
        status.put("email", bot.getEmail());
        status.put("uuid", bot.getUuid());
        status.put("model", bot.getModel());
        return status;
	}
}
