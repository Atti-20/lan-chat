package com.lanchat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Vue 单页应用由 Vite 构建到 /static/app，保留原有入口 URL。
        registry.addViewController("/").setViewName("forward:/app/index.html");
        registry.addViewController("/app").setViewName("forward:/app/index.html");
        registry.addViewController("/app/").setViewName("forward:/app/index.html");
        registry.addViewController("/app/chat").setViewName("forward:/app/index.html");
        registry.addViewController("/app/welcome").setViewName("forward:/app/index.html");
        registry.addViewController("/chat").setViewName("forward:/app/index.html");
        registry.addViewController("/welcome").setViewName("forward:/app/index.html");
        // 旧版静态页面仍留在仓库作样式历史，但其 WebSocket 协议已经废弃；
        // 直接访问旧 .html 时也统一进入 V2 Vue 客户端。
        registry.addViewController("/index.html").setViewName("redirect:/app/");
        registry.addViewController("/chat.html").setViewName("redirect:/app/chat");
        registry.addViewController("/welcome.html").setViewName("redirect:/app/welcome");
    }
}
