package uk.co.bithatch.opensim.spawner.domain;

public class UpdatesConfiguration {

    private String dockerHubUsername = "";
    private String dockerHubToken = "";
    private boolean automaticUpdates = true;
    private String tag = "latest";

    public String getDockerHubUsername() {
        return dockerHubUsername;
    }

    public void setDockerHubUsername(String dockerHubUsername) {
        this.dockerHubUsername = normalize(dockerHubUsername);
    }

    public String getDockerHubToken() {
        return dockerHubToken;
    }

    public void setDockerHubToken(String dockerHubToken) {
        this.dockerHubToken = normalize(dockerHubToken);
    }

    public boolean isAutomaticUpdates() {
        return automaticUpdates;
    }

    public void setAutomaticUpdates(boolean automaticUpdates) {
        this.automaticUpdates = automaticUpdates;
    }

    public String getTag() {
        return normalize(tag, "latest");
    }

    public void setTag(String tag) {
        this.tag = normalize(tag, "latest");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalize(String value, String fallback) {
        var normalized = normalize(value);
        return normalized.isEmpty() ? fallback : normalized;
    }
}
