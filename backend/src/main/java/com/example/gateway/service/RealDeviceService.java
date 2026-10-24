package com.example.gateway.service;

import com.example.gateway.model.DeviceState;
import com.example.gateway.model.Dtos.DeviceDto;
import com.example.gateway.model.Dtos.ModbusProbeRequest;
import com.example.gateway.model.Dtos.ModbusProbeResult;
import com.example.gateway.model.Dtos.RealDeviceView;
import com.example.gateway.model.GatewayState;
import com.example.gateway.model.PointConfig;
import com.example.gateway.model.RealDeviceConfig;
import com.example.gateway.model.ThreadOp;
import com.example.gateway.store.GatewayStore;
import com.example.gateway.ws.TelemetrySocket;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 真实设备接入与轮询服务。
 *
 * <p>设备由前端“设备接入”页配置（TCP/RTU + 自定义点表），配置持久化到
 * {@code config/real-devices.json}。启动时恢复配置；之后每秒把轮询任务
 * 投递到对应通道的 worker 线程：按点表把功能码分组，先读 FC03 块，
 * 再读 FC04 块，然后按类型/字序/缩放解码到点位值。</p>
 *
 * @author xujin
 * @since 0.2.0
 */
@Service
public class RealDeviceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RealDeviceService.class);

    private static final String CONFIG_ENV = "GATEWAY_CONFIG";
    private static final String DEFAULT_CONFIG_PATH = "config/real-devices.json";
    private static final String TRANSPORT_TCP = "TCP";
    private static final String RTU_GATEWAY_NAME = "真实 RTU 总线";
    private static final String TCP_GATEWAY_NAME = "真实 TCP 设备";
    private static final String THREAD_PREFIX = "io-real-";
    private static final int CYCLE_MS = 1000;
    private static final int WRITE_WAIT_MS = 3000;
    private static final int MAX_READ_REGISTERS = 125;
    private static final int MAX_WRITE_REGISTERS = 123;
    private static final int MAX_POINTS_PER_DEVICE = 200;
    private static final int PROBE_LIMIT = 8;
    private static final int PRINTABLE_MIN = 32;
    private static final int PRINTABLE_MAX = 127;
    private static final int MIN_SLAVE_ID = 1;
    private static final int MAX_SLAVE_ID = 247;
    private static final int DEFAULT_TCP_PORT = 502;
    private static final int DEFAULT_BAUD = 9600;
    private static final int DEFAULT_DATA_BITS = 8;
    private static final int DEFAULT_STOP_BITS = 1;
    private static final int FUNCTION_READ_HOLDING = 3;
    private static final int FUNCTION_READ_INPUT = 4;
    private static final int FUNCTION_WRITE_SINGLE = 6;
    private static final int FUNCTION_WRITE_MULTIPLE = 16;

    private final GatewayStore store;
    private final TelemetrySocket socket;
    private final ModbusTcpClient tcpClient;
    private final ModbusRtuClient rtuClient;
    private final ObjectMapper objectMapper;

    private final Map<String, RealDeviceConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, GatewayState> gateways = new ConcurrentHashMap<>();
    private final Map<String, ChannelRuntime> channels = new ConcurrentHashMap<>();
    private final AtomicInteger gatewaySequence = new AtomicInteger();
    private final AtomicInteger deviceSequence = new AtomicInteger();
    private ScheduledThreadPoolExecutor scheduler;

    public RealDeviceService(GatewayStore store, TelemetrySocket socket,
                             ModbusTcpClient tcpClient, ModbusRtuClient rtuClient,
                             ObjectMapper objectMapper) {
        this.store = store;
        this.socket = socket;
        this.tcpClient = tcpClient;
        this.rtuClient = rtuClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 恢复已保存的真实设备，并启动轮询调度。
     */
    @PostConstruct
    public void init() {
        loadConfigs();
        scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "real-poll-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.scheduleAtFixedRate(this::enqueuePolls, CYCLE_MS, CYCLE_MS, TimeUnit.MILLISECONDS);
        LOGGER.info("real device polling started, {} device(s)", configs.size());
    }

    /**
     * 停止调度和所有真实设备通道。
     */
    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        channels.values().forEach(ChannelRuntime::shutdown);
    }

    /**
     * 返回所有真实设备配置及其当前运行状态。
     */
    public List<RealDeviceView> list() {
        List<RealDeviceView> views = new ArrayList<>();
        for (RealDeviceConfig config : configs.values()) {
            DeviceState device = store.device(config.id);
            views.add(new RealDeviceView(config,
                    device == null ? null : store.deviceDto(device),
                    device == null ? null : device.gatewayId));
        }
        views.sort(Comparator.comparing(view -> view.config().id));
        return views;
    }

    /**
     * 一次性读取，用于“测试连接”。不注册设备、不落库。
     *
     * @throws IOException 连接失败、超时或 Modbus 异常
     */
    public ModbusProbeResult test(ModbusProbeRequest request) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        RealDeviceConfig config = new RealDeviceConfig();
        config.transport = request.transport();
        config.host = request.host();
        config.port = request.port() == null ? DEFAULT_TCP_PORT : request.port();
        config.serialPort = request.serialPort();
        config.baud = request.baud() == null ? DEFAULT_BAUD : request.baud();
        config.parity = request.parity();
        config.dataBits = request.dataBits() == null ? DEFAULT_DATA_BITS : request.dataBits();
        config.stopBits = request.stopBits() == null ? DEFAULT_STOP_BITS : request.stopBits();
        config.slaveId = request.unitId() == null ? MIN_SLAVE_ID : request.unitId();
        config.normalize();

        int address = request.address() == null ? 0 : request.address();
        int count = request.count() == null ? 2 : request.count();
        validateAddress(address);
        if (count < 1 || count > MAX_READ_REGISTERS) {
            throw new IllegalArgumentException("count must be 1.." + MAX_READ_REGISTERS);
        }
        int[] registers = read(config, FUNCTION_READ_HOLDING, address, count);
        return new ModbusProbeResult(toList(registers), decodeProbe(registers));
    }

    /**
     * 接入一台真实设备并开始轮询。
     *
     * @throws IllegalArgumentException 配置不合法或编号冲突
     */
    public RealDeviceView add(RealDeviceConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        config.normalize();
        if (config.id == null || config.id.isBlank()) {
            config.id = nextDeviceId();
            config.serial = "DEV-REAL-" + config.id;
        }
        if (store.device(config.id) != null) {
            throw new IllegalArgumentException("设备编号已存在: " + config.id);
        }
        validate(config);
        register(config);
        persist();
        DeviceState device = store.device(config.id);
        return new RealDeviceView(config, store.deviceDto(device), device.gatewayId);
    }

    /**
     * 移除一台真实设备；如果所在通道已空，同时停止该通道线程。
     */
    public void remove(String id) {
        RealDeviceConfig config = configs.remove(id);
        if (config == null) {
            return;
        }
        DeviceState device = store.device(id);
        store.removeDevice(id);
        if (device != null) {
            GatewayState gateway = store.gateway(device.gatewayId);
            if (gateway != null && gateway.devices.isEmpty()) {
                ChannelRuntime channel = channels.remove(gateway.id);
                if (channel != null) {
                    channel.shutdown();
                }
                store.removeGateway(gateway.id);
                gateways.remove(gateway.id);
            }
        }
        persist();
    }

    /**
     * 替换真实设备的点表；保留已有点位的当前值。
     */
    public RealDeviceView setPoints(String id, List<PointConfig> points) {
        DeviceState device = store.device(id);
        RealDeviceConfig config = configs.get(id);
        if (device == null || config == null) {
            throw new IllegalArgumentException("unknown real device " + id);
        }
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("点表不能为空");
        }
        List<PointConfig> copied = new ArrayList<>();
        for (PointConfig point : points) {
            point.normalize();
            copied.add(point);
        }
        checkOverlap(copied);
        device.setPoints(copied);
        config.points = copied;
        persist();
        return new RealDeviceView(config, store.deviceDto(device), device.gatewayId);
    }

    /**
     * 通过设备所属通道串行执行一次真实写命令，并回读校验。
     *
     * @return 真实执行结果；超时也以失败返回，不向调用方抛异常
     */
    public WriteResult write(DeviceState device, int function, int address, int[] values) {
        RealDeviceConfig config = device.realConfig;
        if (config == null) {
            return new WriteResult(false, "设备没有真实驱动配置", 0);
        }
        ChannelRuntime channel = channels.get(device.gatewayId);
        if (channel == null) {
            return new WriteResult(false, "通道未启动", 0);
        }
        CompletableFuture<WriteResult> future = new CompletableFuture<>();
        channel.submit(new ChannelRuntime.Task(0) {
            @Override
            public void run() {
                long start = System.currentTimeMillis();
                try {
                    writeDirect(device, config, function, address, values);
                    future.complete(new WriteResult(true, "写入成功；回读一致",
                            System.currentTimeMillis() - start));
                } catch (Exception e) {
                    future.complete(new WriteResult(false, e.getMessage(),
                            System.currentTimeMillis() - start));
                }
            }
        });
        try {
            return future.get(WRITE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new WriteResult(false, "命令排队或执行超时", WRITE_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new WriteResult(false, "命令被中断", 0);
        } catch (ExecutionException e) {
            String message = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
            return new WriteResult(false, message, 0);
        }
    }

    private void loadConfigs() {
        Path path = configPath();
        if (!Files.exists(path)) {
            LOGGER.info("no real device config at {}", path.toAbsolutePath());
            return;
        }
        try {
            List<RealDeviceConfig> list = objectMapper.readValue(path.toFile(),
                    new TypeReference<List<RealDeviceConfig>>() {
                    });
            for (RealDeviceConfig config : list) {
                try {
                    config.normalize();
                    validate(config);
                    register(config);
                } catch (RuntimeException e) {
                    LOGGER.warn("skip invalid real device {}: {}", config.id, e.getMessage());
                }
            }
            LOGGER.info("loaded {} real device config(s) from {}", configs.size(), path.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("failed to load real device config {}", path.toAbsolutePath(), e);
        }
    }

    private void register(RealDeviceConfig config) {
        checkOverlap(config.points);
        String endpoint = endpointOf(config);
        GatewayState gateway = gateways.values().stream()
                .filter(item -> item.transport.equals(config.transport) && item.endpoint.equals(endpoint))
                .findFirst()
                .orElse(null);
        if (gateway == null) {
            int sequence = gatewaySequence.incrementAndGet();
            gateway = new GatewayState("GW-R" + sequence,
                    TRANSPORT_TCP.equals(config.transport) ? TCP_GATEWAY_NAME : RTU_GATEWAY_NAME,
                    config.transport, endpoint, THREAD_PREFIX + sequence, CYCLE_MS);
            gateway.real = true;
            gateways.put(gateway.id, gateway);
            store.addGateway(gateway);
        }

        DeviceState device = new DeviceState(config.id, config.name, gateway.id, config.slaveId,
                config.model, config.serial, 0, 0);
        device.real = true;
        device.transport = config.transport;
        device.endpoint = endpoint;
        device.realConfig = config;
        device.quality = "unknown";
        device.setPoints(config.points);
        device.slaveUnitId = config.slaveUnitId;
        store.addDevice(device);
        configs.put(config.id, config);
        if (!channels.containsKey(gateway.id)) {
            channels.put(gateway.id, new ChannelRuntime(gateway, store, socket));
        }
        LOGGER.info("registered real device {} via {}", config.id, endpoint);
    }

    private void enqueuePolls() {
        for (RealDeviceConfig config : configs.values()) {
            DeviceState device = store.device(config.id);
            if (device == null) {
                continue;
            }
            ChannelRuntime channel = channels.get(device.gatewayId);
            if (channel != null) {
                channel.submit(new RealPollTask(device));
            }
        }
    }

    private void poll(DeviceState device) {
        RealDeviceConfig config = device.realConfig;
        if (config == null) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            // 功能码 3 和 4 的点位分别成块读取
            List<PointConfig> holding = pointsByFunction(config, FUNCTION_READ_HOLDING);
            List<PointConfig> input = pointsByFunction(config, FUNCTION_READ_INPUT);
            if (!holding.isEmpty()) {
                Block block = blockRange(holding);
                int[] registers = read(config, FUNCTION_READ_HOLDING, block.base, block.count);
                device.lastReadBase = block.base;
                device.lastReadRegisters = registers;
                decodeInto(device, holding, registers, block.base);
            }
            if (!input.isEmpty()) {
                Block block = blockRange(input);
                int[] registers = read(config, FUNCTION_READ_INPUT, block.base, block.count);
                decodeInto(device, input, registers, block.base);
            }
            long duration = System.currentTimeMillis() - start;
            device.lastSeen = System.currentTimeMillis();
            device.quality = "good";
            device.badTicks = 0;
            store.recordPoll(true);
            store.addOp(new ThreadOp(store.nextOpId(), threadName(device), device.id,
                    start, duration, "poll", false, 0));
            device.addHistory(device.trendValue());
            socket.broadcastTelemetry(store.deviceDto(device), store.stats(), threadName(device));
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            device.quality = "bad";
            device.badTicks = 1;
            store.recordPoll(false);
            store.addOp(new ThreadOp(store.nextOpId(), threadName(device), device.id,
                    start, duration, "poll", true, 0));
            store.addLog("WARN", device.id, "real read failed: " + e.getMessage());
            socket.broadcastTelemetry(store.deviceDto(device), store.stats(), threadName(device));
        }
    }

    private void decodeInto(DeviceState device, List<PointConfig> points,
                            int[] registers, int base) {
        for (PointConfig point : points) {
            int offset = point.address - base;
            if (offset < 0 || offset + point.registerCount() > registers.length) {
                continue;
            }
            device.putPointValue(point.key,
                    RegisterService.decodePoint(point, registers, offset));
        }
        device.syncConvenience();
    }

    private List<PointConfig> pointsByFunction(RealDeviceConfig config, int function) {
        return config.points.stream()
                .filter(point -> point.function == function)
                .sorted(Comparator.comparingInt(point -> point.address))
                .collect(Collectors.toList());
    }

    private Block blockRange(List<PointConfig> points) {
        int base = points.get(0).address;
        PointConfig last = points.get(points.size() - 1);
        int end = last.address + last.registerCount() - 1;
        return new Block(base, end - base + 1);
    }

    private int[] read(RealDeviceConfig config, int function, int address, int count)
            throws IOException {
        if (TRANSPORT_TCP.equals(config.transport)) {
            return tcpClient.readRegisters(config.host, config.port, config.slaveId,
                    function, address, count);
        }
        return rtuClient.readRegisters(config.serialPort, config.baud, config.parity,
                config.dataBits, config.stopBits, config.slaveId, function, address, count);
    }

    private void writeDirect(DeviceState device, RealDeviceConfig config, int function,
                             int address, int[] values) throws IOException {
        if (function == FUNCTION_WRITE_SINGLE) {
            if (values.length != 1) {
                throw new IllegalArgumentException("FC06 requires exactly one value");
            }
            writeSingle(config, address, values[0]);
            int[] check = read(config, FUNCTION_READ_HOLDING, address, 1);
            if (check[0] != values[0]) {
                throw new IOException("回读不一致: " + check[0] + " != " + values[0]);
            }
            patchLastRead(address, values);
            return;
        }
        if (function == FUNCTION_WRITE_MULTIPLE) {
            if (values.length < 1 || values.length > MAX_WRITE_REGISTERS) {
                throw new IllegalArgumentException("FC16 value count must be 1.." + MAX_WRITE_REGISTERS);
            }
            writeMultiple(config, address, values);
            int[] check = read(config, FUNCTION_READ_HOLDING, address, values.length);
            for (int index = 0; index < values.length; index++) {
                if (check[index] != values[index]) {
                    throw new IOException("回读不一致 at " + (address + index));
                }
            }
            patchLastRead(address, values);
            return;
        }
        throw new IllegalArgumentException("unsupported function " + function);
    }

    /**
     * 写成功后同步原始寄存器缓存，让从站镜像和设备页立刻反映新值，
     * 而不是等下一次轮询。
     */
    private void patchLastRead(int address, int[] values) {
        DeviceState target = null;
        for (RealDeviceConfig config : configs.values()) {
            DeviceState candidate = store.device(config.id);
            if (candidate != null && candidate.realConfig == config) {
                target = candidate;
                break;
            }
        }
        if (target == null || target.lastReadRegisters == null) {
            return;
        }
        int offset = address - target.lastReadBase;
        if (offset >= 0 && offset + values.length <= target.lastReadRegisters.length) {
            for (int index = 0; index < values.length; index++) {
                target.lastReadRegisters[offset + index] = values[index];
            }
        }
    }

    private void writeSingle(RealDeviceConfig config, int address, int value) throws IOException {
        if (TRANSPORT_TCP.equals(config.transport)) {
            tcpClient.writeSingleRegister(config.host, config.port, config.slaveId, address, value);
            return;
        }
        rtuClient.writeSingleRegister(config.serialPort, config.baud, config.parity,
                config.dataBits, config.stopBits, config.slaveId, address, value);
    }

    private void writeMultiple(RealDeviceConfig config, int address, int[] values) throws IOException {
        if (TRANSPORT_TCP.equals(config.transport)) {
            tcpClient.writeMultipleRegisters(config.host, config.port, config.slaveId, address, values);
            return;
        }
        rtuClient.writeMultipleRegisters(config.serialPort, config.baud, config.parity,
                config.dataBits, config.stopBits, config.slaveId, address, values);
    }

    private Map<String, Object> decodeProbe(int[] registers) {
        Map<String, Object> decoded = new LinkedHashMap<>();
        List<Integer> signed = new ArrayList<>();
        List<Integer> unsigned = new ArrayList<>();
        List<String> hex = new ArrayList<>();
        int limit = Math.min(PROBE_LIMIT, registers.length);
        for (int index = 0; index < limit; index++) {
            signed.add((int) (short) registers[index]);
            unsigned.add(registers[index] & 0xFFFF);
            hex.add(String.format(Locale.ROOT, "%04X", registers[index] & 0xFFFF));
        }
        decoded.put("hex", hex);
        decoded.put("int16", signed);
        decoded.put("uint16", unsigned);
        if (registers.length >= 2) {
            Map<String, Object> floats = new LinkedHashMap<>();
            for (String order : List.of("ABCD", "CDAB", "BADC", "DCBA")) {
                floats.put(order, RegisterService.decodePoint(
                        new PointConfig("probe", "probe", 3, 0,
                                PointConfig.TYPE_FLOAT32, order, 1.0, "", false),
                        registers, 0));
            }
            decoded.put("float32", floats);
        }
        StringBuilder ascii = new StringBuilder();
        for (int register : registers) {
            char high = (char) ((register >> 8) & 0xFF);
            char low = (char) (register & 0xFF);
            if (high >= PRINTABLE_MIN && high < PRINTABLE_MAX) {
                ascii.append(high);
            }
            if (low >= PRINTABLE_MIN && low < PRINTABLE_MAX) {
                ascii.append(low);
            }
        }
        decoded.put("ascii", ascii.toString());
        return decoded;
    }

    private void validate(RealDeviceConfig config) {
        if (config.slaveId < MIN_SLAVE_ID || config.slaveId > MAX_SLAVE_ID) {
            throw new IllegalArgumentException("slaveId must be " + MIN_SLAVE_ID + ".." + MAX_SLAVE_ID);
        }
        if (config.slaveUnitId < MIN_SLAVE_ID || config.slaveUnitId > MAX_SLAVE_ID) {
            throw new IllegalArgumentException("slaveUnitId must be " + MIN_SLAVE_ID + ".." + MAX_SLAVE_ID);
        }
        // 从站 Unit 冲突会让外部主站读到错误设备的数据，必须在接入时拦下
        for (RealDeviceConfig other : configs.values()) {
            if (!config.id.equals(other.id) && other.slaveUnitId == config.slaveUnitId) {
                throw new IllegalArgumentException(
                        "从站 Unit ID " + config.slaveUnitId + " 已被 " + other.id + " 占用");
            }
        }
        if (config.points.size() > MAX_POINTS_PER_DEVICE) {
            throw new IllegalArgumentException("点表最多 " + MAX_POINTS_PER_DEVICE + " 个点位");
        }
        checkOverlap(config.points);
        if (TRANSPORT_TCP.equals(config.transport)) {
            if (config.host == null || config.host.isBlank() || config.port < 1 || config.port > 65535) {
                throw new IllegalArgumentException("invalid TCP endpoint");
            }
        } else if (config.serialPort == null || config.serialPort.isBlank()) {
            throw new IllegalArgumentException("serialPort is required for RTU");
        }
    }

    /**
     * 同功能码内点位地址不允许重叠，且单个读块不超过 125 个寄存器。
     */
    private static void checkOverlap(List<PointConfig> points) {
        for (int function : new int[]{FUNCTION_READ_HOLDING, FUNCTION_READ_INPUT}) {
            List<PointConfig> subset = points.stream()
                    .filter(point -> point.function == function)
                    .sorted(Comparator.comparingInt(point -> point.address))
                    .collect(Collectors.toList());
            for (int index = 1; index < subset.size(); index++) {
                PointConfig previous = subset.get(index - 1);
                PointConfig current = subset.get(index);
                if (previous.address + previous.registerCount() > current.address) {
                    throw new IllegalArgumentException(
                            "点表地址重叠: " + previous.name + " 与 " + current.name);
                }
            }
            if (!subset.isEmpty()) {
                PointConfig first = subset.get(0);
                PointConfig last = subset.get(subset.size() - 1);
                int span = last.address + last.registerCount() - first.address;
                if (span > MAX_READ_REGISTERS) {
                    throw new IllegalArgumentException(
                            "同功能码点位跨度超过 " + MAX_READ_REGISTERS + " 个寄存器，请拆分设备");
                }
            }
        }
    }

    private String endpointOf(RealDeviceConfig config) {
        return TRANSPORT_TCP.equals(config.transport)
                ? config.host + ":" + config.port
                : config.serialPort + " · " + config.baud;
    }

    private String threadName(DeviceState device) {
        GatewayState gateway = store.gateway(device.gatewayId);
        return gateway == null ? THREAD_PREFIX + "unknown" : gateway.threadName;
    }

    private String nextDeviceId() {
        String id;
        do {
            id = String.format(Locale.ROOT, "R-%02d", deviceSequence.incrementAndGet());
        } while (store.device(id) != null);
        return id;
    }

    private synchronized void persist() {
        Path path = configPath();
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(path.toFile(), new ArrayList<>(configs.values()));
            LOGGER.info("persisted {} real device config(s) to {}", configs.size(), path.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("failed to persist real device config {}", path.toAbsolutePath(), e);
        }
    }

    private static Path configPath() {
        String value = System.getenv(CONFIG_ENV);
        return Path.of(value == null || value.isBlank() ? DEFAULT_CONFIG_PATH : value);
    }

    private static void validateAddress(int address) {
        if (address < 0 || address > 0xFFFF) {
            throw new IllegalArgumentException("address out of range: " + address);
        }
    }

    private static List<Integer> toList(int[] values) {
        List<Integer> list = new ArrayList<>(values.length);
        for (int value : values) {
            list.add(value);
        }
        return list;
    }

    private record Block(int base, int count) {
    }

    /**
     * 通道内轮询任务；与模拟轮询共用队列，优先级 1。
     */
    private final class RealPollTask extends ChannelRuntime.Task {
        private final DeviceState device;

        private RealPollTask(DeviceState device) {
            super(1);
            this.device = device;
        }

        @Override
        public void run() {
            poll(device);
        }
    }

    /**
     * 真实写入结果。
     *
     * @param success   是否成功
     * @param message   成功/失败说明
     * @param latencyMs 通道内实际耗时
     */
    public record WriteResult(boolean success, String message, long latencyMs) {
    }
}
