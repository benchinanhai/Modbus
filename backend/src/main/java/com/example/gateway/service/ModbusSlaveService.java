package com.example.gateway.service;

import com.example.gateway.model.DeviceState;
import com.example.gateway.model.Dtos.SlaveStatusDto;
import com.example.gateway.model.Dtos.SlaveUnitDto;
import com.example.gateway.model.PointConfig;
import com.example.gateway.store.GatewayStore;
import com.example.gateway.ws.TelemetrySocket;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 平台作为 Modbus TCP 从站对外提供服务。
 *
 * <p>从站镜像由每个设备的点表生成：Unit ID = 设备的 slaveUnitId，
 * 寄存器地址 = 点表地址。外部主站（SCADA 等）可以：</p>
 * <ul>
 *   <li>FC03/FC04 读任意点位的当前值；</li>
 *   <li>FC06/FC16 写可写点位——真实设备会透传到从站并回读校验，
 *       模拟设备直接更新平台值。</li>
 * </ul>
 *
 * <p>每个连接一个线程；广播（Unit 0）不支持，主站需指定具体 Unit。</p>
 *
 * @author xujin
 * @since 0.2.0
 */
@Service
public class ModbusSlaveService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModbusSlaveService.class);

    private static final int MBAP_HEADER_LENGTH = 7;
    private static final int PROTOCOL_ID = 0;
    private static final int MAX_PDU_LENGTH = 253;
    private static final int FUNCTION_READ_HOLDING = 3;
    private static final int FUNCTION_READ_INPUT = 4;
    private static final int FUNCTION_WRITE_SINGLE = 6;
    private static final int FUNCTION_WRITE_MULTIPLE = 16;
    private static final int EXCEPTION_ILLEGAL_FUNCTION = 1;
    private static final int EXCEPTION_ILLEGAL_ADDRESS = 2;
    private static final int EXCEPTION_SLAVE_FAILURE = 4;
    private static final int MAX_READ_REGISTERS = 125;
    private static final int MAX_WRITE_REGISTERS = 123;

    private final GatewayStore store;
    private final RegisterService registers;
    private final RealDeviceService realDevices;
    private final TelemetrySocket socket;

    @Value("${gateway.slave.enabled:true}")
    private boolean enabled;

    @Value("${gateway.slave.port:1502}")
    private int port;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final AtomicInteger connectionSequence = new AtomicInteger();
    private volatile boolean running;

    public ModbusSlaveService(GatewayStore store, RegisterService registers,
                              RealDeviceService realDevices, TelemetrySocket socket) {
        this.store = store;
        this.registers = registers;
        this.realDevices = realDevices;
        this.socket = socket;
    }

    /**
     * 启动从站监听。
     */
    @PostConstruct
    public void start() {
        if (!enabled) {
            LOGGER.info("modbus slave disabled");
            return;
        }
        running = true;
        acceptThread = new Thread(this::acceptLoop, "modbus-slave-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /**
     * 关闭从站监听与所有连接。
     */
    @PreDestroy
    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // 关闭时的 IO 异常无需处理
        }
    }

    /**
     * 从站运行状态与 Unit 映射，供前端展示。
     */
    public SlaveStatusDto status() {
        List<SlaveUnitDto> units = new ArrayList<>();
        for (DeviceState device : store.devices()) {
            List<PointConfig> points = device.getPoints();
            if (points.isEmpty()) {
                continue;
            }
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            int writable = 0;
            for (PointConfig point : points) {
                min = Math.min(min, point.address);
                max = Math.max(max, point.address + point.registerCount() - 1);
                if (point.writable) {
                    writable++;
                }
            }
            units.add(new SlaveUnitDto(device.slaveUnitId, device.id, device.name,
                    min, max, writable));
        }
        units.sort(Comparator.comparingInt(SlaveUnitDto::unit));
        return new SlaveStatusDto(enabled, port, running, units);
    }

    private void acceptLoop() {
        try (ServerSocket server = new ServerSocket(port)) {
            serverSocket = server;
            LOGGER.info("modbus slave listening on tcp/{}", port);
            while (running) {
                Socket socket = server.accept();
                int connection = connectionSequence.incrementAndGet();
                Thread thread = new Thread(() -> handleConnection(socket),
                        "modbus-slave-conn-" + connection);
                thread.setDaemon(true);
                thread.start();
            }
        } catch (IOException e) {
            if (running) {
                LOGGER.error("modbus slave stopped unexpectedly", e);
            }
        }
    }

    private void handleConnection(Socket socket) {
        String remote = String.valueOf(socket.getRemoteSocketAddress());
        LOGGER.info("slave connection from {}", remote);
        try (socket;
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             OutputStream output = socket.getOutputStream()) {
            while (running) {
                byte[] header = new byte[MBAP_HEADER_LENGTH];
                input.readFully(header);
                int length = ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);
                if (length < 2 || length > MAX_PDU_LENGTH + 1) {
                    break;
                }
                int unit = header[6] & 0xFF;
                byte[] pdu = new byte[length - 1];
                input.readFully(pdu);
                int function = pdu[0] & 0xFF;

                byte[] responsePdu;
                try {
                    responsePdu = handle(unit, function, pdu);
                } catch (Exception e) {
                    LOGGER.warn("slave request failed: {}", e.getMessage());
                    responsePdu = exceptionPdu(function, EXCEPTION_SLAVE_FAILURE);
                }
                output.write(response(unit, header, responsePdu));
                output.flush();
            }
        } catch (IOException e) {
            // 客户端断开属于正常现象
        }
        LOGGER.info("slave connection closed {}", remote);
    }

    private byte[] handle(int unit, int function, byte[] pdu) {
        DeviceState device = unitDevice(unit);
        if (device == null) {
            return exceptionPdu(function, EXCEPTION_ILLEGAL_ADDRESS);
        }
        int address = ((pdu[1] & 0xFF) << 8) | (pdu[2] & 0xFF);
        return switch (function) {
            case FUNCTION_READ_HOLDING, FUNCTION_READ_INPUT ->
                    read(device, function, address,
                            ((pdu[3] & 0xFF) << 8) | (pdu[4] & 0xFF));
            case FUNCTION_WRITE_SINGLE ->
                    write(device, function, address, new int[]{((pdu[3] & 0xFF) << 8) | (pdu[4] & 0xFF)});
            case FUNCTION_WRITE_MULTIPLE -> {
                int count = ((pdu[3] & 0xFF) << 8) | (pdu[4] & 0xFF);
                int byteCount = pdu[5] & 0xFF;
                if (count < 1 || count > MAX_WRITE_REGISTERS || byteCount != count * 2
                        || pdu.length < 6 + count * 2) {
                    yield exceptionPdu(function, EXCEPTION_ILLEGAL_ADDRESS);
                }
                int[] values = new int[count];
                for (int index = 0; index < count; index++) {
                    values[index] = ((pdu[6 + index * 2] & 0xFF) << 8)
                            | (pdu[7 + index * 2] & 0xFF);
                }
                yield write(device, function, address, values);
            }
            default -> exceptionPdu(function, EXCEPTION_ILLEGAL_FUNCTION);
        };
    }

    private byte[] read(DeviceState device, int function, int address, int count) {
        if (count < 1 || count > MAX_READ_REGISTERS) {
            return exceptionPdu(function, EXCEPTION_ILLEGAL_ADDRESS);
        }
        SlaveImage image = image(device);
        int offset = address - image.base();
        if (offset < 0 || offset + count > image.registers().length) {
            return exceptionPdu(function, EXCEPTION_ILLEGAL_ADDRESS);
        }
        byte[] response = new byte[2 + count * 2];
        response[0] = (byte) function;
        response[1] = (byte) (count * 2);
        for (int index = 0; index < count; index++) {
            int register = image.registers()[offset + index];
            response[2 + index * 2] = (byte) (register >> 8);
            response[3 + index * 2] = (byte) register;
        }
        return response;
    }

    /**
     * 从站写请求：可写点位透传到真实设备（或更新模拟值），只读点位拒绝。
     */
    private byte[] write(DeviceState device, int function, int address, int[] values) {
        if (values.length > MAX_WRITE_REGISTERS) {
            return exceptionPdu(function, EXCEPTION_ILLEGAL_ADDRESS);
        }
        PointConfig point = pointCovering(device, address, values.length);
        boolean ok;
        try {
            if (point != null && point.writable) {
                // 命中一个可写点位：FC06 或 FC16 透传
                forwardWrite(device, point, address, values);
                ok = true;
            } else if (coversWritable16BitPoints(device, address, values.length)) {
                // 跨多个 16 位可写点位：逐个转发
                for (int index = 0; index < values.length; index++) {
                    PointConfig target = pointCovering(device, address + index, 1);
                    forwardWrite(device, target, target.address, new int[]{values[index]});
                }
                ok = true;
            } else {
                return exceptionPdu(function, EXCEPTION_ILLEGAL_ADDRESS);
            }
        } catch (Exception e) {
            store.addLog("ERROR", device.id, "slave write failed: " + e.getMessage());
            return exceptionPdu(function, EXCEPTION_SLAVE_FAILURE);
        }
        if (ok) {
            store.addLog("INFO", device.id, "slave write addr=" + address
                    + " values=" + Arrays.toString(values));
        }
        return echoWrite(function, address, values);
    }

    private void forwardWrite(DeviceState device, PointConfig point, int address, int[] values) {
        if (device.real) {
            int function = values.length == 1 ? FUNCTION_WRITE_SINGLE : FUNCTION_WRITE_MULTIPLE;
            RealDeviceService.WriteResult result = realDevices.write(device, function,
                    point.address, values);
            if (!result.success()) {
                throw new IllegalStateException(result.message());
            }
            return;
        }
        // 模拟设备：按点表缩放更新点位值
        device.putPointValue(point.key, RegisterService.decodePoint(point, values, 0));
        device.syncConvenience();
        socket.broadcastTelemetry(store.deviceDto(device), store.stats(), "modbus-slave");
    }

    private boolean coversWritable16BitPoints(DeviceState device, int address, int count) {
        for (int index = 0; index < count; index++) {
            PointConfig point = pointCovering(device, address + index, 1);
            if (point == null || !point.writable || point.registerCount() != 1) {
                return false;
            }
        }
        return true;
    }

    private PointConfig pointCovering(DeviceState device, int address, int count) {
        for (PointConfig point : device.getPoints()) {
            if (address >= point.address && address + count <= point.address + point.registerCount()) {
                return point;
            }
        }
        return null;
    }

    private SlaveImage image(DeviceState device) {
        List<PointConfig> points = device.getPoints();
        if (points.isEmpty()) {
            throw new IllegalStateException("设备没有点表");
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (PointConfig point : points) {
            min = Math.min(min, point.address);
            max = Math.max(max, point.address + point.registerCount() - 1);
        }
        int[] image = new int[max - min + 1];
        for (PointConfig point : points) {
            int[] raw = registers.pointRaw(device, point);
            for (int index = 0; index < raw.length; index++) {
                image[point.address - min + index] = raw[index];
            }
        }
        return new SlaveImage(min, image);
    }

    private DeviceState unitDevice(int unit) {
        DeviceState fallback = null;
        for (DeviceState device : store.devices()) {
            if (device.slaveUnitId != unit) {
                continue;
            }
            // 同 Unit 冲突时真实设备优先
            if (device.real) {
                return device;
            }
            if (fallback == null) {
                fallback = device;
            }
        }
        return fallback;
    }

    private int functionForWrite(int count) {
        return count == 1 ? FUNCTION_WRITE_SINGLE : FUNCTION_WRITE_MULTIPLE;
    }

    private byte[] echoWrite(int function, int address, int[] values) {
        if (function == FUNCTION_WRITE_SINGLE) {
            return new byte[]{(byte) function, (byte) (address >> 8), (byte) address,
                    (byte) (values[0] >> 8), (byte) values[0]};
        }
        byte[] response = new byte[6];
        response[0] = (byte) function;
        response[1] = (byte) (address >> 8);
        response[2] = (byte) address;
        response[3] = (byte) (values.length >> 8);
        response[4] = (byte) values.length;
        return response;
    }

    private byte[] exceptionPdu(int function, int code) {
        return new byte[]{(byte) (function | 0x80), (byte) code};
    }

    private byte[] response(int unit, byte[] requestHeader, byte[] responsePdu) {
        byte[] response = new byte[MBAP_HEADER_LENGTH + responsePdu.length];
        response[0] = requestHeader[0];
        response[1] = requestHeader[1];
        response[2] = 0;
        response[3] = 0;
        int length = responsePdu.length + 1;
        response[4] = (byte) (length >> 8);
        response[5] = (byte) length;
        response[6] = (byte) unit;
        System.arraycopy(responsePdu, 0, response, MBAP_HEADER_LENGTH, responsePdu.length);
        return response;
    }

    private record SlaveImage(int base, int[] registers) {
    }
}
