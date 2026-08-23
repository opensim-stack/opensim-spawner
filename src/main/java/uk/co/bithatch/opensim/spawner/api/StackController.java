package uk.co.bithatch.opensim.spawner.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import uk.co.bithatch.opensim.spawner.service.StackContainerService;
import uk.co.bithatch.opensim.spawner.service.StackContainerView;

@RestController
@RequestMapping("/api/stack")
public class StackController {

    private final StackContainerService stackContainerService;

    public StackController(StackContainerService stackContainerService) {
        this.stackContainerService = stackContainerService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<StackContainerView> listStackContainers() {
        return stackContainerService.listStackContainers();
    }

    @PatchMapping(consumes = {
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> applyAction(@RequestParam String container,
            @RequestParam String action) {
        var status = stackContainerService.applyAction(container, action);
        var response = new LinkedHashMap<String, Object>();
        response.put("container", status.containerName());
        response.put("action", action == null ? "" : action.trim().toLowerCase());
        response.put("status", status.status());
        response.put("running", status.running());
        response.put("ok", true);
        return response;
    }
}
