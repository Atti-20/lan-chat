package com.lanchat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/** Central exact-origin CORS policy for the shared Web and desktop clients. */
@Configuration
public class CorsConfig {

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${cors.allowed-origins:" + ClientOriginDefaults.ALLOWED_ORIGINS + "}")
                      String[] allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (this.allowedOrigins.isEmpty() || this.allowedOrigins.contains("*")) {
            throw new IllegalArgumentException(
                    "CORS_ALLOWED_ORIGINS 必须包含明确的 Origin，不能留空或使用通配符");
        }
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of(
                "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "X-Request-ID"));
        configuration.setExposedHeaders(List.of(
                "X-Request-ID", "Content-Disposition"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
