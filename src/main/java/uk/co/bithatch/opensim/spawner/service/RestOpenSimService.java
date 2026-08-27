package uk.co.bithatch.opensim.spawner.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.opensim.OpensimRESTConsole;

@Service
public class RestOpenSimService implements OpenSimService {

    private static final Logger LOG = LoggerFactory.getLogger(RestOpenSimService.class);

    private final SpawnerProperties properties;

    public RestOpenSimService(SpawnerProperties properties) {
        this.properties = properties;
    }

    @Override
    public void createUser(String first, String last, String password, String email, String uuid, String model) {
        try (var console = openConsole()) {
            LOG.info("Creating OpenSim user {} {} (email={}, uuid={}, model={}).", first, last, email, uuid, model);
            console.executeCommand("create", "user", first, last, password, email, uuid, model).toList();
            LOG.info("Created OpenSim user {} {}.", first, last);
        } catch (RuntimeException e) {
            throw new ExternalDependencyException("Failed to create OpenSimulator user via REST console. " + e.getMessage(), e);
        }
    }

    @Override
	public void loadRegionArchive(String archivePath) {
    	 try (var console = openConsole()) {
             LOG.info("Loading opensimulator archive '{}''.", archivePath);
             console.executeCommand(
             		"load", "oar", 
             		archivePath).toList();
             LOG.info("Loaded opensimulator archive '{}'.", archivePath);
         } catch (RuntimeException e) {
             throw new ExternalDependencyException("Failed to load OpenSimulator inventory archive via REST console. " + e.getMessage(), e);
         }
		
	}

	@Override
    public void loadInventoryArchive(String first, String last, String inventoryPath, String password, String archivePath) {
        try (var console = openConsole()) {
            LOG.info("Loading inventory archive '{}' into {} {} at '{}'.", archivePath, first, last, inventoryPath);
            console.executeCommand(
            		"load", "iar", 
            		first, 
            		last, 
            		inventoryPath, 
            		password, 
            		archivePath).toList();
            LOG.info("Loaded inventory archive '{}' into {} {}.", archivePath, first, last);
        } catch (RuntimeException e) {
            LOG.error("Failed to load inventory archive '{}' into {} {}.", archivePath, first, last, e);
            throw new ExternalDependencyException("Failed to load OpenSimulator inventory archive via REST console. " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteUser(String first, String last) {
        // OpenSimulator user deletion is intentionally not implemented yet.
        LOG.warn("OpenSim delete user requested for {} {}, but deletion is not implemented.", first, last);
        throw new UnsupportedOperationException("OpenSimulator user deletion is not currently supported.");
    }

    @Override
    public Map<String, String> showAccount(String first, String last) {
        try (var console = openConsole()) {
            LOG.info("Looking up OpenSim account {} {}.", first, last);
            var output = console.executeCommand("show", "account", first, last).toList();
            return parseAccountDetails(output);
        } catch (RuntimeException e) {
            throw new ExternalDependencyException("Failed to query OpenSimulator user via REST console. " + e.getMessage(), e);
        }
    }

    @Override
    public List<Map<String, String>> showActiveUsers() {
        try (var console = openConsole()) {
            LOG.info("Listing active OpenSim users.");
            var output = console.executeCommand("show", "users", "full").toList();
            return parseActiveUsers(output);
        } catch (RuntimeException e) {
            throw new ExternalDependencyException("Failed to list active OpenSimulator users via REST console. " + e.getMessage(), e);
        }
    }

    @Override
    public void resetUserPassword(String first, String last, String password) {
        try (var console = openConsole()) {
            LOG.info("Resetting OpenSim password for {} {}.", first, last);
            console.executeCommand("reset", "user", "password", first, last, password).toList();
            LOG.info("Reset OpenSim password for {} {}.", first, last);
        } catch (RuntimeException e) {
            throw new ExternalDependencyException("Failed to reset OpenSimulator password via REST console. " + e.getMessage(), e);
        }
    }

    private static Map<String, String> parseAccountDetails(List<String> lines) {
        var details = new LinkedHashMap<String, String>();
        for (var rawLine : lines) {
            if (rawLine == null || rawLine.isBlank()) {
                continue;
            }
            for (var line : rawLine.split("\\R")) {
                var trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                var delimiter = trimmed.indexOf(':');
                if (delimiter <= 0) {
                    continue;
                }
                var key = trimmed.substring(0, delimiter).trim();
                var value = trimmed.substring(delimiter + 1).trim();
                if (!key.isEmpty()) {
                    details.put(key, value);
                }
            }
        }
        return details;
    }

    private static List<Map<String, String>> parseActiveUsers(List<String> lines) {
        var users = new ArrayList<Map<String, String>>();
        for (var rawLine : lines) {
            if (rawLine == null || rawLine.isBlank()) {
                continue;
            }
            for (var line : rawLine.split("\\R")) {
                var trimmed = line.trim();
                if (trimmed.isEmpty()
                        || trimmed.startsWith("Total agents in region")
                        || trimmed.startsWith("Firstname")) {
                    continue;
                }

                var columns = trimmed.split("\\s{2,}");
                if (columns.length < 5) {
                    continue;
                }

                var user = new LinkedHashMap<String, String>();
                user.put("first", columns[0].trim());
                user.put("last", columns[1].trim());
                user.put("agentId", columns[2].trim());
                user.put("type", columns[3].trim());
                user.put("position", columns[4].trim());
                users.add(user);
            }
        }
        return users;
    }

    private OpensimRESTConsole openConsole() {
        var user = Optional.ofNullable(properties.getOpensimConsoleUser()).filter(value -> !value.isBlank());
        var pass = Optional.ofNullable(properties.getOpensimConsolePass())
                .filter(value -> !value.isBlank())
                .map(String::toCharArray);
        return new OpensimRESTConsole(properties.getOpensimConsoleUrl(), user, pass);
    }
}
