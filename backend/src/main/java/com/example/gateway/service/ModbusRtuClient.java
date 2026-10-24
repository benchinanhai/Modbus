package com.example.gateway.service;

import com.fazecast.jSerialComm.SerialPort;
import com.example.gateway.util.Crc16;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Arrays;

/**
 * 最小 Modbus RTU 客户端，基于 jSerialComm，只实现 FC03 / FC06 / FC16。
 *
 * <p>RTU 帧 = 从站地址(1) + PDU + CRC16(2，低字节在前)。
 * 串口是独占资源，因此本类由“一个通道一个 worker 线程”保证串行调用；
 * 每次事务打开/关闭串口，避免异常后留下半开句柄。</p>
 *
 * @author xujin
 * @since 0.2.0
 */
@Service
public class ModbusRtuClient {
    private static final int READ_TIMEOUT_MS = 1200;
    private static final int FRAME_GAP_MS = 10;
    private static final int MIN_FRAME_LENGTH = 5;
    private static final int FUNCTION_READ_HOLDING = 0x03;
    private static final int FUNCTION_READ_INPUT = 0x04;
    private static final int FUNCTION_WRITE_SINGLE = 0x06;
    private static final int FUNCTION_WRITE_MULTIPLE = 0x10;
    private static final int MAX_READ_REGISTERS = 125;
    private static final int MAX_WRITE_REGISTERS = 123;
    private static final int EXCEPTION_FLAG = 0x80;

    /**
     * 读保持寄存器（FC03）。
     */
    public int[] readHoldingRegisters(String portName, int baud, String parity, int dataBits,
                                      int stopBits, int slaveId, int address, int count)
            throws IOException {
        return readRegisters(portName, baud, parity, dataBits, stopBits, slaveId,
                FUNCTION_READ_HOLDING, address, count);
    }

    /**
     * 读寄存器；function 3 = 保持寄存器，4 = 输入寄存器。
     */
    public int[] readRegisters(String portName, int baud, String parity, int dataBits,
                               int stopBits, int slaveId, int function, int address, int count)
            throws IOException {
        if (function != FUNCTION_READ_HOLDING && function != FUNCTION_READ_INPUT) {
            throw new IllegalArgumentException("function must be 3 or 4");
        }
        if (count < 1 || count > MAX_READ_REGISTERS) {
            throw new IllegalArgumentException("count must be 1.." + MAX_READ_REGISTERS);
        }
        int[] request = pdu(function, address >> 8, address, count >> 8, count);
        byte[] response = transact(new RtuParams(portName, baud, parity, dataBits, stopBits, slaveId),
                request, MIN_FRAME_LENGTH + count * 2);
        if ((response[1] & 0xFF) != function) {
            throw new IOException("unexpected function " + (response[1] & 0xFF));
        }
        int byteCount = response[2] & 0xFF;
        if (byteCount != count * 2 || response.length < MIN_FRAME_LENGTH + byteCount) {
            throw new IOException("invalid byte count " + byteCount);
        }
        int[] registers = new int[count];
        for (int index = 0; index < count; index++) {
            registers[index] = ((response[3 + index * 2] & 0xFF) << 8)
                    | (response[4 + index * 2] & 0xFF);
        }
        return registers;
    }

    /**
     * 写单个寄存器（FC06），校验回显。
     */
    public void writeSingleRegister(String portName, int baud, String parity, int dataBits,
                                    int stopBits, int slaveId, int address, int value)
            throws IOException {
        int[] request = pdu(FUNCTION_WRITE_SINGLE, address >> 8, address, value >> 8, value);
        byte[] response = transact(new RtuParams(portName, baud, parity, dataBits, stopBits, slaveId),
                request, 8);
        int echoAddress = ((response[2] & 0xFF) << 8) | (response[3] & 0xFF);
        int echoValue = ((response[4] & 0xFF) << 8) | (response[5] & 0xFF);
        if ((response[1] & 0xFF) != FUNCTION_WRITE_SINGLE || echoAddress != address
                || echoValue != value) {
            throw new IOException("FC06 echo mismatch");
        }
    }

