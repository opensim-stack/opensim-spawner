package uk.co.bithatch.opensim.spawner.domain;

import java.util.List;

public class GridState implements DomainObject {

	private String adminToken;
	private String name;
	private String nick;
	private List<String> addOns;

	public String getAdminToken() {
		return adminToken;
	}

	public List<String> getAddOns() {
		return addOns;
	}

	public void setAddOns(List<String> addOns) {
		this.addOns = addOns;
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
