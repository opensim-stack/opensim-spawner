package uk.co.bithatch.opensim.spawner.config;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @see application.properties for the defaults
 */
@ConfigurationProperties(prefix = "spawner")
public class SpawnerProperties {

	private boolean opensimCreateBotUser;
	private String token = "";
	private String metaverse2mcpImage;
	private String opencodeImage;
	private String opensimHandlerConfig;
	private String opensimGridName;
	private String opensimGridNick;
	private String opensimEstateName;
	private String opensimEstateArchive;
	private String opensimWelcomeMessage;
	private String opensimConsoleUser;
	private String opensimConsolePass;
	private String opensimBotFirst;
	private String opensimBotLast;
	private String opensimBotEmail;
	private String opensimBotAppearance;
	private String opensimBotGender;
	private String opensimPullPolicy;
	private String opensimRestartPolicy;
	private String opensimNetwork;
	private String composeProjectName;
	private String opensimUserEmail;
	private String opensimUserPassword;
	private String opensimUserFirst;
	private String opensimUserLast;
	private String opensimProvisionMode;
	private String opensimGridServices;
	private Path addOnsDir;
	private String addOnsRepository;
	private boolean addOnsRefreshAtStartup;
	private Path configDir;
	private Path dataDir;
	private Path workspaceDir;
	private int firstPort;
	private int opensimRegionX;
	private int opensimRegionY;
	private int opensimMaxBots;
	private int opensimMaxSimulators;
	private int opensimRobustPublicPort;
	private int opensimRobustPrivatePort;

	public Path getAddOnsDir() {
		return addOnsDir;
	}

	public void setAddOnsDir(Path addOnsDir) {
		this.addOnsDir = addOnsDir;
	}

	public String getAddOnsRepository() {
		return addOnsRepository;
	}

	public void setAddOnsRepository(String addOnsRepository) {
		this.addOnsRepository = addOnsRepository == null ? "" : addOnsRepository.trim();
	}

	public boolean isAddOnsRefreshAtStartup() {
		return addOnsRefreshAtStartup;
	}

	public void setAddOnsRefreshAtStartup(boolean addOnsRefreshAtStartup) {
		this.addOnsRefreshAtStartup = addOnsRefreshAtStartup;
	}

	public String getOpensimGridServices() {
		return opensimGridServices;
	}

	public void setOpensimGridServices(String opensimGridServices) {
		this.opensimGridServices = opensimGridServices;
	}

	public int getOpensimRegionX() {
		return opensimRegionX;
	}

	public void setOpensimRegionX(int opensimRegionX) {
		this.opensimRegionX = opensimRegionX;
	}

	public int getOpensimRegionY() {
		return opensimRegionY;
	}

	public void setOpensimRegionY(int opensimRegionY) {
		this.opensimRegionY = opensimRegionY;
	}

	public String getOpensimEstateArchive() {
		return opensimEstateArchive;
	}

	public void setOpensimEstateArchive(String opensimEstateArchive) {
		this.opensimEstateArchive = opensimEstateArchive;
	}

	public String getOpensimEstateName() {
		return opensimEstateName;
	}

	public void setOpensimEstateName(String opensimEstateName) {
		this.opensimEstateName = opensimEstateName;
	}

	public String getOpensimBotAppearance() {
		return opensimBotAppearance;
	}

	public void setOpensimBotAppearance(String opensimBotAppearance) {
		this.opensimBotAppearance = opensimBotAppearance;
	}

	public String getOpensimBotGender() {
		return opensimBotGender;
	}

	public void setOpensimBotGender(String opensimBotGender) {
		this.opensimBotGender = opensimBotGender;
	}

	public String getOpensimProvisionMode() {
		return opensimProvisionMode;
	}

	public void setOpensimProvisionMode(String opensimProvisionMode) {
		this.opensimProvisionMode = opensimProvisionMode;
	}

	public String getOpensimUserEmail() {
		return opensimUserEmail;
	}

	public void setOpensimUserEmail(String opensimUserEmail) {
		this.opensimUserEmail = opensimUserEmail;
	}

	public String getOpensimUserPassword() {
		return opensimUserPassword;
	}

	public void setOpensimUserPassword(String opensimUserPassword) {
		this.opensimUserPassword = opensimUserPassword;
	}

