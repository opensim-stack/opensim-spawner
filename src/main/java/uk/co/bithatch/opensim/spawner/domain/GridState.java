package uk.co.bithatch.opensim.spawner.domain;

public class GridState implements DomainObject {

	private String adminToken;
	private String name;
	private String nick;

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
