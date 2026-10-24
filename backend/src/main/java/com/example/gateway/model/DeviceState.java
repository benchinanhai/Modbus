package com.example.gateway.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 设备运行时状态。
 *
 * <p>点位值统一放在 {@link #pointValues}（key → 工程值），由点表
 * {@link PointConfig} 驱动；temperature/humidity/setpoint 等字段是
 * 常见点位的“便捷视图”，由 {@link #syncConvenience()} 从点表同步，
 * 保证旧接口（DTO 字段、写命令映射）继续可用。</p>
 *
 * <p>volatile 字段由通道线程写入、HTTP/WebSocket 线程读取。</p>
 *
 * @author xujin
 * @since 0.1.0
 */
public class DeviceState {
    private static final int SEED_HISTORY_SIZE = 48;
    private static final int MAX_HISTORY_SIZE = 60;
    private static final double SEED_NOISE = 0.5;

    public final String id;
    public final String name;
    public final String gatewayId;
    public final int slaveId;
    public final String model;
    public final String serial;

    /** 便捷视图字段：由同名 key 的点位同步。 */
    public volatile double temperature;
    public volatile double humidity;
    public volatile double current;
    public volatile int setpoint = 250;
    public volatile int alarmLow = 300;
    public volatile int alarmHigh = 400;
    public volatile String quality = "good";
    public volatile long lastSeen = System.currentTimeMillis();
    public volatile int badTicks = 0;

    /** 是否为真实设备；false 表示模拟器。 */
    public volatile boolean real = false;
    /** 传输类型：RTU / TCP。 */
    public volatile String transport = "RTU";
    /** 网关端点，用于界面展示。 */
    public volatile String endpoint = "";
    /** 真实设备配置；模拟设备为 null。 */
    public volatile RealDeviceConfig realConfig;
    /** 最近一次真实读取的起始地址与原始寄存器，用于界面展示真实字节。 */
    public volatile int lastReadBase;
    public volatile int[] lastReadRegisters;

    /** 设备作为平台从站对外提供服务时使用的 Unit ID。 */
    public volatile int slaveUnitId = 1;
    /** 点表。 */
    private volatile List<PointConfig> points = new ArrayList<>();
    /** 点位工程值：key → 值。 */
    private final Map<String, Double> pointValues = new ConcurrentHashMap<>();

    private final Deque<Double> history = new ArrayDeque<>();

    public DeviceState(String id, String name, String gatewayId, int slaveId, String model, String serial,
                       double temperature, double humidity) {
        this.id = id;
        this.name = name;
        this.gatewayId = gatewayId;
        this.slaveId = slaveId;
        this.model = model;
        this.serial = serial;
        this.temperature = temperature;
        this.humidity = humidity;
        for (int i = 0; i < SEED_HISTORY_SIZE; i++) {
            history.addLast(temperature
                    + (ThreadLocalRandom.current().nextDouble() - 0.5) * SEED_NOISE);
        }
    }

    /**
     * 设置点表；已有点位的当前值保留，新点位用初始值。
     */
    public void setPoints(List<PointConfig> newPoints) {
        List<PointConfig> copied = new ArrayList<>();
        for (PointConfig point : newPoints) {
            point.normalize();
            copied.add(point);
            pointValues.putIfAbsent(point.key, initialFor(point));
        }
        this.points = copied;
        syncConvenience();
    }

    public List<PointConfig> getPoints() {
        return points;
    }

    public Map<String, Double> getPointValues() {
        return pointValues;
    }

    /**
     * 读取点位工程值；未采集时返回 0。
     */
    public double value(String key) {
        Double value = pointValues.get(key);
        return value == null ? 0.0 : value;
    }

    /**
     * 写入点位工程值。
     */
    public void putPointValue(String key, double value) {
        pointValues.put(key, value);
    }

    /**
     * 把常见 key 同步到便捷字段（工程值），兼容旧 DTO 与写命令映射。
     */
    public void syncConvenience() {
        if (pointValues.containsKey("temp")) {
            temperature = pointValues.get("temp");
        }
        if (pointValues.containsKey("hum")) {
            humidity = pointValues.get("hum");
        }
        if (pointValues.containsKey("cur")) {
            current = pointValues.get("cur");
        }
        if (pointValues.containsKey("sp")) {
            setpoint = (int) Math.round(pointValues.get("sp"));
        }
        if (pointValues.containsKey("alarmLo")) {
            alarmLow = (int) Math.round(pointValues.get("alarmLo"));
        }
        if (pointValues.containsKey("alarmHi")) {
            alarmHigh = (int) Math.round(pointValues.get("alarmHi"));
        }
    }

    /**
     * 模拟设备：只读点位随机游走，可写点位保持最后一次写入值。
     */
    public void simulateValues() {
        for (PointConfig point : points) {
            if (point.writable) {
                continue;
            }
            double value = pointValues.getOrDefault(point.key, initialFor(point));
            double step = Math.max(0.2, Math.abs(value) * 0.02);
            value = value + (ThreadLocalRandom.current().nextDouble() - 0.5) * step;
            pointValues.put(point.key, value);
        }
        syncConvenience();
    }

    /**
     * 曲线取第一个点位的值。
     */
    public double trendValue() {
        return points.isEmpty() ? 0.0 : value(points.get(0).key);
    }

    private double initialFor(PointConfig point) {
        return switch (point.key) {
            case "temp" -> 25 + ThreadLocalRandom.current().nextDouble() * 5;
            case "hum" -> 50;
            case "cur" -> 5;
            case "sp" -> 25;
            case "alarmLo" -> 30;
            case "alarmHi" -> 40;
            default -> ThreadLocalRandom.current().nextDouble() * 100;
        };
    }

    public synchronized void addHistory(double value) {
        history.addLast(value);
        if (history.size() > MAX_HISTORY_SIZE) {
            history.removeFirst();
        }
    }

    public synchronized List<Double> historySnapshot() {
        return new ArrayList<>(history);
    }
}
