package com.secureclipboard.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    /**
     * Serve static assets with revalidation instead of a fixed lifetime. The page
     * pulls fourteen ES modules by plain relative path, so a version query on the
     * entry point cannot invalidate them after a deploy. A conditional request costs
     * a round trip and answers 304 while the file is unchanged.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        CacheControl revalidate = CacheControl.noCache();
        registry.addResourceHandler("/css/**")
                .addResourceLocations("classpath:/static/css/")
                .setCacheControl(revalidate);
        registry.addResourceHandler("/js/**")
                .addResourceLocations("classpath:/static/js/")
                .setCacheControl(revalidate);
        registry.addResourceHandler("/img/**")
                .addResourceLocations("classpath:/static/img/")
                .setCacheControl(revalidate);
    }
}
