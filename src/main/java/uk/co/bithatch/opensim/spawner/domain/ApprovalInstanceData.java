package uk.co.bithatch.opensim.spawner.domain;

public class ApprovalInstanceData extends ContainerGroupInstanceData<ApprovalLevel> {

    private String first;
    private String last;
    private String email;
    private String password;
    private long requestedAtEpochMillis;

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

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public long getRequestedAtEpochMillis() {
        return requestedAtEpochMillis;
    }

    public void setRequestedAtEpochMillis(long requestedAtEpochMillis) {
        this.requestedAtEpochMillis = requestedAtEpochMillis;
    }

    public String key() {
        return first + "-" + last;
    }

    @Override
    public String displayName() {
        return first + " " + last;
    }
}
