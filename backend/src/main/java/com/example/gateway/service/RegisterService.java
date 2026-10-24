package com.example.gateway.service;

import com.example.gateway.model.DeviceState;
import com.example.gateway.model.Dtos.DeviceDetailDto;
import com.example.gateway.model.Dtos.LongStringChunkDto;
import com.example.gateway.model.Dtos.LongStringControlDto;
import com.example.gateway.model.Dtos.LongStringDto;
import com.example.gateway.model.Dtos.LongStringRegisterDto;
import com.example.gateway.model.Dtos.RegisterCellDto;
import com.example.gateway.model.Dtos.RegisterGroupDto;
import com.example.gateway.model.PointConfig;
import com.example.gateway.store.GatewayStore;
import com.example.gateway.util.Crc16;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 寄存器编组、报文帧与长字符串模型，全部由点表驱动。
 *
 * <p>每个点位生成一个编组：16 位点位占 1 个寄存器，32 位点位占 2 个；
 * 原始字节优先取真实设备最近一次读到的寄存器，模拟设备由工程值按
 * 类型/字序/缩放反推。帧类型由设备 transport 决定：TCP 用 MBAP，RTU 用 CRC。</p>
 *
 * <p>长字符串不是 Modbus 原生类型，这里用“控制区 + 数据区”的邮箱模型演示：
 * 控制区保存长度、片数、片号、状态与 CRC，数据区每片最多 16 个寄存器。</p>
 *
 * @author xujin
 * @since 0.1.0
 */
@Service
public class RegisterService {
    private static final int LONG_STRING_CONTROL_BASE = 0x0100;
    private static final int LONG_STRING_DATA_BASE = 0x0110;
    private static final int CHUNK_REGISTERS = 16;
    private static final int FIRMWARE_ADDRESS = 16;
    private static final int FIRMWARE_REGISTERS = 4;
    private static final int DISPLAY_TRANSACTION_ID = 1;
    private static final int FUNCTION_READ_HOLDING = 3;
    private static final int FUNCTION_READ_INPUT = 4;
    private static final int FUNCTION_WRITE_SINGLE = 6;
    private static final int FUNCTION_WRITE_MULTIPLE = 16;
    private static final int BYTE_MASK = 0xFF;
    private static final int SHORT_MASK = 0xFFFF;
    private static final String TRANSPORT_TCP = "TCP";
    private static final String EMPTY_CHAR = "·";

    private final GatewayStore store;

    public RegisterService(GatewayStore store) {
        this.store = store;
    }

    /**
     * 设备详情：设备快照 + 点表编组 + 长字符串模型。
     */
    public DeviceDetailDto detail(DeviceState device) {
        return new DeviceDetailDto(store.deviceDto(device), groups(device), longString(device));
    }

    /**
     * 按点表生成寄存器编组：每个点位一个编组，原始字节与工程值并列展示。
     */
    public List<RegisterGroupDto> groups(DeviceState device) {
        List<RegisterGroupDto> groups = new ArrayList<>();
        for (PointConfig point : sortedPoints(device)) {
            int count = point.registerCount();
            int[] raw = pointRaw(device, point);
            double value = device.value(point.key);
            int readFunction = point.isHolding() ? FUNCTION_READ_HOLDING : FUNCTION_READ_INPUT;
            groups.add(new RegisterGroupDto(point.key, point.name, point.typeLabel(),
                    point.address, count, point.writable,
                    cells(point.address, raw), bytesText(bytesOf(raw)),
                    formatValue(point, value),
                    readRequest(device, readFunction, point.address, count),
                    readResponseFrame(device, readFunction, raw),
                    point.writable
                            ? writeRequest(device, writeFunction(count), point.address, raw)
                            : ""));
        }
        return groups;
    }

