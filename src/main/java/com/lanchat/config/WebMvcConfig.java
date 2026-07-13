package com.lanchat.config;

import com.lanchat.service.impl.FileServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

//    @Value("${file.path}")
//    private String filePath;
    private String filePath = System.getProperty("user.dir") + "/uploads/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        File dir = new File(filePath);
        if(!dir.exists()) {
            dir.mkdirs();
        }
        registry.addResourceHandler("/file/**")
                .addResourceLocations("file:" + filePath);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 无后缀 URL 转发到对应的静态 HTML 文件
        registry.addViewController("/chat").setViewName("forward:/chat.html");
        registry.addViewController("/welcome").setViewName("forward:/welcome.html");
    }
}
