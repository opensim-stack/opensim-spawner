package uk.co.bithatch.opensim.spawner.service;

import static uk.co.bithatch.opensim.spawner.state.ApprovalStateRepository.key;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import uk.co.bithatch.opensim.spawner.domain.ApprovalInstanceData;
import uk.co.bithatch.opensim.spawner.domain.ApprovalLevel;
import uk.co.bithatch.opensim.spawner.state.ApprovalStateRepository;

@Service
public class ApprovalService {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9]+$");

    private final ApprovalStateRepository approvalStateRepository;
    private final OpenSimService openSimService;
    private final SimulatorProvisioningService simulatorProvisioningService;
    private final BotProvisioningService botProvisioningService;

    public ApprovalService(ApprovalStateRepository approvalStateRepository,
            OpenSimService openSimService,
            SimulatorProvisioningService simulatorProvisioningService,
            BotProvisioningService botProvisioningService) {
        this.approvalStateRepository = approvalStateRepository;
        this.openSimService = openSimService;
        this.simulatorProvisioningService = simulatorProvisioningService;
        this.botProvisioningService = botProvisioningService;
    }

    public synchronized ApprovalInstanceData createApproval(String first, String last, String email, String password) {
        var normalizedFirst = normalizeName("first", first);
        var normalizedLast = normalizeName("last", last);
        var normalizedEmail = normalizeRequired("email", email);
        var normalizedPassword = normalizeRequired("password", password);

        if (approvalStateRepository.exists(key(normalizedFirst, normalizedLast))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An approval already exists for " + normalizedFirst + " " + normalizedLast + ".");
        }

        var approval = new ApprovalInstanceData();
        approval.setLevel(ApprovalLevel.PENDING);
        approval.setFirst(normalizedFirst);
        approval.setLast(normalizedLast);
        approval.setEmail(normalizedEmail);
        approval.setPassword(normalizedPassword);
        approval.setRequestedAtEpochMillis(Instant.now().toEpochMilli());
        approvalStateRepository.save(approval);
        return approval;
    }

    public synchronized List<ApprovalInstanceData> listApprovals() {
        return approvalStateRepository.list();
    }

    public synchronized ApprovalInstanceData approve(String first, String last) {
        return approve(first, last, false);
    }

    public synchronized ApprovalInstanceData approve(String first, String last, boolean asHandler) {
        ensureGridLoginServiceAvailable();
        var normalizedFirst = normalizeName("first", first);
        var normalizedLast = normalizeName("last", last);
        var approval = loadRequired(normalizedFirst, normalizedLast);

        var uuid = UUID.randomUUID().toString();
        var model = "Ruth";

        openSimService.createUser(
                approval.getFirst(),
                approval.getLast(),
                approval.getPassword(),
                approval.getEmail(),
                uuid,
                model);

        if (asHandler) {
            botProvisioningService.addHandler("*", "*", approval.getFirst(), approval.getLast());
        }

        approvalStateRepository.delete(key(normalizedFirst, normalizedLast));
        return approval;
    }

    public synchronized void delete(String first, String last) {
        var normalizedFirst = normalizeName("first", first);
        var normalizedLast = normalizeName("last", last);
        if (!approvalStateRepository.exists(key(normalizedFirst, normalizedLast))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Approval not found for " + normalizedFirst + " " + normalizedLast + ".");
        }
        approvalStateRepository.delete(key(normalizedFirst, normalizedLast));
    }

    private ApprovalInstanceData loadRequired(String first, String last) {
        return approvalStateRepository.load(key(first, last))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Approval not found for " + first + " " + last + "."));
    }

    private void ensureGridLoginServiceAvailable() {
        if (simulatorProvisioningService.hasActiveGridLoginService()) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Approvals cannot be approved because no active ROBUST/STANDALONE simulator is providing grid login services.");
    }

    private static String normalizeName(String field, String value) {
        var normalized = normalizeRequired(field, value);
        if (!NAME_PATTERN.matcher(normalized).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid " + field + ". Use alphanumeric characters only.");
        }
        return normalized;
    }

    private static String normalizeRequired(String field, String value) {
        var normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: " + field + ".");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public static String toAction(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
