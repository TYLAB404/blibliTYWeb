package com.tylab.biliweb.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Web MVC 配置：注册全局访问口令拦截器 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AccessAuthInterceptor accessAuthInterceptor;

    public WebConfig(AccessAuthInterceptor accessAuthInterceptor) {
        this.accessAuthInterceptor = accessAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accessAuthInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/system/access-info", "/api/system/verify-token");
    }
}