    /**
     * 写多个寄存器（FC16），校验回显的地址与数量。
     */
    public void writeMultipleRegisters(String portName, int baud, String parity, int dataBits,
                                       int stopBits, int slaveId, int address, int[] values)
            throws IOException {
        if (values == null || values.length < 1 || values.length > MAX_WRITE_REGISTERS) {
            throw new IllegalArgumentException("values must be 1.." + MAX_WRITE_REGISTERS);
        }
        int[] request = new int[6 + values.length * 2];
        request[0] = FUNCTION_WRITE_MULTIPLE;
        request[1] = (address >> 8) & 0xFF;
        request[2] = address & 0xFF;
        request[3] = (values.length >> 8) & 0xFF;
        request[4] = values.length & 0xFF;
        request[5] = values.length * 2;
        for (int index = 0; index < values.length; index++) {
            request[6 + index * 2] = (values[index] >> 8) & 0xFF;
            request[7 + index * 2] = values[index] & 0xFF;
        }
        byte[] response = transact(new RtuParams(portName, baud, parity, dataBits, stopBits, slaveId),
                request, 8);
        int echoAddress = ((response[2] & 0xFF) << 8) | (response[3] & 0xFF);
        int echoCount = ((response[4] & 0xFF) << 8) | (response[5] & 0xFF);
        if ((response[1] & 0xFF) != FUNCTION_WRITE_MULTIPLE || echoAddress != address
                || echoCount != values.length) {
            throw new IOException("FC16 echo mismatch");
        }
    }

    /**
     * 执行一次 RTU 事务。
     *
     * @param expectedLength 正常响应的总长度；异常响应固定 5 字节
     */
    private byte[] transact(RtuParams params, int[] requestPdu, int expectedLength) throws IOException {
        int[] frame = new int[1 + requestPdu.length + 2];
        frame[0] = params.slaveId;
        System.arraycopy(requestPdu, 0, frame, 1, requestPdu.length);
        int crc = Crc16.of(Arrays.copyOf(frame, frame.length - 2));
        frame[frame.length - 2] = crc & 0xFF;
        frame[frame.length - 1] = (crc >> 8) & 0xFF;

        byte[] request = new byte[frame.length];
        for (int index = 0; index < frame.length; index++) {
            request[index] = (byte) frame[index];
        }

        SerialPort port = SerialPort.getCommPort(params.portName);
        port.setComPortParameters(params.baud, params.dataBits,
                params.stopBits == 2 ? SerialPort.TWO_STOP_BITS : SerialPort.ONE_STOP_BIT,
                parityCode(params.parity));
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, READ_TIMEOUT_MS, 0);
        if (!port.openPort()) {
            throw new IOException("cannot open serial port " + params.portName);
        }
        try {
            port.writeBytes(request, request.length);
            // 3.5 字符静默；9600 bps 下约 4 ms，这里留出余量。
            Thread.sleep(FRAME_GAP_MS);
            byte[] response = new byte[expectedLength];
            int received = port.readBytes(response, response.length);
            if (received < MIN_FRAME_LENGTH) {
                throw new IOException("RTU timeout, received " + received + " bytes");
            }
            if ((response[1] & EXCEPTION_FLAG) != 0) {
                throw new IOException("Modbus exception code " + (response[2] & 0xFF));
            }
            if (received < expectedLength) {
                throw new IOException("short RTU response " + received + "/" + expectedLength);
            }
            int calculated = Crc16.of(Arrays.copyOf(response, received - 2));
            int actual = (response[received - 2] & 0xFF) | ((response[received - 1] & 0xFF) << 8);
            if (calculated != actual) {
                throw new IOException("RTU CRC mismatch");
            }
            return Arrays.copyOf(response, received);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("RTU transaction interrupted", e);
        } finally {
            port.closePort();
        }
    }

    private static int parityCode(String parity) {
        if ("E".equalsIgnoreCase(parity)) {
            return SerialPort.EVEN_PARITY;
        }
        if ("O".equalsIgnoreCase(parity)) {
            return SerialPort.ODD_PARITY;
        }
        return SerialPort.NO_PARITY;
    }

    private static int[] pdu(int... values) {
        int[] result = new int[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = values[index] & 0xFF;
        }
        return result;
    }

    private record RtuParams(String portName, int baud, String parity, int dataBits,
                             int stopBits, int slaveId) {
    }
}
