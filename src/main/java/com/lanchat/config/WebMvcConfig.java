package com.lanchat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${file.path}")
    private String filePath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 将 /file/** 映射到本地文件目录，实现上传文件的访问
        registry.addResourceHandler("/file/**")
                .addResourceLocations("file:" + filePath);
    }
}
