package uk.co.bithatch.opensim.spawner.domain;

import java.util.HashMap;
import java.util.Map;

public class GridState implements DomainObject {

	private String adminToken;
	private String name;
	private String nick;
	private Map<String, String> tokens = new HashMap<>();

	public Map<String, String> getTokens() {
		return tokens;
	}

	public void setTokens(Map<String, String> tokens) {
		this.tokens = tokens;
	}

	public String getAdminToken() {
		return adminToken;
	}

	public void setAdminToken(String adminToken) {
		this.adminToken = adminToken;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getNick() {
		return nick;
	}

	public void setNick(String nick) {
		this.nick = nick;
	}

	@Override
	public String displayName() {
		return "grid";
	}

}
