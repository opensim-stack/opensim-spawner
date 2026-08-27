package uk.co.bithatch.opensim.spawner.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ContainerSpec {

    private String image;
    private String name;
    private Map<String, String> environment = new LinkedHashMap<>();
    private Map<String, String> volumes = new LinkedHashMap<>();
    private Map<String, String> files = new LinkedHashMap<>();
    private Map<String, String> ports = new LinkedHashMap<>();
    private Map<String, String> extraHosts = new LinkedHashMap<>();
    private String hostname;
    private List<String> aliases = new ArrayList<>();
    private List<String> directories = new ArrayList<>();

    public List<String> getDirectories() {
		return directories;
	}

	public void setDirectories(List<String> directories) {
		this.directories = directories;
	}

	public List<String> getAliases() {
		return aliases;
	}

	public void setAliases(List<String> aliases) {
		this.aliases = aliases;
	}

	public String getHostname() {
		return hostname;
	}

	public void setHostname(String hostname) {
		this.hostname = hostname;
	}

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

	public Map<String, String> getPorts() {
		return ports;
	}

	public void setPorts(Map<String, String> ports) {
		this.ports = ports;
	}

	public Map<String, String> getExtraHosts() {
		return extraHosts;
	}

	public void setExtraHosts(Map<String, String> extraHosts) {
		this.extraHosts = extraHosts;
	}

	@Override
	public String toString() {
		return "ContainerSpec [image=" + image + ", name=" + name + ", environment=" + environment + ", volumes="
				+ volumes + ", files=" + files + ", ports=" + ports + ", extraHosts=" + extraHosts + ", hostname="
				+ hostname + ", aliases=" + aliases + "]";
	}
}
