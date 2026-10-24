package com.example.gateway.store;

import com.example.gateway.model.DeviceState;
import com.example.gateway.model.Dtos.CommandDto;
import com.example.gateway.model.Dtos.DeviceDto;
import com.example.gateway.model.Dtos.GatewayDto;
import com.example.gateway.model.Dtos.LaneDto;
import com.example.gateway.model.Dtos.PointValueDto;
import com.example.gateway.model.Dtos.StatsDto;
import com.example.gateway.model.Dtos.ThreadsDto;
import com.example.gateway.model.GatewayState;
import com.example.gateway.model.LogEntry;
import com.example.gateway.model.PointConfig;
import com.example.gateway.model.ThreadOp;
import com.example.gateway.ws.TelemetrySocket;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行时状态仓库：网关、设备、历史曲线、日志、线程事务与指令审计。
 *
 * <p>模拟设备与真实设备共用这里的状态；真实设备的配置由 RealDeviceService
 * 注册进来。所有集合都考虑并发读写：设备表用 ConcurrentHashMap，网关表用
 * synchronized 快照，日志/事务用双端队列加锁。</p>
 *
 * @author xujin
 * @since 0.1.0
 */
@Component
public class GatewayStore {
    private static final int RTU1_COUNT = 12;
    private static final int RTU2_COUNT = 20;
    private static final double TEMP_SEED_BASE = 22;
    private static final double TEMP_SEED_STEP = 1.7;
    private static final double TEMP_SEED_RANDOM = 2;
    private static final double HUM_SEED_BASE = 45;
    private static final double HUM_SEED_STEP = 2;
    private static final double HUM_SEED_RANDOM = 5;
    private static final double BAD_PROBABILITY = 0.0025;
    private static final int BAD_TICKS = 3;
    private static final double INFO_LOG_PROBABILITY = 0.03;
    private static final int MAX_LOG_SIZE = 500;
    private static final int MAX_OP_SIZE = 300;
    private static final int MAX_COMMAND_SIZE = 50;
    private static final int MAX_LOG_QUERY = 200;
    private static final int THREAD_WINDOW_MS = 4500;
    private static final int POINTS_PER_DEVICE = 4;
    private static final int P95_BASE_MS = 11;
    private static final int P95_JITTER_MS = 7;
    private static final int P95_BAD_PENALTY_MS = 4;
    private static final int UTIL_BASE = 12;
    private static final int UTIL_QUEUE_FACTOR = 7;
    private static final int UTIL_JITTER = 6;
    private static final int UTIL_MAX = 95;

    private static final String[] NAMES = {
            "冷库温度", "冷冻水回水", "车间环境", "烘箱温度", "注塑机料筒", "配电柜温度",
            "空压机出口", "循环水温", "仓库环境", "老化房温度", "冷却塔", "实验室温度",
            "机房温度", "档案室", "食堂冷柜", "热水罐", "蒸汽管道", "污水处理",
            "锅炉出口", "包装线", "原料仓", "成品仓", "走廊环境", "屋顶气象"
    };

    private final TelemetrySocket socket;
    private final Map<String, GatewayState> gateways = new LinkedHashMap<>();
    private final Map<String, DeviceState> devices = new ConcurrentHashMap<>();
    private final Deque<LogEntry> logs = new ArrayDeque<>();
    private final Map<String, Deque<ThreadOp>> ops = new ConcurrentHashMap<>();
    private final Deque<CommandDto> commands = new ArrayDeque<>();

    private final AtomicLong logSeq = new AtomicLong();
    private final AtomicLong opSeq = new AtomicLong();
    private final AtomicLong commandSeq = new AtomicLong();
    private final AtomicLong okPolls = new AtomicLong(4800);
    private final AtomicLong totalPolls = new AtomicLong(4820);

    public GatewayStore(TelemetrySocket socket) {
        this.socket = socket;
        seed();
    }

    private void seed() {
        GatewayState g1 = new GatewayState("GW-01", "一号车间", "RTU", "COM3 · 9600 8N1", "io-com3", 1000);
        GatewayState g2 = new GatewayState("GW-02", "二号车间", "RTU", "COM4 · 19200 8N1", "io-com4", 1000);
        GatewayState g3 = new GatewayState("GW-03", "办公楼", "TCP", "10.0.3.15:502", "io-tcp", 500);
        gateways.put(g1.id, g1);
        gateways.put(g2.id, g2);
        gateways.put(g3.id, g3);

        for (int i = 0; i < NAMES.length; i++) {
            GatewayState gateway = i < RTU1_COUNT ? g1 : (i < RTU2_COUNT ? g2 : g3);
            int offset = i < RTU1_COUNT ? 0 : (i < RTU2_COUNT ? RTU1_COUNT : RTU2_COUNT);
            String id = String.format("T-%02d", i + 1);
            String serial = String.format("DEV-CN-SH-2026-%02d-7F3C9B2E-%018d", i + 1, 42L + i);
            double temp = TEMP_SEED_BASE + (i % 5) * TEMP_SEED_STEP
                    + ThreadLocalRandom.current().nextDouble() * TEMP_SEED_RANDOM;
            double hum = HUM_SEED_BASE + (i % 7) * HUM_SEED_STEP
                    + ThreadLocalRandom.current().nextDouble() * HUM_SEED_RANDOM;
            DeviceState device = new DeviceState(id, NAMES[i], gateway.id, i - offset + 1,
                    "MB-TH-" + (100 + i), serial, temp, hum);
            device.transport = gateway.transport;
            device.endpoint = gateway.endpoint;
            device.slaveUnitId = i + 1;
            device.setPoints(PointConfig.defaults());
            device.putPointValue("temp", temp);
            device.putPointValue("hum", hum);
            device.syncConvenience();
            gateway.devices.add(device);
            devices.put(id, device);
        }

        addLog("INFO", "GW-01", "channel online, 12 slaves");
        addLog("INFO", "T-01", "poll 0x0000 ok=30.5 q=good");
        addLog("INFO", "GW-03", "tcp reconnect ok, 4 slaves");
        addLog("WARN", "T-09", "inter-frame gap 1.2 char, retry");
        addLog("ERROR", "T-12", "exception 0x02 illegal data address");
    }

