package com.example.gateway.model;

import java.util.List;
import java.util.Map;

/**
 * API 传输对象集合。
 *
 * <p>统一使用 record，保证序列化结构稳定、不可变；网关/设备/点位/指令/线程
 * 的字段都在这里集中定义，前端只依赖这一层，不依赖运行时状态类。</p>
 *
 * @author xujin
 * @since 0.1.0
 */
public final class Dtos {
    private Dtos() {
    }

    public record DeviceDto(
            String id, String name, String gatewayId, int slaveId, String model,
            double temperature, double humidity, double current, String quality, long lastSeen,
            String serial, int setpoint, int alarmLow, int alarmHigh,
            List<Double> history, boolean real, String transport, String endpoint,
            int slaveUnitId, List<PointValueDto> points) {
    }

    public record PointValueDto(
            String key, String name, int address, String type, String unit,
            double value, boolean writable, int function, int registerCount) {
    }

    public record GatewayDto(
            String id, String name, String transport, String endpoint,
            int cycleMs, int queueDepth, int utilization, String threadName,
            int deviceCount, List<DeviceDto> devices, boolean real) {
    }

    public record StatsDto(int online, int points, double successRate, int p95, int throughput) {
    }

    public record OverviewDto(StatsDto stats, List<GatewayDto> gateways, List<LogEntry> events) {
    }

    public record RegisterCellDto(int address, int value, String chars) {
    }

    public record RegisterGroupDto(
            String key, String name, String type, int start, int count, boolean rw,
            List<RegisterCellDto> cells, String bytes, String value,
            String readRequest, String readResponse, String writeRequest) {
    }

    public record LongStringControlDto(int address, String name, int value, String display) {
    }

    public record LongStringRegisterDto(int address, int value, String chars) {
    }

    public record LongStringChunkDto(int index, int startReg, int count, int bytes, String range) {
    }

    public record LongStringDto(
            String value, int length, int registerCount, int chunkCount,
            List<LongStringControlDto> control,
            List<LongStringRegisterDto> registers,
            List<LongStringChunkDto> chunks,
            int crc, String readRequest, String readResponse,
            List<String> writeFrames) {
    }

    public record DeviceDetailDto(DeviceDto device, List<RegisterGroupDto> groups, LongStringDto longString) {
    }

    public record LaneDto(String lane, String gatewayId, String thread, String state,
                          int queueDepth, List<ThreadOp> ops) {
    }

    public record ThreadsDto(long now, List<LaneDto> lanes) {
    }

    public record CommandRequest(String deviceId, Integer function, Integer address,
                                 List<Integer> values, String simulate, String note) {
    }

    public record CommandStepDto(String name, String note, int atMs) {
    }

    public record CommandDto(
            String id, String traceId, String deviceId, int function, int address,
            List<Integer> values, String frame, String status, String message,
            int latencyMs, List<CommandStepDto> steps, long ts, String note) {
    }

    public record ModbusProbeRequest(
            String transport, String host, Integer port, String serialPort,
            Integer baud, String parity, Integer dataBits, Integer stopBits,
            Integer unitId, Integer address, Integer count) {
    }

    public record ModbusProbeResult(List<Integer> registers, Map<String, Object> decoded) {
    }

    public record RealDeviceView(RealDeviceConfig config, DeviceDto device, String gatewayId) {
    }

    public record PointTableParseResult(List<PointConfig> points, List<String> errors) {
    }

    public record SlaveUnitDto(int unit, String deviceId, String name,
                               int minAddress, int maxAddress, int writableCount) {
    }

    public record SlaveStatusDto(boolean enabled, int port, boolean running,
                                 List<SlaveUnitDto> units) {
    }
}
