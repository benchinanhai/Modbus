package com.example.gateway.ws;

import com.example.gateway.model.Dtos.CommandDto;
import com.example.gateway.model.Dtos.DeviceDto;
import com.example.gateway.model.Dtos.StatsDto;
import com.example.gateway.model.LogEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 遥测、日志与指令的 WebSocket 广播器。
 *
 * <p>采集线程只负责调用 broadcast，不关心有多少浏览器在线；没有连接时直接返回，
 * 任何发送失败都不会反向影响采集。</p>
 *
 * @author xujin
 * @since 0.1.0
 */
@Component
public class TelemetrySocket extends TextWebSocketHandler {
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ObjectMapper mapper;

    public TelemetrySocket(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        send(session, Map.of("type", "connected", "ts", System.currentTimeMillis()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // The console is read-only over this socket; commands go through REST.
    }

    public void broadcastTelemetry(DeviceDto device, StatsDto stats, String lane) {
        broadcast(Map.of(
                "type", "telemetry",
                "device", device,
                "stats", stats,
                "lane", lane,
                "ts", System.currentTimeMillis()
        ));
    }

    public void broadcastLog(LogEntry entry) {
        broadcast(Map.of("type", "log", "data", entry));
    }

    public void broadcastCommand(CommandDto command) {
        broadcast(Map.of("type", "command", "data", command));
    }

    private void broadcast(Object payload) {
        if (sessions.isEmpty()) {
            return;
        }
        try {
            TextMessage message = new TextMessage(mapper.writeValueAsString(payload));
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(message);
                    } catch (IOException ignored) {
                        sessions.remove(session);
                    }
                }
            }
        } catch (Exception ignored) {
            // A dead console must never break acquisition.
        }
    }

    private void send(WebSocketSession session, Object payload) throws IOException {
        session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
    }
}
