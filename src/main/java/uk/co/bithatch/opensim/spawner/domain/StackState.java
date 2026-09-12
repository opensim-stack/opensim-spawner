package uk.co.bithatch.opensim.spawner.domain;

import java.util.HashMap;
import java.util.Map;

public class StackState extends ContainerGroupInstanceData<StackLevel> {

	public static final String ADMIN_TOKEN = "ADMIN_TOKEN";
	@Deprecated
	private String adminToken;
	private String name;
	private String nick;
	private String welcomeMessage;
	private Map<String, String> tokens = new HashMap<>();
	private String consoleUser;
	private String consolePass;
	private String addOnsRepository;
	private String addOnsBranch;
	private UpdatesConfiguration updates = new UpdatesConfiguration();
	private boolean initialized;
	private Map<String, String> global = new HashMap<>();

	public UpdatesConfiguration getUpdates() {
		if (updates == null) {
			updates = new UpdatesConfiguration();
		}
		return updates;
	}

	public Map<String, String> getGlobal() {
		return global;
	}

	public void setGlobal(Map<String, String> global) {
		this.global = global;
	}

	public void setUpdates(UpdatesConfiguration updates) {
		this.updates = updates == null ? new UpdatesConfiguration() : updates;
	}

	public boolean isInitialized() {
		return initialized;
	}

	public void setInitialized(boolean initialized) {
		this.initialized = initialized;
	}

	public String getConsoleUser() {
		return consoleUser;
	}

	public void setConsoleUser(String consoleUser) {
		this.consoleUser = consoleUser;
	}

	public String getConsolePass() {
		return consolePass;
	}

	public void setConsolePass(String consolePass) {
		this.consolePass = consolePass;
	}

	public String getAddOnsRepository() {
		return normalize(addOnsRepository);
	}

	public void setAddOnsRepository(String addOnsRepository) {
		this.addOnsRepository = normalize(addOnsRepository);
	}

	public String getAddOnsBranch() {
		return normalize(addOnsBranch);
	}

	public void setAddOnsBranch(String addOnsBranch) {
		this.addOnsBranch = normalize(addOnsBranch);
	}

	public String getWelcomeMessage() {
		return welcomeMessage;
	}

	public void setWelcomeMessage(String welcomeMessage) {
		this.welcomeMessage = welcomeMessage;
	}

	public Map<String, String> getTokens() {
		return tokens;
	}

	public void setTokens(Map<String, String> tokens) {
		this.tokens = tokens;
	}

	public String getAdminToken() {
		return adminToken;
	}

	@Deprecated
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

	private static String normalize(String value) {
		return value == null ? "" : value.trim();
	}

}
