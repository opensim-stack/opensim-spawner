package uk.co.bithatch.opensim.spawner.domain;

import java.util.List;

public class BotInstanceData {

    private String first;
    private String last;
    private BotLevel level;
    private String password;
    private String parent;
    private String email;
    private String uuid;
    private String model;
    private List<String> containerIds = List.of();
    private String token;

    public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token;
	}

	public String getFirst() {
        return first;
    }

    public void setFirst(String first) {
        this.first = first;
    }

    public String getLast() {
        return last;
    }

    public void setLast(String last) {
        this.last = last;
    }

    public BotLevel getLevel() {
        return level;
    }

    public void setLevel(BotLevel level) {
        this.level = level;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getParent() {
        return parent;
    }

    public void setParent(String parent) {
        this.parent = parent;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<String> getContainerIds() {
        return containerIds;
    }

    public void setContainerIds(List<String> containerIds) {
        this.containerIds = containerIds == null ? List.of() : List.copyOf(containerIds);
    }

    public String key() {
        return first + "-" + last;
    }

    public String displayName() {
        return first + " " + last;
    }
}
