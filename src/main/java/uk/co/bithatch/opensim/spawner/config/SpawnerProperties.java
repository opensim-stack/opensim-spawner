package uk.co.bithatch.opensim.spawner.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spawner")
public class SpawnerProperties {

    private String token = "";
    private int firstPort = 12345;
    private String metaverse2mcpImage = "bithatch/opensim-metaverse2mcp:latest";
    private String opencodeImage = "bithatch/opensim-opencode:latest";
    private String openCodeHandlerFirstname = "";
    private String openCodeHandlerLastname = "";

    private String opensimConsoleUrl = "http://opensim:9000";
    private String opensimConsoleUser;
    private String opensimConsolePass;
    private boolean opensimCreateBotUser = true;
    private String opensimLoginFirstname = "Bot";
    private String opensimLoginLastname = "User";
    private String opensimLoginEmail = "bot@localhost";
    private String opensimLoginModel = "Ruth";
    private int opensimMaxBots = 10;
    private String opensimPullPolicy = "IfNotPresent";
    private String opensimRestartPolicy = "unless-stopped";
    private String opensimNetwork = "opensim-ai-docker_default";
    private String composeProjectName = "";

    private Path configDir = Path.of("/config");
    private Path dataDir = Path.of("/data");
    private Path workspaceDir = Path.of("/workspace");

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

	public boolean isOpensimCreateBotUser() {
		return opensimCreateBotUser;
	}

	public void setOpensimCreateBotUser(boolean opensimCreateBotUser) {
		this.opensimCreateBotUser = opensimCreateBotUser;
	}

	public String getOpensimLoginFirstname() {
		return opensimLoginFirstname;
	}

	public void setOpensimLoginFirstname(String opensimLoginFirstname) {
		this.opensimLoginFirstname = opensimLoginFirstname;
	}

	public String getOpensimLoginLastname() {
		return opensimLoginLastname;
	}

	public void setOpensimLoginLastname(String opensimLoginLastname) {
		this.opensimLoginLastname = opensimLoginLastname;
	}

	public String getOpensimLoginEmail() {
		return opensimLoginEmail;
	}

	public void setOpensimLoginEmail(String opensimLoginEmail) {
		this.opensimLoginEmail = opensimLoginEmail;
	}

	public String getOpensimLoginModel() {
		return opensimLoginModel;
	}

	public void setOpensimLoginModel(String opensimLoginModel) {
		this.opensimLoginModel = opensimLoginModel;
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

    public String getOpenCodeHandlerFirstname() {
        return openCodeHandlerFirstname;
    }

    public void setOpenCodeHandlerFirstname(String openCodeHandlerFirstname) {
        this.openCodeHandlerFirstname = openCodeHandlerFirstname;
    }

    public String getOpenCodeHandlerLastname() {
        return openCodeHandlerLastname;
    }

    public void setOpenCodeHandlerLastname(String openCodeHandlerLastname) {
        this.openCodeHandlerLastname = openCodeHandlerLastname;
    }

    public String getOpensimConsoleUrl() {
        return opensimConsoleUrl;
    }

    public void setOpensimConsoleUrl(String opensimConsoleUrl) {
        this.opensimConsoleUrl = opensimConsoleUrl;
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
}