    public synchronized List<GatewayState> gateways() {
        return new ArrayList<>(gateways.values());
    }

    public List<DeviceState> devices() {
        return new ArrayList<>(devices.values());
    }

    public DeviceState device(String id) {
        return devices.get(id);
    }

    public synchronized GatewayState gateway(String id) {
        return gateways.get(id);
    }

    /**
     * 注册一个运行时网关。真实设备接入时按端点动态创建。
     */
    public synchronized void addGateway(GatewayState gateway) {
        gateways.put(gateway.id, gateway);
    }

    /**
     * 注册一个运行时设备，并挂到对应网关下。
     */
    public void addDevice(DeviceState device) {
        devices.put(device.id, device);
        GatewayState gateway = gateway(device.gatewayId);
        if (gateway != null && !gateway.devices.contains(device)) {
            gateway.devices.add(device);
        }
    }

    /**
     * 移除真实设备。模拟设备不应调用此方法。
     */
    public void removeDevice(String id) {
        DeviceState device = devices.remove(id);
        if (device == null) {
            return;
        }
        GatewayState gateway = gateway(device.gatewayId);
        if (gateway != null) {
            gateway.devices.remove(device);
        }
    }

    public synchronized void removeGateway(String id) {
        gateways.remove(id);
    }

    public boolean poll(DeviceState device) {
        totalPolls.incrementAndGet();
        boolean ok = true;
        synchronized (device) {
            if (device.badTicks > 0) {
                device.badTicks--;
                ok = false;
                if (device.badTicks == 0) {
                    device.quality = "good";
                    addLog("INFO", device.id, "recovered, quality=good");
                }
            } else if (ThreadLocalRandom.current().nextDouble() < BAD_PROBABILITY) {
                device.quality = "bad";
                device.badTicks = BAD_TICKS;
                ok = false;
                addLog("WARN", device.id, "read timeout, quality=bad");
            } else {
                device.simulateValues();
                device.lastSeen = System.currentTimeMillis();
                device.addHistory(device.trendValue());
            }
        }
        if (ok) {
            okPolls.incrementAndGet();
            if (ThreadLocalRandom.current().nextDouble() < INFO_LOG_PROBABILITY) {
                addLog("INFO", device.id, String.format(Locale.ROOT, "poll ok=%.1f q=%s",
                        device.trendValue(), device.quality));
            }
        }
        return ok;
    }

    public List<Double> history(String id) {
        DeviceState device = devices.get(id);
        return device == null ? List.of() : device.historySnapshot();
    }

    /**
     * 记录一次真实设备轮询结果，供成功率指标使用。
     */
    public void recordPoll(boolean success) {
        totalPolls.incrementAndGet();
        if (success) {
            okPolls.incrementAndGet();
        }
    }

    public DeviceDto deviceDto(DeviceState device) {
        List<PointValueDto> pointDtos = new ArrayList<>();
        for (PointConfig point : device.getPoints()) {
            pointDtos.add(new PointValueDto(point.key, point.name, point.address, point.type,
                    point.unit, round(device.value(point.key)), point.writable, point.function,
                    point.registerCount()));
        }
        return new DeviceDto(device.id, device.name, device.gatewayId, device.slaveId, device.model,
                round(device.temperature), round(device.humidity), round(device.current),
                device.quality, device.lastSeen, device.serial, device.setpoint,
                device.alarmLow, device.alarmHigh, device.historySnapshot(),
                device.real, device.transport, device.endpoint, device.slaveUnitId, pointDtos);
    }

    public List<DeviceDto> deviceDtos() {
        List<DeviceDto> list = new ArrayList<>();
        for (DeviceState device : devices.values()) {
            list.add(deviceDto(device));
        }
        list.sort(Comparator.comparing(DeviceDto::id));
        return list;
    }

