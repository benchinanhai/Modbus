package com.example.gateway.service;

import com.example.gateway.model.DeviceState;
import com.example.gateway.model.Dtos.CommandDto;
import com.example.gateway.model.Dtos.CommandRequest;
import com.example.gateway.model.Dtos.CommandStepDto;
import com.example.gateway.model.PointConfig;
import com.example.gateway.store.GatewayStore;
import com.example.gateway.ws.TelemetrySocket;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * 指令下发服务。
 *
 * <p>模拟设备走“时间线 + 延迟”的演示路径；真实设备走对应通道的串行队列，
 * 实际执行 FC06/FC16 并回读校验。两种模式返回同一个 {@link CommandDto}，
 * 前端不需要区分。</p>
 *
 * @author xujin
 * @since 0.2.0
 */
@Service
public class CommandService {
    private static final int FUNCTION_WRITE_SINGLE = 6;
    private static final int FUNCTION_WRITE_MULTIPLE = 16;
    private static final int DEFAULT_FUNCTION = 6;
    private static final int DEFAULT_ADDRESS = 8;
    private static final int MIN_ADDRESS = 0;
    private static final int MAX_ADDRESS = 0xFFFF;
    private static final int MAX_FC16_VALUES = 123;
    private static final int MAX_SINGLE_VALUE = 0xFFFF;
    private static final int STEP_INTERVAL_MS = 350;
    private static final int STEP_SEND_MS = 750;
    private static final int STEP_PROCESS_MS = 1150;
    private static final int STEP_VERIFY_MS = 1550;
    private static final int STEP_CACHE_MS = 1950;
    private static final int SIM_LATENCY_OK_MS = 12;
    private static final int SIM_LATENCY_EXCEPTION_MS = 18;
    private static final int SIM_LATENCY_TIMEOUT_MS = 3000;
    private static final String MODE_OK = "ok";
    private static final String MODE_TIMEOUT = "timeout";
    private static final String MODE_EXCEPTION = "exception";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_PREVIEW = "PREVIEW";

    private final GatewayStore store;
    private final SimulationService simulation;
    private final RealDeviceService realDeviceService;
    private final TelemetrySocket socket;

    public CommandService(GatewayStore store, SimulationService simulation,
                          RealDeviceService realDeviceService, TelemetrySocket socket) {
        this.store = store;
        this.simulation = simulation;
        this.realDeviceService = realDeviceService;
        this.socket = socket;
    }

    /**
     * 只生成报文帧，不下发。用于前端输入时的实时预览。
     */
    public CommandDto preview(CommandRequest request) {
        Parsed parsed = parse(request);
        String frame = RegisterService.writeRequest(parsed.device, parsed.function,
                parsed.address, parsed.values);
        return new CommandDto("preview", "preview", parsed.device.id, parsed.function,
                parsed.address, toList(parsed.values), frame, STATUS_PREVIEW,
                "预览帧，未下发", 0, List.of(), System.currentTimeMillis(),
                request.note() == null ? "" : request.note());
    }

    /**
     * 下发写命令。
     *
     * <p>真实设备的执行结果以从站回显 + 回读为准；模拟设备按 simulate 参数
     * 生成成功、超时或异常码 02 的结果。</p>
     */
    public CommandDto execute(CommandRequest request) {
        Parsed parsed = parse(request);
        String trace = store.nextTrace();
        String frame = RegisterService.writeRequest(parsed.device, parsed.function,
                parsed.address, parsed.values);

        boolean real = parsed.device.real;
        boolean success;
        boolean timeout = false;
        boolean exception = false;
        String message;
        int latency;
        String status;
        if (real) {
            RealDeviceService.WriteResult result = realDeviceService.write(parsed.device,
                    parsed.function, parsed.address, parsed.values);
            success = result.success();
            message = result.message();
            latency = (int) Math.min(Integer.MAX_VALUE, result.latencyMs());
            status = success ? STATUS_SUCCESS : STATUS_FAILED;
        } else {
            String simulate = request.simulate() == null ? MODE_OK : request.simulate();
            timeout = MODE_TIMEOUT.equalsIgnoreCase(simulate);
            exception = MODE_EXCEPTION.equalsIgnoreCase(simulate);
            success = !timeout && !exception;
            status = success ? STATUS_SUCCESS : STATUS_FAILED;
            if (timeout) {
                message = "超时，已重试 3 次；点位标记为 bad，等待下一周期";
                latency = SIM_LATENCY_TIMEOUT_MS;
            } else if (exception) {
                message = "异常码 02：非法数据地址；命令终止";
                latency = SIM_LATENCY_EXCEPTION_MS;
            } else {
                message = "写入成功；缓存已更新，WebSocket 已推送";
                latency = SIM_LATENCY_OK_MS;
            }
        }

        List<CommandStepDto> steps = buildSteps(frame, message, success, real, timeout, exception);
        CommandDto command = new CommandDto("CMD-" + (1000 + store.nextCommandId()), trace,
                parsed.device.id, parsed.function, parsed.address, toList(parsed.values),
                frame, status, message, latency, steps, System.currentTimeMillis(),
                request.note() == null ? "" : request.note());

        store.addCommand(command);
        store.addLog(success ? "INFO" : "ERROR", parsed.device.id,
                success ? "write ok addr=" + parsed.address + " values=" + toList(parsed.values)
                        : message, trace);
        socket.broadcastCommand(command);

        if (success) {
            applyToState(parsed.device, parsed.function, parsed.address, parsed.values);
            if (!real) {
                simulation.submitWrite(parsed.device, parsed.address, parsed.values, trace);
            }
        }
        return command;
    }

