package uk.co.bithatch.opensim.spawner.domain;

import java.util.LinkedHashMap;
import java.util.Map;

public class ContainerSpec {

    private String image;
    private String name;
    private Map<String, String> environment = new LinkedHashMap<>();
    private Map<String, String> volumes = new LinkedHashMap<>();
    private Map<String, String> files = new LinkedHashMap<>();

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    public void setEnvironment(Map<String, String> environment) {
        this.environment = environment;
    }

    public Map<String, String> getVolumes() {
        return volumes;
    }

    public void setVolumes(Map<String, String> volumes) {
        this.volumes = volumes;
    }

    public Map<String, String> getFiles() {
        return files;
    }

    public void setFiles(Map<String, String> files) {
        this.files = files;
    }
}
