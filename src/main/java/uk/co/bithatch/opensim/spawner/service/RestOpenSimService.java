package uk.co.bithatch.opensim.spawner.service;

import java.util.Optional;

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
            var command = String.format("create user %s %s %s %s %s %s",
                    first,
                    last,
                    password,
                    email,
                    uuid,
                    model);
            console.execute(command).toList();
            LOG.info("Created OpenSim user {} {}.", first, last);
        } catch (RuntimeException e) {
            throw new ExternalDependencyException("Failed to create OpenSimulator user via REST console. " + e.getMessage(), e);
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

    private OpensimRESTConsole openConsole() {
        var user = Optional.ofNullable(properties.getOpensimConsoleUser()).filter(value -> !value.isBlank());
        var pass = Optional.ofNullable(properties.getOpensimConsolePass())
                .filter(value -> !value.isBlank())
                .map(String::toCharArray);
        return new OpensimRESTConsole(properties.getOpensimConsoleUrl(), user, pass);
    }
}