    /**
     * 长字符串邮箱模型。
     *
     * <p>数据区与控制区使用固定地址，便于演示和跨设备复用；设备编号来自
     * 设备元数据，不要求从站真的实现了这套邮箱。</p>
     */
    public LongStringDto longString(DeviceState device) {
        byte[] raw = device.serial.getBytes(StandardCharsets.UTF_8);
        int[] registers = pack(raw);
        int chunkCount = (registers.length + CHUNK_REGISTERS - 1) / CHUNK_REGISTERS;
        int crc = Crc16.of(raw);

        List<LongStringControlDto> control = List.of(
                new LongStringControlDto(LONG_STRING_CONTROL_BASE, "长度", raw.length,
                        String.valueOf(raw.length)),
                new LongStringControlDto(LONG_STRING_CONTROL_BASE + 1, "片数", chunkCount,
                        String.valueOf(chunkCount)),
                new LongStringControlDto(LONG_STRING_CONTROL_BASE + 2, "片号", 0, "0"),
                new LongStringControlDto(LONG_STRING_CONTROL_BASE + 3, "状态", 0, "空闲"),
                new LongStringControlDto(LONG_STRING_CONTROL_BASE + 4, "CRC16", crc, hex(crc))
        );

        List<LongStringRegisterDto> registerDtos = new ArrayList<>();
        for (int index = 0; index < registers.length; index++) {
            registerDtos.add(new LongStringRegisterDto(LONG_STRING_DATA_BASE + index,
                    registers[index], charsOf(registers[index])));
        }

        List<LongStringChunkDto> chunks = new ArrayList<>();
        List<String> writeFrames = new ArrayList<>();
        for (int index = 0; index < chunkCount; index++) {
            int startRegister = index * CHUNK_REGISTERS;
            int count = Math.min(CHUNK_REGISTERS, registers.length - startRegister);
            int[] part = new int[count];
            System.arraycopy(registers, startRegister, part, 0, count);
            chunks.add(new LongStringChunkDto(index, startRegister, count, count * 2,
                    String.format(Locale.ROOT, "0x%04X–0x%04X", LONG_STRING_DATA_BASE + startRegister,
                            LONG_STRING_DATA_BASE + startRegister + count - 1)));
            writeFrames.add(writeRequest(device, FUNCTION_WRITE_MULTIPLE,
                    LONG_STRING_DATA_BASE + startRegister, part));
        }

        return new LongStringDto(device.serial, raw.length, registers.length, chunkCount,
                control, registerDtos, chunks, crc,
                readRequest(device, FUNCTION_READ_HOLDING, LONG_STRING_DATA_BASE, registers.length),
                readResponseFrame(device, FUNCTION_READ_HOLDING, registers),
                writeFrames);
    }

    /**
     * 单个点位的原始寄存器：真实设备取最近一次读到的值，否则按工程值反推。
     */
    public static int[] pointRaw(DeviceState device, PointConfig point) {
        int count = point.registerCount();
        int[] fallback = encodeRegs(point, device.value(point.key));
        if (!device.real || device.lastReadRegisters == null) {
            return fallback;
        }
        int offset = point.address - device.lastReadBase;
        if (offset < 0 || offset + count > device.lastReadRegisters.length) {
            return fallback;
        }
        int[] result = new int[count];
        System.arraycopy(device.lastReadRegisters, offset, result, 0, count);
        return result;
    }

    /**
     * 按类型/字序/缩放把工程值编码为寄存器。
     */
    public static int[] encodeRegs(PointConfig point, double engineering) {
        double rawValue = point.scale == 0 ? engineering : engineering / point.scale;
        if (PointConfig.TYPE_FLOAT32.equals(point.type)) {
            return wordsFromBytes(bytesOfFloat((float) rawValue), point.order);
        }
        long raw = Math.round(rawValue);
        if (PointConfig.TYPE_INT16.equals(point.type)) {
            return new int[]{(short) raw & SHORT_MASK};
        }
        if (PointConfig.TYPE_UINT16.equals(point.type)) {
            return new int[]{(int) (raw & SHORT_MASK)};
        }
        return wordsFromBytes(bytesOfInt((int) raw), point.order);
    }

