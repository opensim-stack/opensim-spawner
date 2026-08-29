package uk.co.bithatch.opensim.spawner.domain;

import java.util.HashMap;
import java.util.Map;

public class Manifest implements DomainObject {

	private String version;
	private String name;
	private String description;
	private String author;
	private String icon;
	private Map<String, String> constants = new HashMap<>();
	private Map<AddOnType, Map<String, ContainerSpec>> extensions = new HashMap<>();

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getAuthor() {
		return author;
	}

	public void setAuthor(String author) {
		this.author = author;
	}

	public String getIcon() {
		return icon;
	}

	public void setIcon(String icon) {
		this.icon = icon;
	}

	public Map<String, String> getConstants() {
		return constants;
	}

	public void setConstants(Map<String, String> constants) {
		this.constants = constants;
	}

	public Map<AddOnType, Map<String, ContainerSpec>> getExtensions() {
		return extensions;
	}

	public void setExtensions(Map<AddOnType, Map<String, ContainerSpec>> extensions) {
		this.extensions = extensions;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	@Override
	public String displayName() {
		return name;
	}

}
