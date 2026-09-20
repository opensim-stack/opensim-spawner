package uk.co.bithatch.opensim.spawner.api;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import uk.co.bithatch.opensim.jlib.IO;
import uk.co.bithatch.opensim.spawner.service.BotProvisioningService;
import uk.co.bithatch.opensim.spawner.service.SimulatorProvisioningService;

@RestController
@RequestMapping("/api/import")
public class ImportController {

    private static final Logger LOG = LoggerFactory.getLogger(ImportController.class);

    private final BotProvisioningService provisioningService;
    private final SimulatorProvisioningService simulatorProvisioningService;

    public ImportController(BotProvisioningService provisioningService,
    		SimulatorProvisioningService simulatorProvisioningService) {
        this.provisioningService = provisioningService;
        this.simulatorProvisioningService = simulatorProvisioningService;
    }

    @GetMapping(path = "/oar-url/{simulator}/{region}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public java.util.LinkedHashMap<String, Object> importOARByUrl(
    		@PathVariable String simulator,
    		@PathVariable String region,
            @RequestParam("url") String url,
            @RequestParam(value = "merge", defaultValue = "true") boolean merge,
            @RequestParam(value = "skipAssets", defaultValue = "false") boolean skipAssets
          ) {
        try {
            var openUrl = URI.create(url.trim()).toURL();
            var urlc = (HttpURLConnection)openUrl.openConnection();
            var filename = IO.getFilename(urlc);
            try (var stream = urlc.getInputStream()) {
                if(filename == null) {
                	filename = openUrl.getPath();
                }
                filename = Path.of(filename).getFileName().toString();
                simulatorProvisioningService.importOAR(simulator, region, stream, filename, merge, skipAssets);
            }

            var response = new java.util.LinkedHashMap<String, Object>();
            response.put("region", region);
            response.put("file", filename);
            response.put("url", url.trim());
            response.put("imported", true);
            return response;
        } catch (IllegalArgumentException | IOException e) {
            LOG.error("Failed to import OAR for region {} from URL {}.", region, url, e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping(path = "/oar/{simulator}/{region}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public java.util.LinkedHashMap<String, Object> importOAR(
    		@PathVariable String simulator,
    		@PathVariable String region,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "merge", defaultValue = "true") boolean merge,
            @RequestParam(value = "skipAssets", defaultValue = "false") boolean skipAssets) {
        try {
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("Uploaded file is empty.");
            }

            var filename = file.getOriginalFilename();
            if (filename == null || filename.isBlank()) {
                throw new IllegalArgumentException("Uploaded filename is missing.");
            }
            filename = Path.of(filename).getFileName().toString();

            simulatorProvisioningService.importOAR(simulator, region, file.getInputStream(), filename, merge, skipAssets);

            var response = new java.util.LinkedHashMap<String, Object>();
            response.put("region", region);
            response.put("file", filename);
            response.put("imported", true);
            return response;
        } catch (IOException | IllegalArgumentException e) {
            LOG.error("Failed to import IAR for region {}.", region, e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping(path = "/iar/{first}/{last}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public java.util.LinkedHashMap<String, Object> importIAR(@PathVariable String first,
            @PathVariable String last,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "inventoryPath", required = false) String inventoryPath) {
        try {
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("Uploaded file is empty.");
            }

            var filename = file.getOriginalFilename();
            if (filename == null || filename.isBlank()) {
                throw new IllegalArgumentException("Uploaded filename is missing.");
            }
            filename = Path.of(filename).getFileName().toString();

            inventoryPath = provisioningService.importIAR(first, last, file.getInputStream(), filename, inventoryPath);

            var response = new java.util.LinkedHashMap<String, Object>();
            response.put("first", first);
            response.put("last", last);
            response.put("file", filename);
            response.put("inventoryPath", inventoryPath);
            response.put("imported", true);
            return response;
        } catch (IOException | IllegalArgumentException e) {
            LOG.error("Failed to import IAR for bot {} {}.", first, last, e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @GetMapping(path = "/iar-url/{first}/{last}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public java.util.LinkedHashMap<String, Object> importIARByUrl(@PathVariable String first,
            @PathVariable String last,
            @RequestParam("url") String url,
            @RequestParam(value = "inventoryPath", required = false) String inventoryPath) {
        try {
            var openUrl = URI.create(url.trim()).toURL();
            var urlc = (HttpURLConnection)openUrl.openConnection();
            var filename = IO.getFilename(urlc);
            try (var stream = urlc.getInputStream()) {
                if(filename == null) {
                	filename = openUrl.getPath();
                }
                filename = Path.of(filename).getFileName().toString();
                inventoryPath = provisioningService.importIAR(first, last, stream, filename, inventoryPath);
            }

            var response = new java.util.LinkedHashMap<String, Object>();
            response.put("first", first);
            response.put("last", last);
            response.put("file", filename);
            response.put("inventoryPath", inventoryPath);
            response.put("url", url.trim());
            response.put("imported", true);
            return response;
        } catch (IllegalArgumentException | IOException e) {
            LOG.error("Failed to import IAR for bot {} {} from URL {}.", first, last, url, e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }
}