    /**
     * 按类型/字序/缩放把寄存器解码为工程值。
     */
    /**
     * 按类型/字序/缩放把寄存器解码为工程值。
     *
     * <p>16 位类型只读 1 个寄存器；32 位类型按字序读 2 个；int16/int32 做符号扩展。</p>
     */
    public static double decodePoint(PointConfig point, int[] registers, int offset) {
        if (PointConfig.TYPE_FLOAT32.equals(point.type)) {
            int bits = (int) wordFromBytes(registers, offset, point.order);
            return Float.intBitsToFloat(bits) * point.scale;
        }
        long raw;
        if (PointConfig.TYPE_INT16.equals(point.type)) {
            raw = (short) registers[offset];
        } else if (PointConfig.TYPE_UINT16.equals(point.type)) {
            raw = registers[offset] & SHORT_MASK;
        } else if (PointConfig.TYPE_INT32.equals(point.type)) {
            raw = (int) wordFromBytes(registers, offset, point.order);
        } else {
            raw = wordFromBytes(registers, offset, point.order);
        }
        return point.scale == 0 ? raw : raw * point.scale;
    }

    private static List<PointConfig> sortedPoints(DeviceState device) {
        List<PointConfig> points = new ArrayList<>(device.getPoints());
        points.sort(Comparator.comparingInt(point -> point.address));
        return points;
    }

    /**
     * 生成读请求帧；FC03 读保持寄存器，FC04 读输入寄存器。
     */
    public static String readRequest(DeviceState device, int function, int address, int count) {
        int[] pdu = {function, (address >> 8) & BYTE_MASK, address & BYTE_MASK,
                (count >> 8) & BYTE_MASK, count & BYTE_MASK};
        return frameFor(device, pdu);
    }

    /**
     * 生成读响应帧，用于界面展示。
     */
    public static String readResponseFrame(DeviceState device, int function, int[] raw) {
        int[] data = bytesOf(raw);
        int[] pdu = new int[2 + data.length];
        pdu[0] = function;
        pdu[1] = data.length;
        System.arraycopy(data, 0, pdu, 2, data.length);
        return frameFor(device, pdu);
    }

    /**
     * 生成写请求帧；FC06 写单个，FC16 写多个。
     */
    public static String writeRequest(DeviceState device, int function, int address, int[] values) {
        if (function == FUNCTION_WRITE_SINGLE) {
            return frameFor(device, new int[]{FUNCTION_WRITE_SINGLE,
                    (address >> 8) & BYTE_MASK, address & BYTE_MASK,
                    (values[0] >> 8) & BYTE_MASK, values[0] & BYTE_MASK});
        }
        int[] data = bytesOf(values);
        int[] pdu = new int[6 + data.length];
        pdu[0] = FUNCTION_WRITE_MULTIPLE;
        pdu[1] = (address >> 8) & BYTE_MASK;
        pdu[2] = address & BYTE_MASK;
        pdu[3] = (values.length >> 8) & BYTE_MASK;
        pdu[4] = values.length & BYTE_MASK;
        pdu[5] = data.length;
        System.arraycopy(data, 0, pdu, 6, data.length);
        return frameFor(device, pdu);
    }

    private static int writeFunction(int count) {
        return count == 1 ? FUNCTION_WRITE_SINGLE : FUNCTION_WRITE_MULTIPLE;
    }

    private static String frameFor(DeviceState device, int[] pdu) {
        if (device != null && TRANSPORT_TCP.equalsIgnoreCase(device.transport)) {
            return tcpFrame(device.slaveId, pdu);
        }
        int slaveId = device == null ? 1 : device.slaveId;
        return rtuFrame(slaveId, pdu);
    }

    private static String rtuFrame(int slaveId, int[] pdu) {
        int[] frame = new int[1 + pdu.length];
        frame[0] = slaveId;
        System.arraycopy(pdu, 0, frame, 1, pdu.length);
        int crc = Crc16.of(frame);
        StringBuilder builder = new StringBuilder();
        for (int value : frame) {
            builder.append(hexByte(value)).append(' ');
        }
        builder.append(hexByte(crc & BYTE_MASK)).append(' ')
                .append(hexByte((crc >> 8) & BYTE_MASK));
        return builder.toString();
    }

    private static String tcpFrame(int slaveId, int[] pdu) {
        int length = 1 + pdu.length;
        int[] frame = new int[7 + pdu.length];
        frame[0] = (DISPLAY_TRANSACTION_ID >> 8) & BYTE_MASK;
        frame[1] = DISPLAY_TRANSACTION_ID & BYTE_MASK;
        frame[2] = 0;
        frame[3] = 0;
        frame[4] = (length >> 8) & BYTE_MASK;
        frame[5] = length & BYTE_MASK;
        frame[6] = slaveId;
        System.arraycopy(pdu, 0, frame, 7, pdu.length);
        StringBuilder builder = new StringBuilder();
        for (int value : frame) {
            builder.append(hexByte(value)).append(' ');
        }
        return builder.toString().trim();
    }

