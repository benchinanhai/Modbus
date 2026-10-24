package com.example.gateway.service;

import org.springframework.stereotype.Service;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 最小 Modbus TCP 客户端，只实现保持寄存器的 FC03 / FC06 / FC16。
 *
 * <p>每次事务独立建连，避免一个设备断线影响其他设备；真实生产环境可以替换为
 * 连接池或 j2mod，但协议字节的组装与校验逻辑保持不变。</p>
 *
 * <p>MBAP 头：事务 ID(2) + 协议 ID(2，固定 0) + 长度(2) + 单元 ID(1)，
 * 不含 CRC；这与 RTU 帧有本质区别。</p>
 *
 * @author xujin
 * @since 0.2.0
 */
@Service
public class ModbusTcpClient {
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 2000;
    private static final int MBAP_HEADER_LENGTH = 7;
    private static final int PROTOCOL_ID = 0;
    private static final int FUNCTION_READ_HOLDING = 0x03;
    private static final int FUNCTION_READ_INPUT = 0x04;
    private static final int FUNCTION_WRITE_SINGLE = 0x06;
    private static final int FUNCTION_WRITE_MULTIPLE = 0x10;
    private static final int MAX_READ_REGISTERS = 125;
    private static final int MAX_WRITE_REGISTERS = 123;
    private static final int EXCEPTION_FLAG = 0x80;
    private static final AtomicInteger TRANSACTION_ID = new AtomicInteger(1);

    /**
     * 读保持寄存器（FC03）。
     *
     * @param host    对端地址
     * @param port    端口
     * @param unitId  从站地址
     * @param address 起始地址（0 基）
     * @param count   寄存器数量，1–125
     * @return 寄存器值
     * @throws IOException 网络、超时或 Modbus 异常响应
     */
    public int[] readHoldingRegisters(String host, int port, int unitId, int address, int count)
            throws IOException {
        return readRegisters(host, port, unitId, FUNCTION_READ_HOLDING, address, count);
    }

    /**
     * 读寄存器；function 3 = 保持寄存器，4 = 输入寄存器。
     */
    public int[] readRegisters(String host, int port, int unitId, int function,
                               int address, int count) throws IOException {
        if (function != FUNCTION_READ_HOLDING && function != FUNCTION_READ_INPUT) {
            throw new IllegalArgumentException("function must be 3 or 4");
        }
        if (count < 1 || count > MAX_READ_REGISTERS) {
            throw new IllegalArgumentException("count must be 1.." + MAX_READ_REGISTERS);
        }
        byte[] response = transact(host, port, unitId,
                pdu(function, address >> 8, address, count >> 8, count));
        if ((response[0] & 0xFF) != function) {
            throw new IOException("unexpected function " + (response[0] & 0xFF));
        }
        int byteCount = response[1] & 0xFF;
        if (byteCount != count * 2 || response.length < 2 + byteCount) {
            throw new IOException("invalid byte count " + byteCount + " for " + count + " registers");
        }
        int[] registers = new int[count];
        for (int index = 0; index < count; index++) {
            registers[index] = ((response[2 + index * 2] & 0xFF) << 8)
                    | (response[3 + index * 2] & 0xFF);
        }
        return registers;
    }

    /**
     * 写单个寄存器（FC06），并校验从站回显。
     */
    public void writeSingleRegister(String host, int port, int unitId, int address, int value)
            throws IOException {
        byte[] response = transact(host, port, unitId,
                pdu(FUNCTION_WRITE_SINGLE, address >> 8, address, value >> 8, value));
        if ((response[0] & 0xFF) != FUNCTION_WRITE_SINGLE || response.length < 5) {
            throw new IOException("invalid FC06 response");
        }
        int echoAddress = ((response[1] & 0xFF) << 8) | (response[2] & 0xFF);
        int echoValue = ((response[3] & 0xFF) << 8) | (response[4] & 0xFF);
        if (echoAddress != address || echoValue != value) {
            throw new IOException("FC06 echo mismatch");
        }
    }

    /**
     * 写多个寄存器（FC16），并校验从站回显的地址与数量。
     */
    public void writeMultipleRegisters(String host, int port, int unitId, int address, int[] values)
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
        byte[] response = transact(host, port, unitId, request);
        if ((response[0] & 0xFF) != FUNCTION_WRITE_MULTIPLE || response.length < 5) {
            throw new IOException("invalid FC16 response");
        }
        int echoAddress = ((response[1] & 0xFF) << 8) | (response[2] & 0xFF);
        int echoCount = ((response[3] & 0xFF) << 8) | (response[4] & 0xFF);
        if (echoAddress != address || echoCount != values.length) {
            throw new IOException("FC16 echo mismatch");
        }
    }

    /**
     * 执行一次 MBAP 事务，返回响应 PDU（不含 MBAP 头）。
     */
    private byte[] transact(String host, int port, int unitId, int[] requestPdu) throws IOException {
        int transaction = TRANSACTION_ID.getAndIncrement() & 0xFFFF;
        int length = 1 + requestPdu.length;
        byte[] request = new byte[MBAP_HEADER_LENGTH + requestPdu.length];
        request[0] = (byte) (transaction >> 8);
        request[1] = (byte) transaction;
        request[2] = 0;
        request[3] = 0;
        request[4] = (byte) (length >> 8);
        request[5] = (byte) length;
        request[6] = (byte) unitId;
        for (int index = 0; index < requestPdu.length; index++) {
            request[MBAP_HEADER_LENGTH + index] = (byte) requestPdu[index];
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream output = socket.getOutputStream();
            output.write(request);
            output.flush();

            DataInputStream input = new DataInputStream(socket.getInputStream());
            byte[] header = new byte[MBAP_HEADER_LENGTH];
            input.readFully(header);
            int responseTransaction = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
            int protocol = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            int responseLength = ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);
            if (responseTransaction != transaction || protocol != PROTOCOL_ID || responseLength < 2) {
                throw new IOException("invalid MBAP response");
            }
            byte[] responsePdu = new byte[responseLength - 1];
            input.readFully(responsePdu);
            int function = responsePdu[0] & 0xFF;
            if ((function & EXCEPTION_FLAG) != 0) {
                int exceptionCode = responsePdu.length > 1 ? responsePdu[1] & 0xFF : -1;
                throw new IOException("Modbus exception code " + exceptionCode);
            }
            return responsePdu;
        }
    }

    private static int[] pdu(int... values) {
        int[] result = new int[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = values[index] & 0xFF;
        }
        return result;
    }
}
