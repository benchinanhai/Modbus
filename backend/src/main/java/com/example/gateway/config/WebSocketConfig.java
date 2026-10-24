package com.example.gateway.config;

import com.example.gateway.ws.TelemetrySocket;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 遥测 WebSocket 注册：前端通过 /ws/telemetry 接收设备、日志、指令事件。
 *
 * @author xujin
 * @since 0.1.0
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final TelemetrySocket telemetrySocket;

    public WebSocketConfig(TelemetrySocket telemetrySocket) {
        this.telemetrySocket = telemetrySocket;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(telemetrySocket, "/ws/telemetry")
                .setAllowedOriginPatterns("*");
    }
}