    private List<CommandStepDto> buildSteps(String frame, String message, boolean success,
                                            boolean real, boolean timeout, boolean exception) {
        List<CommandStepDto> steps = new ArrayList<>();
        steps.add(new CommandStepDto("入队",
                real ? "进入真实设备通道队列，优先级高于轮询" : "写命令进入通道队列，优先级高于轮询", 0));
        steps.add(new CommandStepDto("通道令牌", "等待当前事务结束，通道内串行", STEP_INTERVAL_MS));
        steps.add(new CommandStepDto("发送请求", frame, STEP_SEND_MS));
        steps.add(new CommandStepDto("从站处理",
                real ? (success ? "从站返回回显帧" : message)
                        : (exception ? "返回 0x86 + 异常码 02" : "执行写入并返回回显帧"),
                STEP_PROCESS_MS));
        steps.add(new CommandStepDto("校验响应",
                real ? (success ? "回显与回读一致" : "回读或 CRC 校验失败")
                        : (timeout ? "1000 ms 无响应，重试 1 / 3" : "CRC 正确，功能码匹配"),
                STEP_VERIFY_MS));
        steps.add(new CommandStepDto("更新缓存",
                success ? "写缓存、推 WebSocket、记审计日志" : "重试耗尽，标坏点并告警",
                STEP_CACHE_MS));
        return steps;
    }

    /**
     * 写成功后立刻更新点表值，避免等待下一次轮询才在界面看到变化。
     */
    private static void applyToState(DeviceState device, int function, int address, int[] values) {
        for (PointConfig point : device.getPoints()) {
            if (point.address == address && point.writable
                    && point.registerCount() == values.length) {
                device.putPointValue(point.key,
                        RegisterService.decodePoint(point, values, 0));
            }
        }
        device.syncConvenience();
    }

    private Parsed parse(CommandRequest request) {
        if (request == null || request.deviceId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId is required");
        }
        DeviceState device = store.device(request.deviceId());
        if (device == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "unknown device " + request.deviceId());
        }
        int function = request.function() == null ? DEFAULT_FUNCTION : request.function();
        if (function != FUNCTION_WRITE_SINGLE && function != FUNCTION_WRITE_MULTIPLE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "function must be 6 or 16");
        }
        int address = request.address() == null ? DEFAULT_ADDRESS : request.address();
        if (address < MIN_ADDRESS || address > MAX_ADDRESS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "address out of range");
        }
        List<Integer> raw = request.values() == null ? List.of() : request.values();
        if (raw.isEmpty() || (function == FUNCTION_WRITE_SINGLE && raw.size() != 1)
                || (function == FUNCTION_WRITE_MULTIPLE && raw.size() > MAX_FC16_VALUES)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid value count for function " + function);
        }
        int[] values = new int[raw.size()];
        for (int index = 0; index < raw.size(); index++) {
            Integer value = raw.get(index);
            if (value == null || value < 0 || value > MAX_SINGLE_VALUE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value out of range");
            }
            values[index] = value;
        }
        return new Parsed(device, function, address, values);
    }

    private static List<Integer> toList(int[] values) {
        List<Integer> list = new ArrayList<>(values.length);
        for (int value : values) {
            list.add(value);
        }
        return list;
    }

    private record Parsed(DeviceState device, int function, int address, int[] values) {
    }
}
