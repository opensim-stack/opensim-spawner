package uk.co.bithatch.opensim.spawner.api;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

@Component
public class ContainerLogsWebSocketHandler extends TextWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ContainerLogsWebSocketHandler.class);

    private final ObjectMapper objectMapper;
    private final DockerClient dockerClient;
    private final Map<String, LogSessionState> sessions = new ConcurrentHashMap<>();

    public ContainerLogsWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.dockerClient = buildDockerClient();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        var container = queryParam(session, "container");
        if (container == null || container.isBlank()) {
            sendMessage(session, "error", "Missing required query parameter: container.");
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        var containerName = container.trim();
        try {
            dockerClient.inspectContainerCmd(containerName).exec();
        } catch (NotFoundException e) {
            sendMessage(session, "error", "Container not found: " + containerName);
            session.close(CloseStatus.BAD_DATA);
            return;
        } catch (RuntimeException e) {
            sendMessage(session, "error", "Failed to inspect container: " + e.getMessage());
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }

        var callback = new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                var data = frame == null ? null : frame.getPayload();
                if (data == null || data.length == 0) {
                    return;
                }
                try {
                    sendOutput(session, new String(data, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    LOG.debug("Failed to forward log output to websocket session {}.", session.getId(), e);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                try {
                    sendMessage(session, "error", "Log stream ended: " + throwable.getMessage());
                } catch (IOException ignored) {
                    // Best effort.
                }
            }

            @Override
            public void onComplete() {
                try {
                    sendMessage(session, "info", "Log stream completed.");
                } catch (IOException ignored) {
                    // Best effort.
                }
            }
        };

        try {
            dockerClient.logContainerCmd(containerName)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .withTailAll()
                    .exec(callback);
            sessions.put(session.getId(), new LogSessionState(containerName, callback));
            sendMessage(session, "started", "Attached to logs for " + containerName + ".");
        } catch (RuntimeException e) {
            closeQuietly(callback);
            sendMessage(session, "error", "Failed to stream logs: " + e.getMessage());
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Log streaming is server-push only. Incoming messages are ignored.
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closeSession(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        LOG.warn("WebSocket transport error for logs session {}.", session.getId(), exception);
        closeSession(session.getId());
    }

    @PreDestroy
    public void shutdown() {
        for (var sessionId : sessions.keySet()) {
            closeSession(sessionId);
        }
        sessions.clear();
        try {
            dockerClient.close();
        } catch (IOException e) {
            LOG.warn("Failed to close Docker client used by logs websocket handler.", e);
        }
    }

    private void closeSession(String sessionId) {
        var state = sessions.remove(sessionId);
        if (state == null) {
            return;
        }
        closeQuietly(state.callback());
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best effort.
        }
    }

    private void sendOutput(WebSocketSession session, String data) throws IOException {
        if (!session.isOpen()) {
            return;
        }
        var message = objectMapper.writeValueAsString(Map.of("type", "output", "data", data));
        synchronized (session) {
            session.sendMessage(new TextMessage(message));
        }
    }

    private void sendMessage(WebSocketSession session, String type, String message) throws IOException {
        if (!session.isOpen()) {
            return;
        }
        var payload = objectMapper.writeValueAsString(Map.of("type", type, "message", message));
        synchronized (session) {
            session.sendMessage(new TextMessage(payload));
        }
    }

    private static String queryParam(WebSocketSession session, String name) {
        var uri = session.getUri();
        if (uri == null || uri.getRawQuery() == null) {
            return null;
        }
        for (var part : uri.getRawQuery().split("&")) {
            var idx = part.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            var key = URLDecoder.decode(part.substring(0, idx), StandardCharsets.UTF_8);
            if (!name.equals(key)) {
                continue;
            }
            return URLDecoder.decode(part.substring(idx + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static DockerClient buildDockerClient() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        return DockerClientBuilder.getInstance(config).withDockerHttpClient(httpClient).build();
    }

    private record LogSessionState(String containerName, ResultCallback.Adapter<Frame> callback) {
    }
}
