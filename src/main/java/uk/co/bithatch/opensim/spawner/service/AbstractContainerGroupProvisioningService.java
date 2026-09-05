package uk.co.bithatch.opensim.spawner.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.ContainerGroupInstanceData;
import uk.co.bithatch.opensim.spawner.domain.Plan;
import uk.co.bithatch.opensim.spawner.state.StateRepository;

public abstract class AbstractContainerGroupProvisioningService<
	R extends StateRepository<T>,
	T extends ContainerGroupInstanceData<?>> {
	
    private static final Logger LOG = LoggerFactory.getLogger(AbstractContainerGroupProvisioningService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	protected final R stateRepository;
    protected final DockerService dockerService;
    protected final SpawnerProperties properties;
    protected final TemplateResolver templateResolver;

	public AbstractContainerGroupProvisioningService(
			R stateRepository, 
			DockerService dockerService,
			TemplateResolver templateResolver,
			SpawnerProperties properties) {
		this.stateRepository = stateRepository;
		this.dockerService = dockerService;
		this.properties = properties;
		this.templateResolver = templateResolver;
	}

    public boolean exists(String name) {
        return stateRepository.exists(name);
    }

    public final List<String> listNames() {
        return stateRepository.list().stream().map(T::displayName).toList();
    }

    public Map<String, Object> getContainerGroupStatus(String name) {
        LOG.info("Fetching status for {} {}.", name);
        var cntr = stateRepository.load(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bot not found."));

        var status = toResponse(cntr);
        try {
            status.put("containerStatus", dockerService.getContainerStatuses(cntr.getContainerIds()));
        } catch (RuntimeException e) {
            LOG.error("Failed to fetch container status for container group {}.", name, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Failed to query Docker container group status: " + e.getMessage(), e);
        }
        return status;
    } 
    
    public final synchronized void deleteContainerGroup(String name) {
        LOG.info("Deleting container group {}.", name);
        var bot = stateRepository.load(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Container group not found."));

        try {
            dockerService.removeContainers(bot.getContainerIds());
            var botVolumeSuffix = "-" + name;
            dockerService.removeVolumesBySuffix(botVolumeSuffix);
            LOG.info("Removed {} container(s) and named volumes with suffix '{}' for container group {}.",
                    bot.getContainerIds().size(),
                    botVolumeSuffix,
                    name);
        } catch (RuntimeException e) {
            LOG.error("Failed deleting containers for container group {}.", name, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Failed to remove bot containers: " + e.getMessage(), e);
        }

        try {
            stateRepository.delete(name);
            LOG.info("Deleted persisted state for container group {}.", name);
        } catch (RuntimeException e) {
            LOG.error("Failed deleting state for container group {}.", name, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to remove container group state: " + e.getMessage(), e);
        }

        onDeleteContainerGroup(name);
    }
    


    public synchronized void restart(String name) {
        LOG.info("Restarting bot {}.", name);
        var bot = stateRepository.load(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bot not found."));

        applyContainerAction(bot, name, dockerService::restartContainers, "restart", "Restarted");
    }

    public synchronized void start(String name) {
        LOG.info("Starting container group {}.", name);
        var bot = stateRepository.load(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bot not found."));

        applyContainerAction(bot, name, dockerService::startContainers, "start", "Started");
    }

    public synchronized void stop(String name) {
        LOG.info("Stopping container group {}.", name);
        var bot = stateRepository.load(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bot not found."));

        applyContainerAction(bot, name, dockerService::stopContainers, "stop", "Stopped");
    }

    private void applyContainerAction(T containerGroup,
            String name,
            Consumer<List<String>> operation,
            String actionVerb,
            String actionPastTense) {
        try {
            operation.accept(containerGroup.getContainerIds());
            LOG.info("{} {} container(s) for group {}.",
                    actionPastTense,
                    containerGroup.getContainerIds().size(),
                    name);
        } catch (RuntimeException e) {
            LOG.error("Failed {}ing containers for group {}.", actionVerb, name, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Failed to " + actionVerb + " bot containers: " + e.getMessage(), e);
        }
    }

    protected void onDeleteContainerGroup(String name) {
    }
    
    protected abstract Map<String, Object> toResponse(T bot);
    


    protected void rollbackFailedProvision(String name, List<String> containerIds, List<java.nio.file.Path> files) {
        try {
            dockerService.removeContainers(containerIds);
        } catch (RuntimeException ignored) {
            // Rollback is best effort.
            LOG.warn("Rollback could not remove all containers for container group {}.", name);
        }

        for (var path : files.reversed()) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Rollback is best effort.
                LOG.warn("Rollback could not delete file {} for container group {}.", path, name);
            }
        }

        try {
            stateRepository.delete(name);
        } catch (RuntimeException ignored) {
            // Rollback is best effort.
            LOG.warn("Rollback could not delete state for container group {} {}.", name);
        }

        onRollbackFailedProvision(name, containerIds, files);
    }

    protected String loadFileTemplate(String name) {
        var configFile = properties.getConfigDir().resolve(name);
        try {
            if (Files.exists(configFile)) {
                return Files.readString(configFile, StandardCharsets.UTF_8);
            }
            var resource = new ClassPathResource(name);
            try (var input = resource.getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load template file " + name + ".", e);
        }
    }

    protected java.nio.file.Path copyArchiveToWorkspace(String resourcePath, List<java.nio.file.Path> writtenFiles) {
        return ArchiveWorkspaceResolver.resolveArchivePath(resourcePath, properties.getWorkspaceDir(), writtenFiles, LOG);
    }

    protected void waitForStartupWindow(List<String> containerIds, Duration startupWindow, Duration pollInterval) {
        if (containerIds.isEmpty()) {
            return;
        }

        var deadlineMillis = System.currentTimeMillis() + startupWindow.toMillis();
        while (System.currentTimeMillis() < deadlineMillis) {
            try {
                var statuses = dockerService.getContainerStatuses(containerIds);
                if (!statuses.isEmpty() && statuses.stream().allMatch(ContainerStatus::running)) {
                    LOG.info("All {} container(s) report running before startup timeout.", statuses.size());
                    return;
                }
            } catch (RuntimeException e) {
                LOG.warn("Container status poll failed during startup window. Will retry.", e);
            }

            var remainingMillis = deadlineMillis - System.currentTimeMillis();
            if (remainingMillis <= 0) {
                break;
            }
            var sleepMillis = Math.min(pollInterval.toMillis(), remainingMillis);
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for containers to start.", e);
            }
        }

        LOG.info("Startup window elapsed before all containers reported running.");
    }
    
    protected static String defaultEmail(String first, String last, String requestedEmail) {
        var trimmed = requestedEmail == null ? "" : requestedEmail.trim();
        if (!trimmed.isEmpty()) {
            return trimmed;
        }
        return first + "." + last + "@localhost";
    }

    protected static String valueOrFail(String requestedValue) {
    	if (requestedValue == null || requestedValue.trim().isEmpty()) {
			throw new IllegalArgumentException("Required value is missing or blank.");
		}
    	return requestedValue.trim();
    }

    protected static String defaultValue(String requestedValue, String fallbackValue) {
    	return defaultValue(requestedValue, () -> fallbackValue);
    }

    protected static String defaultValue(String requestedValue, Supplier<String> fallbackValue) {
        var trimmed = requestedValue == null ? "" : requestedValue.trim();
        if (!trimmed.isEmpty()) {
            return trimmed;
        }
        return fallbackValue.get();
    }

	protected void onRollbackFailedProvision(String name, List<String> containerIds, List<Path> files) {
	}

	protected void materializeFiles(Plan plan, List<Path> writtenFiles, Map<String, String> variables) {
		LOG.info("Materializing files for {} container(s).", plan.containers().size());
		for (var container : plan.containers()) {
			LOG.info("Container materialization step: {} directories, {} files, {} managed files.",
					container.getDirectories().size(), container.getFiles().size(), container.getManagedFiles().size());
			for (var dir : container.getDirectories()) {
				var targetPath = Path.of(dir);
				try {
					LOG.info("Ensuring directory exists: {}", targetPath);
					Files.createDirectories(targetPath);
					writtenFiles.add(targetPath);
				} catch (IOException e) {
					throw new IllegalStateException(
							"Failed to materialize container group directory " + targetPath + ".", e);
				}
			}

			for (var fileEntry : container.getFiles().entrySet()) {
				var targetPath = java.nio.file.Path.of(fileEntry.getKey());
				var templateName = fileEntry.getValue();
				var content = loadFileTemplate(templateName);
				var resolved = templateResolver.resolve(content, variables);
				try {
					var parent = targetPath.getParent();
					if (parent != null) {
						LOG.info("Ensuring file parent directory exists: {}", parent);
						Files.createDirectories(parent);
					}
					LOG.info("Writing materialized file '{}' from template '{}'.", targetPath, templateName);
					Files.writeString(targetPath, resolved, StandardCharsets.UTF_8);
					writtenFiles.add(targetPath);
				} catch (IOException e) {
					throw new IllegalStateException("Failed to materialize container group file " + targetPath + ".",
							e);
				}
			}

			/*
			 * Managed files allow other parts of the stack to contribute configuratiln
			 * files. The child add-on (e.g. blender, a stack add-on) will add configuration
			 * files to the drop-in directory, and the parent stack container (e.g. stack,
			 * sim or bot container) will add its own files (highest priority, loaded first)
			 * to the dropins directory, and then scan the drop in directory for other files
			 * to merge. At the moment, only merging of JSON files is supported
			 */

			for (var managedFile : container.getManagedFiles()) {
				var templateName = managedFile.resource();
				if (!templateName.endsWith(".json")) {
					throw new IllegalArgumentException("Managed file resource must be JSON");
				}
				var dropInDir = Path.of(managedFile.dropIns());
				var targetName = templateResolver.resolve( managedFile.target(), variables);
        var content = loadManagedFileTemplate(templateName, targetName);
        var resolved = templateResolver.resolve(content, variables);
				LOG.info("Processing managed file template '{}' with drop-ins directory '{}' and target '{}'.",
						templateName, dropInDir, targetName);

				try {
					Files.createDirectories(dropInDir);
					LOG.info("Ensured drop-ins directory exists: {}", dropInDir);
				} catch (IOException e) {
					throw new IllegalStateException("Failed to create drop-ins directory " + dropInDir + ".", e);
				}

				if (targetName == null || targetName.trim().isEmpty()) {
					// Add-on side of contribution
					var number = new AtomicInteger(1);
					LOG.info("Managed file '{}' is an add-on contribution; allocating drop-in index in '{}'.",
							templateName, dropInDir);

					try {
						Files.list(dropInDir).forEach(path -> {
							var fileName = path.getFileName().toString();
							if (fileName.matches("\\d{2}-.*\\.json") && fileName.endsWith(templateName)) {
								var prefix = fileName.substring(0, 2);
								try {
									var prefixNum = Integer.parseInt(prefix);
									if (prefixNum >= number.get()) {
										number.set(prefixNum + 1);
									}
								} catch (NumberFormatException e) {
									// Ignore
								}
							}
						});
					} catch (IOException e) {
						throw new IllegalStateException("Failed to find next drop in number.", e);
					}

					var dropInPath = dropInDir.resolve(String.format("%02d-%s", number.get(), templateName));
					try {
						LOG.info("Writing add-on drop-in file: {}", dropInPath);
						Files.writeString(dropInPath, resolved, StandardCharsets.UTF_8);
						writtenFiles.add(dropInPath);
					} catch (IOException e) {
						throw new IllegalStateException(
								"Failed to materialize container group file " + dropInPath + ".", e);
					}
				} else {
					// Parent stack side of contribution
					var targetPath = Path.of(targetName);
					var dropInPath = dropInDir.resolve("00-" + templateName);
					try {
						LOG.info("Writing parent drop-in file '{}' for target '{}'.", dropInPath, targetPath);
						Files.writeString(dropInPath, resolved, StandardCharsets.UTF_8);
						writtenFiles.add(dropInPath);
					} catch (IOException e) {
						throw new IllegalStateException(
								"Failed to materialize container group file " + dropInPath + ".", e);
					}

					try {
						var parent = targetPath.getParent();
						if (parent != null) {
							LOG.info("Ensuring managed target parent directory exists: {}", parent);
							Files.createDirectories(parent);
						}

						var merged = mergeJsonDropIns(Path.of(managedFile.dropIns()), templateName);
						LOG.info("Writing merged managed JSON target file: {}", targetPath);
						Files.writeString(targetPath,
								OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(merged)
										+ System.lineSeparator(),
								StandardCharsets.UTF_8);
						writtenFiles.add(targetPath);
					} catch (IOException e) {
						throw new IllegalStateException(
								"Failed to materialize container group file " + targetPath + ".", e);
					}
				}
			}
		}
	}

  protected String loadManagedFileTemplate(String name, String targetName) {
    return loadFileTemplate(name);
  }

	protected static String nonBlankOrNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ObjectNode mergeJsonDropIns(Path dropInsPath, String templateName) throws IOException {
        if (!Files.exists(dropInsPath)) {
            LOG.info("Drop-ins directory does not exist yet, returning empty merge object: {}", dropInsPath);
            return OBJECT_MAPPER.createObjectNode();
        }

        var merged = OBJECT_MAPPER.createObjectNode();
        try (var stream = Files.list(dropInsPath)) {
            var sortedJsonFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json") && path.getFileName().toString().endsWith(templateName))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();

            LOG.info("Merging {} drop-in JSON file(s) from '{}': {}",
                    sortedJsonFiles.size(),
                    dropInsPath,
                    sortedJsonFiles.stream().map(Path::toString).toList());

            for (var jsonFile : sortedJsonFiles) {
                var parsed = OBJECT_MAPPER.readTree(jsonFile.toFile());
                if (!(parsed instanceof ObjectNode objectNode)) {
                    throw new IllegalStateException("Managed drop-in file '" + jsonFile + "' must contain a JSON object.");
                }
                LOG.info("Applying drop-in JSON file: {}", jsonFile);
                deepMerge(merged, objectNode);
            }
        }
        return merged;
    }

    private void deepMerge(ObjectNode target, ObjectNode source) {
        source.fields().forEachRemaining(entry -> {
            var key = entry.getKey();
            var sourceValue = entry.getValue();
            var targetValue = target.get(key);

            if (sourceValue.isObject() && targetValue != null && targetValue.isObject()) {
                deepMerge((ObjectNode) targetValue, (ObjectNode) sourceValue);
                return;
            }

            target.set(key, sourceValue.deepCopy());
        });
    }

}
