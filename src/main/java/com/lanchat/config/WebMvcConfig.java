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
        registry.addViewController("/chat").setViewName("forward:/app/index.html");
        registry.addViewController("/welcome").setViewName("forward:/app/index.html");
    }
}
