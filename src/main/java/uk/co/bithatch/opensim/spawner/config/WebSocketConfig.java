package uk.co.bithatch.opensim.spawner.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import uk.co.bithatch.opensim.spawner.api.ContainerConsoleWebSocketHandler;
import uk.co.bithatch.opensim.spawner.api.UiSessionHandshakeInterceptor;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ContainerConsoleWebSocketHandler consoleHandler;
    private final UiSessionHandshakeInterceptor uiSessionHandshakeInterceptor;

    public WebSocketConfig(ContainerConsoleWebSocketHandler consoleHandler,
            UiSessionHandshakeInterceptor uiSessionHandshakeInterceptor) {
        this.consoleHandler = consoleHandler;
        this.uiSessionHandshakeInterceptor = uiSessionHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(consoleHandler, "/ui/ws/console")
                .addInterceptors(uiSessionHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
