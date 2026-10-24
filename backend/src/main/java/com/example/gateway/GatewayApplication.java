package com.example.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Modbus 接入与运维平台启动入口。
 *
 * <p>一个进程内同时承载：模拟/真实设备采集、寄存器编组、指令下发、
 * WebSocket 推送与前端静态资源。</p>
 *
 * @author xujin
 * @since 0.1.0
 */
@SpringBootApplication
@EnableScheduling
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
