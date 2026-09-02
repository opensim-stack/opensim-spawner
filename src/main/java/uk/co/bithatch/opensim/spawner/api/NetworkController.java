package uk.co.bithatch.opensim.spawner.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import uk.co.bithatch.opensim.spawner.service.NetworkAddressStatusView;
import uk.co.bithatch.opensim.spawner.service.NetworkContainerPortsView;
import uk.co.bithatch.opensim.spawner.service.StackContainerService;

@RestController
@RequestMapping("/api/network")
public class NetworkController {

    private final StackContainerService stackContainerService;

    public NetworkController(StackContainerService stackContainerService) {
        this.stackContainerService = stackContainerService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<NetworkContainerPortsView> listNetworkPorts() {
        return stackContainerService.listNetworkContainerPorts();
    }

    @GetMapping(path = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public NetworkAddressStatusView networkStatus() {
        return stackContainerService.detectNetworkAddressStatus();
    }

    @PostMapping(path = "/test", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> testExternalNetwork() {
        var response = new LinkedHashMap<String, Object>();
        response.put("ok", false);
        response.put("error", "External Network test not yet implemented");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(response);
    }
}
