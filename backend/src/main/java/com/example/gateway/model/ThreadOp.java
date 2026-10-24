package com.example.gateway.model;

/**
 * 通道内的一次事务，供“并发线程”页绘制泳道。
 *
 * @param id         自增编号
 * @param lane       通道线程名，例如 io-com3
 * @param deviceId   设备编号
 * @param start      开始时间（毫秒）
 * @param durationMs 耗时
 * @param kind       poll / write
 * @param bad        是否失败
 * @param stack      冲突展示时的堆叠层号
 * @author xujin
 * @since 0.2.0
 */
public record ThreadOp(long id, String lane, String deviceId, long start, double durationMs,
                       String kind, boolean bad, int stack) {
}
