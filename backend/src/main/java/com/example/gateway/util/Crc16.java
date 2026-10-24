package com.example.gateway.util;

/**
 * Modbus CRC16 实现：初值 0xFFFF，多项式 0xA001，线路上低字节在前。
 *
 * @author xujin
 * @since 0.1.0
 */
public final class Crc16 {
    private Crc16() {
    }

    public static int of(int[] bytes) {
        int crc = 0xFFFF;
        for (int value : bytes) {
            crc ^= value & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 1) != 0) {
                    crc = (crc >> 1) ^ 0xA001;
                } else {
                    crc >>= 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    public static int of(byte[] bytes) {
        int[] values = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            values[i] = bytes[i] & 0xFF;
        }
        return of(values);
    }
}
