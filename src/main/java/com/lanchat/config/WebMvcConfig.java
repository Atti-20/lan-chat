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
        // 保留旧版入口地址的兼容重定向，避免书签继续落到已移除的 V1 页面。
        registry.addViewController("/index.html").setViewName("redirect:/app/");
        registry.addViewController("/chat.html").setViewName("redirect:/app/chat");
        registry.addViewController("/welcome.html").setViewName("redirect:/app/welcome");
    }
}
