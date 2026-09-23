package com.sky.config;

import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.json.JacksonObjectMapper;
import com.sky.utils.LocalFileUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;
import org.springframework.web.servlet.resource.PathResourceResolver;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 配置类，注册web层相关组件
 */
@Configuration
@Slf4j
public class WebMvcConfiguration extends WebMvcConfigurationSupport {

    @Autowired
    private JwtTokenAdminInterceptor jwtTokenAdminInterceptor;

    /**
     * 注入上传工具还有一层作用：保证"创建上传目录"先于下面注册静态资源映射执行。
     */
    @Autowired
    private LocalFileUtil localFileUtil;

    /**
     * 注册自定义拦截器
     *
     * @param registry
     */
    protected void addInterceptors(InterceptorRegistry registry) {
        log.info("开始注册自定义拦截器...");
        //只拦 /admin/**。上传文件的访问路径必须留在拦截范围外：img 标签发出的请求带不上 token，
        //把它加进来会让全站图片直接裂掉。
        registry.addInterceptor(jwtTokenAdminInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns("/admin/employee/login");
    }

    /**
     * 通过knife4j生成接口文档  封装了swagger的框架
     * @return
     */
    @Bean
    public Docket docket() {
        log.info("准备生成接口文档");
        ApiInfo apiInfo = new ApiInfoBuilder()
                .title("苍穹外卖项目接口文档")
                .version("2.0")
                .description("苍穹外卖项目接口文档")
                .build();
        Docket docket = new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(apiInfo)
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.sky.controller"))
                .paths(PathSelectors.any())
                .build();
        return docket;
    }

    /**
     * 扩展消息转换器，让接口按 JacksonObjectMapper 约定输出日期时间。
     * 不注册时 LocalDateTime 会被序列化成 [2026,9,22,14,59] 这样的数组，
     * 与接口文档要求的字符串不符，前端“最后操作时间”列也无法直接展示。
     *
     * @param converters
     */
    protected void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        log.info("开始扩展消息转换器...");
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(new JacksonObjectMapper());
        //放在最前面，优先于默认的 Jackson 转换器
        converters.add(0, converter);
    }

    /**
     * 设置静态资源映射
     * @param registry
     */
    protected void addResourceHandlers(ResourceHandlerRegistry registry) {
        log.info("开始设置静态资源映射");
        //swagger接口测试网址设置    localhost：8080/doc.html
        registry.addResourceHandler("/doc.html").addResourceLocations("classpath:/META-INF/resources/");
        registry.addResourceHandler("/webjars/**").addResourceLocations("classpath:/META-INF/resources/webjars/");
        addUploadResourceHandler(registry);
    }

    /**
     * 上传文件（本地磁盘）的访问入口，如 /uploads/2026/09/23/xxx.jpg。
     * <p>
     * 注意：本类继承了 WebMvcConfigurationSupport，Spring Boot 的默认静态资源配置已整体退避，
     * spring.web.resources.* / spring.mvc.static-path-pattern 配了也不生效（而且不会报错），
     * 要换上传目录只能改 sky.upload.dir 或这里。
     */
    private void addUploadResourceHandler(ResourceHandlerRegistry registry) {
        Path uploadRoot = localFileUtil.getRoot();
        registry.addResourceHandler(LocalFileUtil.URL_PREFIX + "/**")
                .addResourceLocations(LocalFileUtil.fileLocation(uploadRoot))
                //resourceChain(false) 只为拿到注册 resolver 的入口，不启用资源缓存
                .resourceChain(false)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource resource = super.getResource(resourcePath, location);
                        //这个入口匿名可读（这是必须的），所以不依赖框架自身的路径规范化行为，
                        //明确要求解析结果仍在上传目录内，挡掉 ../ 与各种编码变形导致的越界读取
                        if (resource == null || !resource.getFile().toPath().normalize().startsWith(uploadRoot)) {
                            return null;
                        }
                        return resource;
                    }
                });
    }
}
