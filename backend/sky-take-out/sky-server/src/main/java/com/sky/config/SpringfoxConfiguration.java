package com.sky.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import springfox.documentation.spring.web.plugins.WebMvcRequestHandlerProvider;

import java.lang.reflect.Field;
import java.util.List;

/** Springfox 3 不能解析 Spring Boot 2.7 Actuator 使用的 PathPattern 路由。 */
@Configuration
public class SpringfoxConfiguration {

    @Bean
    public static BeanPostProcessor springfoxHandlerProviderPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof WebMvcRequestHandlerProvider) {
                    Field field = ReflectionUtils.findField(WebMvcRequestHandlerProvider.class, "handlerMappings");
                    if (field == null) {
                        throw new IllegalStateException("Springfox handlerMappings field not found");
                    }
                    ReflectionUtils.makeAccessible(field);
                    @SuppressWarnings("unchecked")
                    List<RequestMappingInfoHandlerMapping> mappings =
                            (List<RequestMappingInfoHandlerMapping>) ReflectionUtils.getField(field, bean);
                    //只过滤 Springfox 的扫描列表，不改变 MVC/Actuator 实际注册的路由。
                    if (mappings != null) {
                        mappings.removeIf(mapping -> mapping.getPatternParser() != null);
                    }
                }
                return bean;
            }
        };
    }
}
