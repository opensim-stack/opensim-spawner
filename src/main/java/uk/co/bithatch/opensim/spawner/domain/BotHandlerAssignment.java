package uk.co.bithatch.opensim.spawner.domain;

public class BotHandlerAssignment implements DomainObject {

    private String botFirst;
    private String botLast;
    private String handlerFirst;
    private String handlerLast;

    public String getBotFirst() {
        return botFirst;
    }

    public void setBotFirst(String botFirst) {
        this.botFirst = botFirst;
    }

    public String getBotLast() {
        return botLast;
    }

    public void setBotLast(String botLast) {
        this.botLast = botLast;
    }

    public String getHandlerFirst() {
        return handlerFirst;
    }

    public void setHandlerFirst(String handlerFirst) {
        this.handlerFirst = handlerFirst;
    }

    public String getHandlerLast() {
        return handlerLast;
    }

    public void setHandlerLast(String handlerLast) {
        this.handlerLast = handlerLast;
    }

    @Override
    public String getName() {
        return botFirst + "-" + botLast + "-" + handlerFirst + "-" + handlerLast;
    }
}
