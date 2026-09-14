package com.rappi.buyer.trace;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TraceInterceptor trace;

    public WebConfig(TraceInterceptor trace) {
        this.trace = trace;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(trace).addPathPatterns("/tools/**");
    }
}
