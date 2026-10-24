package com.example.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 点表中的一个点位。
 *
 * <p>点表是平台的核心：它把“寄存器地址”翻译成有业务含义的字段，前端、
 * 后端、从站镜像都由它驱动。所有地址均为 0 基；32 位类型占两个寄存器，
 * 字序只对 32 位类型生效。</p>
 *
 * <p>读使用 FC03（保持寄存器）或 FC04（输入寄存器）；可写点位通过
 * FC06（单寄存器）/ FC16（多寄存器）下发。</p>
 *
 * @author xujin
 * @since 0.2.0
 */
public class PointConfig {
    public static final String TYPE_INT16 = "int16";
    public static final String TYPE_UINT16 = "uint16";
    public static final String TYPE_INT32 = "int32";
    public static final String TYPE_UINT32 = "uint32";
    public static final String TYPE_FLOAT32 = "float32";
    private static final Set<String> VALID_TYPES =
            Set.of(TYPE_INT16, TYPE_UINT16, TYPE_INT32, TYPE_UINT32, TYPE_FLOAT32);
    private static final Set<String> VALID_ORDERS =
            Set.of("ABCD", "CDAB", "BADC", "DCBA");
    private static final int FUNCTION_READ_HOLDING = 3;
    private static final int FUNCTION_READ_INPUT = 4;
    private static final int MAX_ADDRESS = 0xFFFF;
    private static final int DEFAULT_SCALE = 1;

    /** 稳定标识，用于状态表；导入时可由后端生成。 */
    public String key;
    /** 业务名称。 */
    public String name;
    /** 读功能码：3 = 保持寄存器，4 = 输入寄存器。 */
    public int function = FUNCTION_READ_HOLDING;
    /** 寄存器地址（0 基）。 */
    public int address;
    /** 数据类型。 */
    public String type = TYPE_UINT16;
    /** 32 位类型的字序。 */
    public String order = "ABCD";
    /** 工程值 = 原始值 × 缩放。 */
    public double scale = DEFAULT_SCALE;
    /** 单位。 */
    public String unit = "";
    /** 是否可通过 FC06/FC16 写入。 */
    public boolean writable = false;

    public PointConfig() {
    }

    public PointConfig(String key, String name, int function, int address, String type,
                       String order, double scale, String unit, boolean writable) {
        this.key = key;
        this.name = name;
        this.function = function;
        this.address = address;
        this.type = type;
        this.order = order;
        this.scale = scale;
        this.unit = unit;
        this.writable = writable;
        normalize();
    }

    /**
     * 平台默认点表：温度、湿度、电流、设定值、报警上下限。
     */
    public static List<PointConfig> defaults() {
        List<PointConfig> list = new ArrayList<>();
        list.add(new PointConfig("temp", "温度", 3, 0, TYPE_FLOAT32, "ABCD", 1.0, "°C", false));
        list.add(new PointConfig("hum", "湿度", 3, 2, TYPE_INT16, "ABCD", 0.1, "%", false));
        list.add(new PointConfig("cur", "电流", 3, 3, TYPE_INT16, "ABCD", 0.1, "mA", false));
        list.add(new PointConfig("sp", "设定值", 3, 8, TYPE_UINT16, "ABCD", 0.1, "", true));
        list.add(new PointConfig("alarmLo", "报警下限", 3, 9, TYPE_UINT16, "ABCD", 0.1, "", true));
        list.add(new PointConfig("alarmHi", "报警上限", 3, 10, TYPE_UINT16, "ABCD", 0.1, "", true));
        return list;
    }

    /**
     * 补齐默认值、统一大小写并校验类型。
     *
     * @throws IllegalArgumentException 类型 / 功能码 / 字序 / 地址不合法
     */
    public void normalize() {
        key = key == null ? "" : key.trim();
        name = name == null ? "" : name.trim();
        type = type == null ? TYPE_UINT16 : type.trim().toLowerCase(Locale.ROOT);
        if (!VALID_TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的数据类型: " + type);
        }
        order = order == null ? "ABCD" : order.trim().toUpperCase(Locale.ROOT);
        if (!VALID_ORDERS.contains(order)) {
            throw new IllegalArgumentException("不支持的字序: " + order);
        }
        if (function != FUNCTION_READ_HOLDING && function != FUNCTION_READ_INPUT) {
            throw new IllegalArgumentException("读功能码必须是 3 或 4: " + function);
        }
        if (address < 0 || address > MAX_ADDRESS) {
            throw new IllegalArgumentException("地址超出范围: " + address);
        }
        unit = unit == null ? "" : unit.trim();
    }

    @JsonIgnore
    public boolean is32Bit() {
        return TYPE_INT32.equals(type) || TYPE_UINT32.equals(type) || TYPE_FLOAT32.equals(type);
    }

    /** 占用的寄存器数量。 */
    public int registerCount() {
        return is32Bit() ? 2 : 1;
    }

    /** 是否保持寄存器（FC03）。 */
    @JsonIgnore
    public boolean isHolding() {
        return function == FUNCTION_READ_HOLDING;
    }

    /** 类型展示文本，带缩放。 */
    public String typeLabel() {
        if (scale == DEFAULT_SCALE) {
            return type;
        }
        return String.format(Locale.ROOT, "%s × %s", type, trimNumber(scale));
    }

    private static String trimNumber(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%s", value);
    }
}