    public List<GatewayDto> gatewayDtos() {
        List<GatewayDto> list = new ArrayList<>();
        for (GatewayState gateway : gateways()) {
            List<DeviceDto> deviceList = new ArrayList<>();
            for (DeviceState device : gateway.devices) {
                deviceList.add(deviceDto(device));
            }
            int utilization = Math.min(UTIL_MAX,
                    UTIL_BASE + gateway.queueDepth * UTIL_QUEUE_FACTOR
                            + ThreadLocalRandom.current().nextInt(UTIL_JITTER));
            gateway.utilization = utilization;
            list.add(new GatewayDto(gateway.id, gateway.name, gateway.transport, gateway.endpoint,
                    gateway.cycleMs, gateway.queueDepth, utilization, gateway.threadName,
                    deviceList.size(), deviceList, gateway.real));
        }
        return list;
    }

    public StatsDto stats() {
        long total = totalPolls.get();
        long ok = okPolls.get();
        double rate = total == 0 ? 100.0 : Math.round(ok * 1000.0 / total) / 10.0;
        long bad = devices.values().stream().filter(d -> !"good".equals(d.quality)).count();
        int p95 = P95_BASE_MS + ThreadLocalRandom.current().nextInt(P95_JITTER_MS)
                + (bad > 0 ? P95_BAD_PENALTY_MS : 0);
        return new StatsDto(devices.size(), devices.size() * POINTS_PER_DEVICE, rate, p95,
                devices.size() * POINTS_PER_DEVICE);
    }

    public List<LogEntry> recentEvents(int limit) {
        List<LogEntry> warnings = new ArrayList<>();
        List<LogEntry> infos = new ArrayList<>();
        synchronized (logs) {
            var it = logs.descendingIterator();
            while (it.hasNext() && warnings.size() < limit) {
                LogEntry entry = it.next();
                if ("WARN".equals(entry.level()) || "ERROR".equals(entry.level())) {
                    warnings.add(entry);
                } else if (infos.size() < limit) {
                    infos.add(entry);
                }
            }
        }
        for (LogEntry info : infos) {
            if (warnings.size() >= limit) {
                break;
            }
            warnings.add(info);
        }
        return warnings;
    }

    public List<LogEntry> logs(String level, String query) {
        List<LogEntry> list = new ArrayList<>();
        String q = query == null ? "" : query.trim().toLowerCase();
        synchronized (logs) {
            var it = logs.descendingIterator();
            while (it.hasNext() && list.size() < MAX_LOG_QUERY) {
                LogEntry entry = it.next();
                if (level != null && !level.isBlank() && !"ALL".equalsIgnoreCase(level)
                        && !entry.level().equalsIgnoreCase(level)) {
                    continue;
                }
                if (!q.isEmpty()) {
                    String haystack = (entry.source() + " " + entry.message() + " " + entry.traceId()).toLowerCase();
                    if (!haystack.contains(q)) {
                        continue;
                    }
                }
                list.add(entry);
            }
        }
        return list;
    }

    public LogEntry addLog(String level, String source, String message) {
        return addLog(level, source, message, nextTrace());
    }

    public LogEntry addLog(String level, String source, String message, String traceId) {
        LogEntry entry = new LogEntry(logSeq.incrementAndGet(), System.currentTimeMillis(),
                level, source, message, traceId);
        synchronized (logs) {
            logs.addLast(entry);
            if (logs.size() > MAX_LOG_SIZE) {
                logs.removeFirst();
            }
        }
        socket.broadcastLog(entry);
        return entry;
    }

    public void addOp(ThreadOp op) {
        Deque<ThreadOp> deque = ops.computeIfAbsent(op.lane(), key -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(op);
            if (deque.size() > MAX_OP_SIZE) {
                deque.removeFirst();
            }
        }
    }

    public long nextOpId() {
        return opSeq.incrementAndGet();
    }

    public ThreadsDto threads() {
        long now = System.currentTimeMillis();
        List<LaneDto> lanes = new ArrayList<>();
        for (GatewayState gateway : gateways()) {
            Deque<ThreadOp> deque = ops.getOrDefault(gateway.threadName, new ArrayDeque<>());
            List<ThreadOp> recent = new ArrayList<>();
            synchronized (deque) {
                for (ThreadOp op : deque) {
                    if (op.start() > now - THREAD_WINDOW_MS) {
                        recent.add(op);
                    }
                }
            }
            String state = "WAITING";
            if (!recent.isEmpty() && recent.get(recent.size() - 1).start() > now - 1200) {
                state = "RUNNABLE";
            }
            lanes.add(new LaneDto(gateway.threadName, gateway.id, gateway.threadName, state,
                    gateway.queueDepth, recent));
        }
        return new ThreadsDto(System.currentTimeMillis(), lanes);
    }

    public void addCommand(CommandDto command) {
        synchronized (commands) {
            commands.addFirst(command);
            if (commands.size() > MAX_COMMAND_SIZE) {
                commands.removeLast();
            }
        }
    }

    public List<CommandDto> commands() {
        synchronized (commands) {
            return new ArrayList<>(commands);
        }
    }

    public String nextTrace() {
        return "tr-" + Long.toHexString(ThreadLocalRandom.current().nextLong(0xFFFFFFFFFFL));
    }

    public long nextCommandId() {
        return commandSeq.incrementAndGet();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