	public String getOpensimUserFirst() {
		return opensimUserFirst;
	}

	public void setOpensimUserFirst(String opensimUserFirst) {
		this.opensimUserFirst = opensimUserFirst;
	}

	public String getOpensimUserLast() {
		return opensimUserLast;
	}

	public void setOpensimUserLast(String opensimUserLast) {
		this.opensimUserLast = opensimUserLast;
	}

	public int getOpensimRobustPublicPort() {
		return opensimRobustPublicPort;
	}

	public void setOpensimRobustPublicPort(int opensimRobustPublicPort) {
		this.opensimRobustPublicPort = opensimRobustPublicPort;
	}

	public int getOpensimRobustPrivatePort() {
		return opensimRobustPrivatePort;
	}

	public void setOpensimRobustPrivatePort(int opensimRobustPrivatePort) {
		this.opensimRobustPrivatePort = opensimRobustPrivatePort;
	}

	public String getOpensimGridName() {
		return opensimGridName;
	}

	public void setOpensimGridName(String opensimGridName) {
		this.opensimGridName = opensimGridName;
	}

	public String getOpensimGridNick() {
		return opensimGridNick;
	}

	public void setOpensimGridNick(String opensimGridNick) {
		this.opensimGridNick = opensimGridNick;
	}

	public String getOpensimWelcomeMessage() {
		return opensimWelcomeMessage;
	}

	public void setOpensimWelcomeMessage(String opensimWelcomeMessage) {
		this.opensimWelcomeMessage = opensimWelcomeMessage;
	}

	public String getOpensimPullPolicy() {
		return opensimPullPolicy;
	}

	public void setOpensimPullPolicy(String opensimPullPolicy) {
		this.opensimPullPolicy = opensimPullPolicy;
	}

	public String getOpensimRestartPolicy() {
		return opensimRestartPolicy;
	}

	public void setOpensimRestartPolicy(String opensimRestartPolicy) {
		this.opensimRestartPolicy = opensimRestartPolicy;
	}

	public String getOpensimNetwork() {
		return opensimNetwork;
	}

	public void setOpensimNetwork(String opensimNetwork) {
		this.opensimNetwork = opensimNetwork;
	}

	public String getComposeProjectName() {
		return composeProjectName;
	}

	public void setComposeProjectName(String composeProjectName) {
		this.composeProjectName = composeProjectName == null ? "" : composeProjectName.trim();
	}

	public int getOpensimMaxBots() {
		return opensimMaxBots;
	}

	public void setOpensimMaxBots(int opensimMaxBots) {
		this.opensimMaxBots = opensimMaxBots;
	}

	public int getOpensimMaxSimulators() {
		return opensimMaxSimulators;
	}

	public void setOpensimMaxSimulators(int opensimMaxSimulators) {
		this.opensimMaxSimulators = opensimMaxSimulators;
	}

	public boolean isOpensimCreateBotUser() {
		return opensimCreateBotUser;
	}

	public void setOpensimCreateBotUser(boolean opensimCreateBotUser) {
		this.opensimCreateBotUser = opensimCreateBotUser;
	}

	public String getOpensimBotFirst() {
		return opensimBotFirst;
	}

	public void setOpensimBotFirst(String opensimBotFirst) {
		this.opensimBotFirst = opensimBotFirst;
	}

	public String getOpensimBotLast() {
		return opensimBotLast;
	}

	public void setOpensimBotLast(String opensimBotLast) {
		this.opensimBotLast = opensimBotLast;
	}

	public String getOpensimBotEmail() {
		return opensimBotEmail;
	}

