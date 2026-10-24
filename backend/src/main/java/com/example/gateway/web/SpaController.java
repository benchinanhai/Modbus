package com.example.gateway.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 路由回退。
 *
 * <p>容器部署时，前端构建产物被复制到 {@code classpath:/static/}；刷新
 * {@code /devices/T-01} 这类前端路由时，需要回退到 index.html，否则会 404。
 * API 与 WebSocket 路径不在映射范围内。</p>
 *
 * @author xujin
 * @since 0.2.0
 */
@Controller
public class SpaController {
    @GetMapping({"/", "/overview", "/gateways", "/devices", "/devices/**",
            "/pipeline", "/threads", "/onboard", "/commands", "/logs", "/docs", "/plan"})
    public String index() {
        return "forward:/index.html";
    }
}
