package com.example.gateway.model;

/**
 * 结构化日志条目。
 *
 * @param id      自增编号
 * @param ts      时间戳（毫秒）
 * @param level   INFO / WARN / ERROR
 * @param source  设备或网关编号
 * @param message 日志正文
 * @param traceId 链路追踪 ID，同一次采集/指令共享
 * @author xujin
 * @since 0.1.0
 */
public record LogEntry(long id, long ts, String level, String source, String message, String traceId) {
}