	public void setOpensimBotEmail(String opensimLoginEmail) {
		this.opensimBotEmail = opensimLoginEmail;
	}

	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token == null ? "" : token;
	}

	public int getFirstPort() {
		return firstPort;
	}

	public void setFirstPort(int firstPort) {
		this.firstPort = firstPort;
	}

	public String getMetaverse2mcpImage() {
		return metaverse2mcpImage;
	}

	public void setMetaverse2mcpImage(String metaverse2mcpImage) {
		this.metaverse2mcpImage = metaverse2mcpImage;
	}

	public String getOpencodeImage() {
		return opencodeImage;
	}

	public void setOpencodeImage(String opencodeImage) {
		this.opencodeImage = opencodeImage;
	}

	public String getOpensimHandlerConfig() {
		return opensimHandlerConfig;
	}

	public void setOpensimHandlerConfig(String opensimHandlerConfig) {
		this.opensimHandlerConfig = opensimHandlerConfig;
	}

	public String getOpensimConsoleUser() {
		return opensimConsoleUser;
	}

	public void setOpensimConsoleUser(String opensimConsoleUser) {
		this.opensimConsoleUser = opensimConsoleUser;
	}

	public String getOpensimConsolePass() {
		return opensimConsolePass;
	}

	public void setOpensimConsolePass(String opensimConsolePass) {
		this.opensimConsolePass = opensimConsolePass;
	}

	public Path getConfigDir() {
		return configDir;
	}

	public void setConfigDir(Path configDir) {
		this.configDir = configDir;
	}

	public Path getDataDir() {
		return dataDir;
	}

	public void setDataDir(Path dataDir) {
		this.dataDir = dataDir;
	}

	public Path getWorkspaceDir() {
		return workspaceDir;
	}

	public void setWorkspaceDir(Path workspaceDir) {
		this.workspaceDir = workspaceDir;
	}
	
	public Map<String, String> buildVariables() {
		var map = new HashMap<String, String>();
		map.put("cfg.createBotUser", String.valueOf(opensimCreateBotUser));
		map.put("cfg.token", token);
		map.put("cfg.metaverse2mcpImage", metaverse2mcpImage);
		map.put("cfg.opencodeImage", opencodeImage);
		map.put("cfg.handlerConfig", opensimHandlerConfig);
		map.put("cfg.gridName", opensimGridName);
		map.put("cfg.gridNick", opensimGridNick);
		map.put("cfg.estateName", opensimEstateName);
		map.put("cfg.estateArchive", opensimEstateArchive);
		map.put("cfg.welcomeMessage", opensimWelcomeMessage);
		map.put("cfg.consoleUser", opensimConsoleUser);
		map.put("cfg.consolePass", opensimConsolePass);
		map.put("cfg.botFirst", opensimBotFirst);
		map.put("cfg.botLast", opensimBotLast);
		map.put("cfg.botEmail", opensimBotEmail);
		map.put("cfg.botAppearance", opensimBotAppearance);
		map.put("cfg.botGender", opensimBotGender);
		map.put("cfg.pullPolicy", opensimPullPolicy);
		map.put("cfg.restartPolicy", opensimRestartPolicy);
		map.put("cfg.network", opensimNetwork);
		map.put("cfg.composeProjectName", composeProjectName);
		map.put("cfg.userEmail", opensimUserEmail);
		map.put("cfg.userPassword", opensimUserPassword);
		map.put("cfg.userFirst", opensimUserFirst);
		map.put("cfg.userLast", opensimUserLast);
		map.put("cfg.provisionMode", opensimProvisionMode);
		map.put("cfg.gridServices", opensimGridServices);
		map.put("cfg.addOnsDir", addOnsDir.toAbsolutePath().normalize().toString());
		map.put("cfg.addOnsRepository", addOnsRepository);
		map.put("cfg.addOnsRefreshAtStartup", String.valueOf(addOnsRefreshAtStartup));
		map.put("cfg.configDir", configDir.toAbsolutePath().normalize().toString());
		map.put("cfg.dataDir", dataDir.toAbsolutePath().normalize().toString());
		map.put("cfg.workspaceDir", workspaceDir.toAbsolutePath().normalize().toString());
		map.put("cfg.firstPort", String.valueOf(firstPort));
		map.put("cfg.regionX", String.valueOf(opensimRegionX));
		map.put("cfg.regionY", String.valueOf(opensimRegionY));
		map.put("cfg.maxBots", String.valueOf(opensimMaxBots));
		map.put("cfg.maxSimulators", String.valueOf(opensimMaxSimulators));
		map.put("cfg.robustPublicPort", String.valueOf(opensimRobustPublicPort));	
		map.put("cfg.robustPrivatePort", String.valueOf(opensimRobustPrivatePort));
		return map;
	}
}
