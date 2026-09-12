package uk.co.bithatch.opensim.spawner.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Component<LVL extends Enum<LVL>> implements DomainObject {
	private String name;

	public Component() { }

	private Map<String, String> constants = new HashMap<>();
	private Map<LVL, Map<String, Object>> extensions = new HashMap<>();
	private List<String> tokens = new ArrayList<>();
	private Map<HookType, List<Map<String, Object>>> hooks = new HashMap<>();
	private List<String> exports = new ArrayList<>();


	public final String getName() {
		return name;
	}

	public final void setName(String name) {
		this.name = name;
	}
	
	public final List<String> getExports() {
		return exports;
	}

	public final void setExports(List<String> exports) {
		this.exports = exports;
	}

	public final Map<HookType, List<Map<String, Object>>> getHooks() {
		return hooks;
	}

	public final void setHooks(Map<HookType, List<Map<String, Object>>> hooks) {
		this.hooks = hooks;
	}

	public final List<String> getTokens() {
		return tokens;
	}

	public final void setTokens(List<String> tokens) {
		this.tokens = tokens;
	}

	public final Map<String, String> getConstants() {
		return constants;
	}

	public final void setConstants(Map<String, String> constants) {
		this.constants = constants;
	}

	public final Map<LVL, Map<String, Object>> getExtensions() {
		return extensions;
	}

	public final void setExtensions(Map<LVL, Map<String, Object>> extensions) {
		this.extensions = extensions;
	}

}
