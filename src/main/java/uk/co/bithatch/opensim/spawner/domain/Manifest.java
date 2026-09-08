package uk.co.bithatch.opensim.spawner.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Manifest extends Component {

	private String version;
	private String description;
	private String author;
	private String icon;
	private Map<String, String> constants = new HashMap<>();
	private Map<ContainerLevel, Map<String, ContainerSpec>> extensions = new HashMap<>();
	private List<String> tokens = new ArrayList<>();
	private Map<HookType, List<Map<String, Object>>> hooks = new HashMap<>();
	private List<String> exports = new ArrayList<>();
	
	public List<String> getExports() {
		return exports;
	}

	public void setExports(List<String> exports) {
		this.exports = exports;
	}

	public Map<HookType, List<Map<String, Object>>> getHooks() {
		return hooks;
	}

	public void setHooks(Map<HookType, List<Map<String, Object>>> hooks) {
		this.hooks = hooks;
	}

	public List<String> getTokens() {
		return tokens;
	}

	public void setTokens(List<String> tokens) {
		this.tokens = tokens;
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

	public Map<ContainerLevel, Map<String, ContainerSpec>> getExtensions() {
		return extensions;
	}

	public void setExtensions(Map<ContainerLevel, Map<String, ContainerSpec>> extensions) {
		this.extensions = extensions;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

}
