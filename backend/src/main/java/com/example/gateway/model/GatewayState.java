package com.example.gateway.model;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 网关运行时状态：一条通道对应一个 IO 线程，队列深度是背压的直接信号。
 *
 * @author xujin
 * @since 0.1.0
 */
public class GatewayState {
    public final String id;
    public final String name;
    public final String transport;
    public final String endpoint;
    public final String threadName;
    public final int cycleMs;
    public final List<DeviceState> devices = new CopyOnWriteArrayList<>();

    public volatile int queueDepth = 0;
    public volatile int utilization = 0;
    /** 是否为真实设备网关；模拟调度器必须跳过它。 */
    public volatile boolean real = false;

    public GatewayState(String id, String name, String transport, String endpoint, String threadName, int cycleMs) {
        this.id = id;
        this.name = name;
        this.transport = transport;
        this.endpoint = endpoint;
        this.threadName = threadName;
        this.cycleMs = cycleMs;
    }
}
