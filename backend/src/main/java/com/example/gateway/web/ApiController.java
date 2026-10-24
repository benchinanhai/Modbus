package com.example.gateway.web;

import com.example.gateway.model.DeviceState;
import com.example.gateway.model.Dtos.CommandDto;
import com.example.gateway.model.Dtos.CommandRequest;
import com.example.gateway.model.Dtos.DeviceDetailDto;
import com.example.gateway.model.Dtos.DeviceDto;
import com.example.gateway.model.Dtos.GatewayDto;
import com.example.gateway.model.Dtos.ModbusProbeRequest;
import com.example.gateway.model.Dtos.ModbusProbeResult;
import com.example.gateway.model.Dtos.OverviewDto;
import com.example.gateway.model.Dtos.PointTableParseResult;
import com.example.gateway.model.Dtos.RealDeviceView;
import com.example.gateway.model.Dtos.SlaveStatusDto;
import com.example.gateway.model.Dtos.ThreadsDto;
import com.example.gateway.model.LogEntry;
import com.example.gateway.model.PointConfig;
import com.example.gateway.model.RealDeviceConfig;
import com.example.gateway.service.CommandService;
import com.example.gateway.service.ModbusSlaveService;
import com.example.gateway.service.ModbusTcpClient;
import com.example.gateway.service.PointTableService;
import com.example.gateway.service.RealDeviceService;
import com.example.gateway.service.RegisterService;
import com.example.gateway.store.GatewayStore;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台 REST 接口。
 *
 * <p>模拟设备与真实设备共用同一套读接口；真实设备额外提供“测试连接、接入、
 * 移除”三个管理接口。</p>
 *
 * @author xujin
 * @since 0.2.0
 */
@RestController
@RequestMapping("/api")
public class ApiController {
    private final GatewayStore store;
    private final RegisterService registers;
    private final CommandService commands;
    private final ModbusTcpClient modbusTcpClient;
    private final RealDeviceService realDevices;
    private final PointTableService pointTables;
    private final ModbusSlaveService slave;

    public ApiController(GatewayStore store, RegisterService registers, CommandService commands,
                         ModbusTcpClient modbusTcpClient, RealDeviceService realDevices,
                         PointTableService pointTables, ModbusSlaveService slave) {
        this.store = store;
        this.registers = registers;
        this.commands = commands;
        this.modbusTcpClient = modbusTcpClient;
        this.realDevices = realDevices;
        this.pointTables = pointTables;
        this.slave = slave;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "ts", System.currentTimeMillis());
    }

    @GetMapping("/overview")
    public OverviewDto overview() {
        return new OverviewDto(store.stats(), store.gatewayDtos(), store.recentEvents(6));
    }

    @GetMapping("/gateways")
    public List<GatewayDto> gateways() {
        return store.gatewayDtos();
    }

    @GetMapping("/devices")
    public List<DeviceDto> devices() {
        return store.deviceDtos();
    }

    @GetMapping("/devices/{id}")
    public DeviceDetailDto device(@PathVariable String id) {
        return registers.detail(requireDevice(id));
    }

    @GetMapping("/devices/{id}/history")
    public Map<String, Object> history(@PathVariable String id) {
        requireDevice(id);
        return Map.of("deviceId", id, "values", store.history(id));
    }

    @GetMapping("/threads")
    public ThreadsDto threads() {
        return store.threads();
    }

    @GetMapping("/logs")
    public List<LogEntry> logs(@RequestParam(defaultValue = "ALL") String level,
                               @RequestParam(defaultValue = "") String q) {
        return store.logs(level, q);
    }

    @GetMapping("/commands")
    public List<CommandDto> commands() {
        return store.commands();
    }

    @PostMapping("/commands/preview")
    public CommandDto preview(@RequestBody CommandRequest request) {
        return commands.preview(request);
    }

    @PostMapping("/commands")
    public CommandDto execute(@RequestBody CommandRequest request) {
        return commands.execute(request);
    }

    /**
     * 真实设备列表：配置 + 当前运行状态。
     */
    @GetMapping("/real-devices")
    public List<RealDeviceView> realDevices() {
        return realDevices.list();
    }

    /**
     * 一次性读取，用于接入前验证 TCP/RTU 连通性。
     */
    @PostMapping("/real-devices/test")
    public ModbusProbeResult testRealDevice(@RequestBody ModbusProbeRequest request) {
        try {
            return realDevices.test(request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    /**
     * 接入一台真实设备，立即开始轮询并持久化配置。
     */
    @PostMapping("/real-devices")
    public RealDeviceView addRealDevice(@RequestBody RealDeviceConfig config) {
        try {
            return realDevices.add(config);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * 移除真实设备，并停止空通道。
     */
    @DeleteMapping("/real-devices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeRealDevice(@PathVariable String id) {
        realDevices.remove(id);
    }

    /**
     * 替换真实设备的点表。
     */
    @PostMapping("/real-devices/{id}/points")
    public RealDeviceView replacePoints(@PathVariable String id,
                                        @RequestBody List<PointConfig> points) {
        try {
            return realDevices.setPoints(id, points);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * 解析上传的点表 xlsx，返回点位与逐行错误。
     */
    @PostMapping(value = "/points/parse", consumes = "multipart/form-data")
    public PointTableParseResult parsePoints(@RequestParam("file") MultipartFile file)
            throws IOException {
        try {
            return pointTables.parse(file);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * 下载点表模板 xlsx。
     */
    @GetMapping("/points/template")
    public void pointTemplate(HttpServletResponse response) throws IOException {
        response.setContentType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=point-template.xlsx");
        pointTables.writeTemplate(response.getOutputStream());
    }

    /**
     * 平台从站状态：端口、Unit 映射。
     */
    @GetMapping("/slave")
    public SlaveStatusDto slaveStatus() {
        return slave.status();
    }

    @GetMapping("/modbus/read")
    public Map<String, Object> readModbus(@RequestParam String host,
                                          @RequestParam(defaultValue = "502") int port,
                                          @RequestParam(defaultValue = "1") int unit,
                                          @RequestParam(defaultValue = "0") int address,
                                          @RequestParam(defaultValue = "2") int count) {
        try {
            int[] values = modbusTcpClient.readHoldingRegisters(host, port, unit, address, count);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("host", host);
            response.put("port", port);
            response.put("unit", unit);
            response.put("address", address);
            response.put("count", count);
            response.put("registers", values);
            return response;
        } catch (IOException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    private DeviceState requireDevice(String id) {
        DeviceState device = store.device(id);
        if (device == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown device " + id);
        }
        return device;
    }
}
