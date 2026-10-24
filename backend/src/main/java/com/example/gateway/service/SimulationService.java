package com.example.gateway.service;

import com.example.gateway.model.DeviceState;
import com.example.gateway.model.GatewayState;
import com.example.gateway.store.GatewayStore;
import com.example.gateway.ws.TelemetrySocket;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 模拟采集调度器。
 *
 * <p>每秒向每个网关的通道投递一轮轮询任务。调度器只负责投递，不直接访问寄存器；
 * 真正的串行执行发生在 {@link ChannelRuntime} 的 worker 线程里。</p>
 *
 * @author xujin
 * @since 0.1.0
 */
@Service
public class SimulationService {
    private static final long INITIAL_DELAY_MS = 1000L;
    private static final long CYCLE_MS = 1000L;

    private final GatewayStore store;
    private final TelemetrySocket socket;
    private final Map<String, ChannelRuntime> channels = new LinkedHashMap<>();
    private final ScheduledThreadPoolExecutor scheduler;

    public SimulationService(GatewayStore store, TelemetrySocket socket) {
        this.store = store;
        this.socket = socket;
        for (GatewayState gateway : store.gateways()) {
            if (!gateway.real) {
                channels.put(gateway.id, new ChannelRuntime(gateway, store, socket));
            }
        }
        this.scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "poll-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.setRemoveOnCancelPolicy(true);
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleAtFixedRate(this::enqueuePolls, INITIAL_DELAY_MS, CYCLE_MS,
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
        channels.values().forEach(ChannelRuntime::shutdown);
    }

    public void enqueuePolls() {
        for (GatewayState gateway : store.gateways()) {
            // 真实网关由 RealDeviceService 自己调度，模拟调度器必须跳过，否则会重复采集。
            if (gateway.real) {
                continue;
            }
            ChannelRuntime channel = channels.get(gateway.id);
            if (channel == null) {
                continue;
            }
            try {
                for (DeviceState device : gateway.devices) {
                    channel.submit(new ChannelRuntime.PollTask(gateway, device, store, socket));
                }
            } catch (RuntimeException e) {
                // 单个网关入队失败不能终止整个 ScheduledExecutor。
                store.addLog("ERROR", gateway.id, "poll enqueue failed: " + e.getMessage());
            }
        }
    }

    public void submitWrite(DeviceState device, int address, int[] values, String traceId) {
        GatewayState gateway = store.gateway(device.gatewayId);
        ChannelRuntime channel = channels.get(gateway.id);
        if (channel != null) {
            channel.submit(new ChannelRuntime.WriteTask(gateway, device, address, values, traceId, store, socket));
        }
    }
}
