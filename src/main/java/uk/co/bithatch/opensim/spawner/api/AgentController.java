package uk.co.bithatch.opensim.spawner.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import uk.co.bithatch.opensim.jlib.OpensimRemoteAdminClient.AgentLocation;
import uk.co.bithatch.opensim.spawner.service.OpenSimService;
import uk.co.bithatch.opensim.spawner.service.SimulatorProvisioningService;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final OpenSimService openSimService;
    private final SimulatorProvisioningService simulatorProvisioningService;

    public AgentController(OpenSimService openSimService,
            SimulatorProvisioningService simulatorProvisioningService) {
        this.openSimService = openSimService;
        this.simulatorProvisioningService = simulatorProvisioningService;
    }

    @GetMapping(path = "/agent/{first}/{last}/", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> findAgent(@PathVariable String first, @PathVariable String last) {
        ensureGridLoginServiceAvailable();
        var account = openSimService.findAgentByName(first, last);

        return accountResponse(account);
    }

    @GetMapping(path = "/agent-by-uuid/{uuid}/", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> findAgentByUuid(@PathVariable String uuid) {
        ensureGridLoginServiceAvailable();
        var account = openSimService.findAgentByUuid(uuid);

        return accountResponse(account);
    }

    private Map<String, Object> accountResponse(Optional<AgentLocation> account) {
		Map<String, Object> response = new LinkedHashMap<>();
        response.put("found", account.isPresent());
        account.ifPresent(agent -> {
			response.put("regionUuid", agent.regionId());
			response.put("regionName", agent.regionName());
			response.put("parcelUuid", agent.agent().currentParcelUuid());
			response.put("flying", agent.agent().isFlying());
			response.put("satOnGround", agent.agent().isSatOnGround());
			response.put("lookAtX", agent.agent().lookatX());
			response.put("lookAtY", agent.agent().lookatY());
			response.put("lookAtZ", agent.agent().lookatZ());
			response.put("name", agent.agent().name());
			response.put("posX", agent.agent().posX());
			response.put("posY", agent.agent().posY());
			response.put("posZ", agent.agent().posZ());
			response.put("type", agent.agent().type());
			response.put("velX", agent.agent().velX());
			response.put("velY", agent.agent().velY());
			response.put("velZ", agent.agent().velZ());
		});
        return response;
	}

    private void ensureGridLoginServiceAvailable() {
        if (simulatorProvisioningService.hasActiveGridLoginService()) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "User management is unavailable because no active ROBUST/STANDALONE simulator is providing grid login services.");
    }
}