    private static List<RegisterCellDto> cells(int start, int[] registers) {
        List<RegisterCellDto> cells = new ArrayList<>();
        for (int index = 0; index < registers.length; index++) {
            cells.add(new RegisterCellDto(start + index, registers[index],
                    charsOf(registers[index])));
        }
        return cells;
    }

    private static int[] pack(byte[] bytes) {
        int[] registers = new int[(bytes.length + 1) / 2];
        for (int index = 0; index < registers.length; index++) {
            int high = bytes[index * 2] & BYTE_MASK;
            int low = (index * 2 + 1 < bytes.length) ? bytes[index * 2 + 1] & BYTE_MASK : 0;
            registers[index] = (high << 8) | low;
        }
        return registers;
    }

    private static int[] bytesOf(int[] registers) {
        int[] bytes = new int[registers.length * 2];
        for (int index = 0; index < registers.length; index++) {
            bytes[index * 2] = (registers[index] >> 8) & BYTE_MASK;
            bytes[index * 2 + 1] = registers[index] & BYTE_MASK;
        }
        return bytes;
    }

    private static String bytesText(int[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < bytes.length; index++) {
            if (index > 0) {
                builder.append(' ');
            }
            builder.append(hexByte(bytes[index]));
        }
        return builder.toString();
    }

    private static String charsOf(int value) {
        int high = (value >> 8) & BYTE_MASK;
        int low = value & BYTE_MASK;
        return (high == 0 ? EMPTY_CHAR : String.valueOf((char) high))
                + (low == 0 ? EMPTY_CHAR : String.valueOf((char) low));
    }

    private static String hex(int value) {
        return String.format("%04X", value & SHORT_MASK);
    }

    private static String hexByte(int value) {
        return String.format("%02X", value & BYTE_MASK);
    }

    private static String formatValue(PointConfig point, double value) {
        if (PointConfig.TYPE_FLOAT32.equals(point.type)) {
            return String.format(Locale.ROOT, "%.2f", value);
        }
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static int[] bytesOfFloat(float value) {
        int bits = Float.floatToIntBits(value);
        return new int[]{(bits >> 24) & BYTE_MASK, (bits >> 16) & BYTE_MASK,
                (bits >> 8) & BYTE_MASK, bits & BYTE_MASK};
    }

    private static int[] bytesOfInt(int value) {
        return new int[]{(value >> 24) & BYTE_MASK, (value >> 16) & BYTE_MASK,
                (value >> 8) & BYTE_MASK, value & BYTE_MASK};
    }

    /**
     * ABCD = A B C D；CDAB = C D A B；BADC = B A D C；DCBA = D C B A。
     */
    private static int[] wordsFromBytes(int[] bytes, String order) {
        int a = bytes[0], b = bytes[1], c = bytes[2], d = bytes[3];
        int[] ordered = switch (order) {
            case "CDAB" -> new int[]{c, d, a, b};
            case "BADC" -> new int[]{b, a, d, c};
            case "DCBA" -> new int[]{d, c, b, a};
            default -> new int[]{a, b, c, d};
        };
        return new int[]{(ordered[0] << 8) | ordered[1], (ordered[2] << 8) | ordered[3]};
    }

    private static long wordFromBytes(int[] registers, int offset, String order) {        int a = registers[offset] >> 8 & BYTE_MASK;
        int b = registers[offset] & BYTE_MASK;
        int c = registers[offset + 1] >> 8 & BYTE_MASK;
        int d = registers[offset + 1] & BYTE_MASK;
        int[] ordered = switch (order) {
            case "CDAB" -> new int[]{c, d, a, b};
            case "BADC" -> new int[]{b, a, d, c};
            case "DCBA" -> new int[]{d, c, b, a};
            default -> new int[]{a, b, c, d};
        };
        long value = 0;
        for (int item : ordered) {
            value = (value << 8) | item;
        }
        return value;
    }
}
