package com.example.gateway.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 真实设备接入配置。
 *
 * <p>点表 {@link #points} 是核心：前端“设备接入”页可编辑，也可通过 Excel
 * 导入；后端按它生成轮询报文、寄存器编组与从站镜像。配置持久化到
 * {@code config/real-devices.json}，容器部署时通过 volume 保留。</p>
 *
 * @author xujin
 * @since 0.2.0
 */
public class RealDeviceConfig {
    private static final String TRANSPORT_TCP = "TCP";
    private static final String TRANSPORT_RTU = "RTU";
    private static final int MIN_SLAVE_ID = 1;
    private static final int MAX_SLAVE_ID = 247;
    private static final int DEFAULT_TCP_PORT = 502;
    private static final int DEFAULT_BAUD = 9600;
    private static final int DEFAULT_DATA_BITS = 8;
    private static final int DEFAULT_STOP_BITS = 1;

    /** 平台内唯一编号，为空时由后端分配。 */
    public String id;
    /** 展示名称。 */
    public String name;
    /** 设备型号，仅用于界面展示。 */
    public String model;
    /** TCP 或 RTU。 */
    public String transport;
    /** TCP 对端地址。 */
    public String host;
    /** TCP 端口。 */
    public int port;
    /** RTU 串口，例如 /dev/ttyUSB0 或 COM3。 */
    public String serialPort;
    /** RTU 波特率。 */
    public int baud;
    /** RTU 校验位：N/E/O。 */
    public String parity;
    /** RTU 数据位。 */
    public int dataBits;
    /** RTU 停止位。 */
    public int stopBits;
    /** 从站地址（作为主站访问设备时使用）。 */
    public int slaveId;
    /** 设备编号，用于平台侧长字符串存储与解析演示。 */
    public String serial;
    /** 设备作为平台从站对外服务时的 Unit ID；默认与 slaveId 一致。 */
    public Integer slaveUnitId;
    /** 点表；为空时使用平台默认点表。 */
    public List<PointConfig> points = new ArrayList<>();

    /**
     * 补齐默认值并校验传输参数与点表。
     *
     * @throws IllegalArgumentException 传输参数或点表不合法
     */
    public void normalize() {
        transport = transport == null ? TRANSPORT_TCP : transport.trim().toUpperCase(Locale.ROOT);
        if (!TRANSPORT_TCP.equals(transport) && !TRANSPORT_RTU.equals(transport)) {
            transport = TRANSPORT_TCP;
        }
        name = isBlank(name) ? "真实设备" : name.trim();
        model = isBlank(model) ? "MB-REAL" : model.trim();
        host = isBlank(host) ? "127.0.0.1" : host.trim();
        port = port <= 0 ? DEFAULT_TCP_PORT : port;
        serialPort = isBlank(serialPort) ? "/dev/ttyUSB0" : serialPort.trim();
        baud = baud <= 0 ? DEFAULT_BAUD : baud;
        parity = isBlank(parity) ? "N" : parity.trim().toUpperCase(Locale.ROOT);
        dataBits = dataBits <= 0 ? DEFAULT_DATA_BITS : dataBits;
        stopBits = stopBits <= 0 ? DEFAULT_STOP_BITS : stopBits;
        slaveId = Math.max(MIN_SLAVE_ID, Math.min(MAX_SLAVE_ID, slaveId));
        slaveUnitId = slaveUnitId == null ? slaveId : slaveUnitId;
        if (isBlank(serial)) {
            serial = "DEV-REAL-" + (isBlank(id) ? "0001" : id);
        }
        if (points == null || points.isEmpty()) {
            points = PointConfig.defaults();
        }
        for (PointConfig point : points) {
            point.normalize();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
