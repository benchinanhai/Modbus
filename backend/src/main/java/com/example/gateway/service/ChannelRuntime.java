package com.example.gateway.service;

import com.example.gateway.model.DeviceState;
import com.example.gateway.model.GatewayState;
import com.example.gateway.model.ThreadOp;
import com.example.gateway.store.GatewayStore;
import com.example.gateway.ws.TelemetrySocket;

import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一个通道 = 一个 worker 线程。
 *
 * <p>通道内严格串行，通道之间并行；写命令优先级 0，轮询 1。
 * 这是串口半双工的物理约束，不是实现选择。</p>
 *
 * @author xujin
 * @since 0.1.0
 */
public class ChannelRuntime {
    private static final int MAX_QUEUE_SIZE = 256;
    private static final int POLL_MIN_MS = 9;
    private static final int POLL_JITTER_MS = 9;
    private static final int WRITE_MS = 12;

    private final GatewayState gateway;
    private final GatewayStore store;
    private final TelemetrySocket socket;
    private final BlockingQueue<Task> queue =
            new PriorityBlockingQueue<>(MAX_QUEUE_SIZE, Comparator
                    .comparingInt((Task task) -> task.priority)
                    .thenComparingLong(task -> task.seq));
    private final Thread worker;
    private volatile boolean running = true;

    public ChannelRuntime(GatewayState gateway, GatewayStore store, TelemetrySocket socket) {
        this.gateway = gateway;
        this.store = store;
        this.socket = socket;
        this.worker = new Thread(this::loop, gateway.threadName);
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public void submit(Task task) {
        queue.offer(task);
        gateway.queueDepth = queue.size();
    }

    public int queueDepth() {
        return queue.size();
    }

    public void shutdown() {
        running = false;
        worker.interrupt();
    }

    private void loop() {
        while (running) {
            try {
                Task task = queue.take();
                gateway.queueDepth = queue.size();
                task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                store.addLog("ERROR", gateway.id, "channel task failed: " + e.getMessage());
            }
        }
    }

    public abstract static class Task implements Runnable {
        private static final AtomicLong SEQUENCE = new AtomicLong();
        final int priority;
        final long seq = SEQUENCE.incrementAndGet();

        protected Task(int priority) {
            this.priority = priority;
        }
    }

    public static class PollTask extends Task {
        private final GatewayState gateway;
        private final DeviceState device;
        private final GatewayStore store;
        private final TelemetrySocket socket;

        public PollTask(GatewayState gateway, DeviceState device, GatewayStore store, TelemetrySocket socket) {
            super(1);
            this.gateway = gateway;
            this.device = device;
            this.store = store;
            this.socket = socket;
        }

        @Override
        public void run() {
            long start = System.currentTimeMillis();
            double duration = POLL_MIN_MS + ThreadLocalRandom.current().nextDouble() * POLL_JITTER_MS;
            try {
                Thread.sleep((long) duration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            boolean ok = store.poll(device);
            long end = System.currentTimeMillis();
            store.addOp(new ThreadOp(store.nextOpId(), gateway.threadName, device.id,
                    start, end - start, "poll", !ok, 0));
            socket.broadcastTelemetry(store.deviceDto(device), store.stats(), gateway.threadName);
        }
    }

    public static class WriteTask extends Task {
        private final GatewayState gateway;
        private final DeviceState device;
        private final int address;
        private final int[] values;
        private final String traceId;
        private final GatewayStore store;
        private final TelemetrySocket socket;

        public WriteTask(GatewayState gateway, DeviceState device, int address, int[] values,
                         String traceId, GatewayStore store, TelemetrySocket socket) {
            super(0);
            this.gateway = gateway;
            this.device = device;
            this.address = address;
            this.values = values;
            this.traceId = traceId;
            this.store = store;
            this.socket = socket;
        }

        @Override
        public void run() {
            long start = System.currentTimeMillis();
            try {
                Thread.sleep(WRITE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (device) {
                if (address == 8 && values.length >= 1) {
                    device.setpoint = values[0];
                }
                if (address == 9 && values.length >= 2) {
                    device.alarmLow = values[0];
                    device.alarmHigh = values[1];
                }
            }
            long end = System.currentTimeMillis();
            store.addOp(new ThreadOp(store.nextOpId(), gateway.threadName, device.id,
                    start, end - start, "write", false, 0));
            store.addLog("INFO", device.id, "write addr=" + address + " values=" + Arrays.toString(values), traceId);
            socket.broadcastTelemetry(store.deviceDto(device), store.stats(), gateway.threadName);
        }
    }
}
