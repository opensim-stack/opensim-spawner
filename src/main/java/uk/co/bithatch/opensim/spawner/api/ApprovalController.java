package uk.co.bithatch.opensim.spawner.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import uk.co.bithatch.opensim.spawner.service.ApprovalService;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> listApprovals() {
        return approvalService.listApprovals().stream()
                .map((approval) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("first", approval.getFirst());
                    item.put("last", approval.getLast());
                    item.put("email", approval.getEmail());
                    item.put("requestedAtEpochMillis", approval.getRequestedAtEpochMillis());
                    item.put("name", approval.displayName());
                    return item;
                })
                .toList();
    }

    @PatchMapping(path = "/{first}/{last}", consumes = {
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> patchApproval(@PathVariable String first,
            @PathVariable String last,
            @RequestParam String action) {
        var normalizedAction = ApprovalService.toAction(action);
        switch (normalizedAction) {
            case "approve" -> {
                var approved = approvalService.approve(first, last);
                var response = new LinkedHashMap<String, Object>();
                response.put("ok", true);
                response.put("action", normalizedAction);
                response.put("first", approved.getFirst());
                response.put("last", approved.getLast());
                response.put("email", approved.getEmail());
                return response;
            }
            case "approve-handler" -> {
                var approved = approvalService.approve(first, last, true);
                var response = new LinkedHashMap<String, Object>();
                response.put("ok", true);
                response.put("action", normalizedAction);
                response.put("first", approved.getFirst());
                response.put("last", approved.getLast());
                response.put("email", approved.getEmail());
                return response;
            }
            case "reject" -> {
                approvalService.delete(first, last);
                var response = new LinkedHashMap<String, Object>();
                response.put("ok", true);
                response.put("action", normalizedAction);
                response.put("first", first);
                response.put("last", last);
                return response;
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported action '" + action + "'. Supported actions: approve, approve-handler, reject.");
        }
    }

    @DeleteMapping(path = "/{first}/{last}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> deleteApproval(@PathVariable String first, @PathVariable String last) {
        approvalService.delete(first, last);
        var response = new LinkedHashMap<String, Object>();
        response.put("ok", true);
        response.put("action", "delete");
        response.put("first", first);
        response.put("last", last);
        return response;
    }
}
