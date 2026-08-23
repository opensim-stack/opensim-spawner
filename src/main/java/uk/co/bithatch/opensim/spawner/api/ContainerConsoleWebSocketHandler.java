package uk.co.bithatch.opensim.spawner.api;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

@Component
public class ContainerConsoleWebSocketHandler extends TextWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ContainerConsoleWebSocketHandler.class);

    private final ObjectMapper objectMapper;
    private final DockerClient dockerClient;
    private final Map<String, ConsoleSessionState> sessions = new ConcurrentHashMap<>();

    public ContainerConsoleWebSocketHandler(ObjectMapper objectMapper) {
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

        sessions.put(session.getId(), new ConsoleSessionState(container.trim()));
        sendMessage(session, "info", "Console connected.");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        var state = sessions.get(session.getId());
        if (state == null) {
            return;
        }

        var payload = objectMapper.readValue(message.getPayload(), new TypeReference<Map<String, Object>>() {
        });
        var type = stringValue(payload.get("type"));
        if (type == null) {
            return;
        }

        switch (type) {
            case "start" -> startShell(session, state, payload);
            case "input" -> writeInput(state, stringValue(payload.get("data")));
            case "resize" -> resizeExec(state, intValue(payload.get("cols")), intValue(payload.get("rows")));
            default -> sendMessage(session, "error", "Unsupported message type: " + type);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var state = sessions.remove(session.getId());
        closeExec(state);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        LOG.warn("WebSocket transport error for console session {}.", session.getId(), exception);
        var state = sessions.remove(session.getId());
        closeExec(state);
    }

    @PreDestroy
    public void shutdown() {
        for (var state : sessions.values()) {
            closeExec(state);
        }
        sessions.clear();
        try {
            dockerClient.close();
        } catch (IOException e) {
            LOG.warn("Failed to close Docker client used by console websocket handler.", e);
        }
    }

    private void startShell(WebSocketSession session, ConsoleSessionState state, Map<String, Object> payload) throws IOException {
        closeExec(state);

        var command = stringValue(payload.get("command"));
        if (command == null || command.isBlank()) {
            command = "/bin/bash";
        }

        var containerName = state.containerName();
        try {
            dockerClient.inspectContainerCmd(containerName).exec();
        } catch (NotFoundException e) {
            sendMessage(session, "error", "Container not found: " + containerName);
            return;
        } catch (RuntimeException e) {
            sendMessage(session, "error", "Failed to inspect container: " + e.getMessage());
            return;
        }

        var stdinPipe = new PipedOutputStream();
        var stdinStream = new PipedInputStream(stdinPipe, 64 * 1024);

        try {
            var execId = dockerClient.execCreateCmd(containerName)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withAttachStdin(true)
                    .withTty(true)
                    .withCmd("/bin/sh", "-lc", command)
                    .exec()
                    .getId();

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
                        LOG.debug("Failed to forward console output to websocket session {}.", session.getId(), e);
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    try {
                        sendMessage(session, "error", "Console stream ended: " + throwable.getMessage());
                    } catch (IOException ignored) {
                        // Best effort.
                    }
                }

                @Override
                public void onComplete() {
                    try {
                        sendMessage(session, "info", "Console process completed.");
                    } catch (IOException ignored) {
                        // Best effort.
                    }
                }
            };

            dockerClient.execStartCmd(execId)
                    .withTty(true)
                    .withDetach(false)
                    .withStdIn(stdinStream)
                    .exec(callback);

            state.setExec(execId, stdinPipe, stdinStream, callback);
            resizeExec(state, intValue(payload.get("cols")), intValue(payload.get("rows")));
            sendMessage(session, "started", "Attached to " + containerName + " with command: " + command);
        } catch (RuntimeException e) {
            closeQuietly(stdinPipe);
            closeQuietly(stdinStream);
            sendMessage(session, "error", "Failed to start shell: " + e.getMessage());
        }
    }

    private void writeInput(ConsoleSessionState state, String data) {
        if (data == null) {
            return;
        }
        var stdinPipe = state.stdinPipe();
        if (stdinPipe == null) {
            return;
        }
        try {
            stdinPipe.write(data.getBytes(StandardCharsets.UTF_8));
            stdinPipe.flush();
        } catch (IOException e) {
            LOG.debug("Failed to write input to exec stream for container {}.", state.containerName(), e);
        }
    }

    private void resizeExec(ConsoleSessionState state, Integer cols, Integer rows) {
        if (state.execId() == null || cols == null || rows == null || cols < 2 || rows < 2) {
            return;
        }

        try {
            dockerClient.resizeExecCmd(state.execId())
                    .withSize(rows, cols)
                    .exec();
        } catch (RuntimeException e) {
            LOG.debug("Failed to resize exec {} to {}x{}.", state.execId(), cols, rows, e);
        }
    }

    private void closeExec(ConsoleSessionState state) {
        if (state == null) {
            return;
        }

        var callback = state.callback();
        var stdinPipe = state.stdinPipe();
        var stdinStream = state.stdinStream();
        state.clearExec();

        if (callback != null) {
            try {
                callback.close();
            } catch (IOException ignored) {
                // Best effort.
            }
        }
        closeQuietly(stdinPipe);
        closeQuietly(stdinStream);
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

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        var text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }

    private static Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static DockerClient buildDockerClient() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        return DockerClientBuilder.getInstance(config).withDockerHttpClient(httpClient).build();
    }

    private static final class ConsoleSessionState {
        private final String containerName;
        private String execId;
        private PipedOutputStream stdinPipe;
        private PipedInputStream stdinStream;
        private ResultCallback.Adapter<Frame> callback;

        private ConsoleSessionState(String containerName) {
            this.containerName = containerName;
        }

        String containerName() {
            return containerName;
        }

        synchronized String execId() {
            return execId;
        }

        synchronized PipedOutputStream stdinPipe() {
            return stdinPipe;
        }

        synchronized PipedInputStream stdinStream() {
            return stdinStream;
        }

        synchronized ResultCallback.Adapter<Frame> callback() {
            return callback;
        }

        synchronized void setExec(String execId,
                PipedOutputStream stdinPipe,
                PipedInputStream stdinStream,
                ResultCallback.Adapter<Frame> callback) {
            this.execId = execId;
            this.stdinPipe = stdinPipe;
            this.stdinStream = stdinStream;
            this.callback = callback;
        }

        synchronized void clearExec() {
            this.execId = null;
            this.stdinPipe = null;
            this.stdinStream = null;
            this.callback = null;
        }
    }
}