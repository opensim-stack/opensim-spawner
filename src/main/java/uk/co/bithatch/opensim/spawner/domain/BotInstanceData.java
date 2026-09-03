package uk.co.bithatch.opensim.spawner.domain;

public class BotInstanceData extends ContainerGroupInstanceData<BotLevel> {

    private String first;
    private String last;
    private String password;
    private String parent;
    private String email;
    private String uuid;
    private String model;
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

    @Override
    public String getName() {
        return first + "-" + last;
    }

    // `name` is derived, but persisted JSON can include it; keep deserialization tolerant.
    public void setName(String ignored) {
    }

	@Override
	public String displayName() {
		return first + " " + last;
	}
}
