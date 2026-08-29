package uk.co.bithatch.opensim.spawner.api;

import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import uk.co.bithatch.opensim.spawner.config.SpawnerProperties;
import uk.co.bithatch.opensim.spawner.domain.AddOn;
import uk.co.bithatch.opensim.spawner.service.AddOnService;
import uk.co.bithatch.opensim.spawner.state.AddOnRepository;

@RestController
@RequestMapping("/api/add-ons")
public class AddOnController {

    private final AddOnService addOnService;
    private final AddOnRepository addOnRepository;
    private final SpawnerProperties properties;

    public AddOnController(AddOnService addOnService,
            AddOnRepository addOnRepository,
            SpawnerProperties properties) {
        this.addOnService = addOnService;
        this.addOnRepository = addOnRepository;
        this.properties = properties;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<AddOn> listAddOns(HttpServletRequest request) {
        ensureAdmin(request);
        return addOnService.getAddOns();
    }

    @GetMapping(path = "/icon")
    public ResponseEntity<Resource> icon(@RequestParam("name") String name, HttpServletRequest request) {
        ensureAdmin(request);

        var addOnName = sanitizeAddOnName(name);
        var manifest = addOnRepository.load(addOnName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Add-on not found."));

        var iconName = iconFileName(manifest.getIcon());
        if (iconName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Add-on icon not configured.");
        }

        var addOnDir = properties.getAddOnsDir().toAbsolutePath().normalize().resolve(addOnName).normalize();
        var iconPath = addOnDir.resolve(iconName).normalize();
        if (!iconPath.startsWith(addOnDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid icon path.");
        }
        if (!Files.isRegularFile(iconPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Add-on icon not found.");
        }

        try {
            var mediaType = mediaTypeFor(iconPath);
            var body = new ByteArrayResource(Files.readAllBytes(iconPath));
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(body);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read add-on icon.", e);
        }
    }

    @PatchMapping(consumes = {
            MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setEnabled(@RequestParam String name,
            @RequestParam boolean enabled,
            HttpServletRequest request) {
        ensureAdmin(request);

        if (enabled) {
            addOnService.enableAddOn(name);
        } else {
            addOnService.disableAddOn(name);
        }

        var response = new LinkedHashMap<String, Object>();
        response.put("ok", true);
        response.put("name", name);
        response.put("enabled", enabled);
        return response;
    }

    @PostMapping(path = "/reload", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> reloadAddOns(HttpServletRequest request) {
        ensureAdmin(request);
        addOnService.reload();

        var response = new LinkedHashMap<String, Object>();
        response.put("ok", true);
        return response;
    }

    private static void ensureAdmin(HttpServletRequest request) {
        if (!UiAuthSupport.isAdmin(request.getSession(false))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access is required.");
        }
    }

    private static String sanitizeAddOnName(String rawName) {
        var name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || !name.matches("[A-Za-z0-9._ -]+") || name.contains("..") || name.contains("/")
                || name.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid add-on name.");
        }
        return name;
    }

    private static String iconFileName(String configuredPath) {
        var value = configuredPath == null ? "" : configuredPath.trim();
        if (value.isEmpty()) {
            return "";
        }
        var normalized = value.replace('\\', '/');
        var slash = normalized.lastIndexOf('/');
        var fileName = (slash < 0 ? normalized : normalized.substring(slash + 1)).trim();
        if (fileName.isEmpty() || fileName.equals(".") || fileName.equals("..") || fileName.contains("/")
                || fileName.contains("\\")) {
            return "";
        }
        return fileName;
    }

    private static MediaType mediaTypeFor(Path path) {
        try {
            var probed = Files.probeContentType(path);
            if (probed != null && !probed.isBlank()) {
                return MediaType.parseMediaType(probed);
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Fall through to extension/name-based content type detection.
        }

        var guessed = URLConnection.guessContentTypeFromName(path.getFileName().toString());
        if (guessed != null && !guessed.isBlank()) {
            try {
                return MediaType.parseMediaType(guessed);
            } catch (IllegalArgumentException ignored) {
                // Keep octet-stream fallback.
            }
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}