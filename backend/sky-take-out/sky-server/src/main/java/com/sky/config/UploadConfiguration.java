package com.sky.config;

import com.sky.SkyApplication;
import com.sky.properties.UploadProperties;
import com.sky.utils.LocalFileUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;

/**
 * 本地文件上传配置：把 sky.upload.dir 解析成一个确定的绝对目录，并暴露上传工具。
 * <p>
 * 目录放在 sky-server 模块下而不是 target 里，否则 mvn clean 会把用户上传的图片一起清掉。
 */
@Configuration
@Slf4j
public class UploadConfiguration {

    @Autowired
    private UploadProperties uploadProperties;

    @Bean
    public LocalFileUtil localFileUtil() {
        String configuredDir = uploadProperties.getDir();
        if (!StringUtils.hasText(configuredDir)) {
            // 其他 properties 缺配置时字段为 null 也不报错，这里必须提前失败：
            // 否则问题会推迟到运行期，以 404 或"文件写到意料之外的目录"的形式暴露
            throw new IllegalStateException("sky.upload.dir 未配置，无法确定文件上传目录");
        }

        Path base = LocalFileUtil.resolveBase(moduleDirectory(), Paths.get(System.getProperty("user.dir")));
        Path root = LocalFileUtil.resolveRoot(configuredDir, base);
        try {
            // 启动就建好：静态资源映射指向一个不存在的目录时不会报错，只会让所有图片 404
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("创建文件上传目录失败：" + root, e);
        }
        log.info("文件上传目录：{}（配置值 {}，相对路径基准 {}）", root, configuredDir, base);
        return new LocalFileUtil(root);
    }

    /**
     * 由类的加载位置反推 Maven 模块目录。
     * <p>
     * IDEA 运行和 mvn spring-boot:run 时位置是 sky-server/target/classes/，打成 jar 后是 sky-server/target/xxx.jar，
     * 两种情况向上数两级都正好是模块目录，所以固定取祖父目录即可，不做向上遍历 ——
     * 无界遍历在"家目录下碰巧存在 pom.xml"时会静默把上传目录建到别处，比直接失败更难查。
     * 拿到的目录是否可信由 LocalFileUtil.resolveBase 按 pom.xml 复核，不可信就回退工作目录。
     */
    private static Path moduleDirectory() {
        try {
            Path location = codeSourceLocation();
            if (location == null) {
                return null;
            }
            Path parent = location.getParent();
            return parent == null ? null : parent.getParent();
        } catch (Exception e) {
            //探不出模块目录不算错误：回退工作目录即可，但要留个日志，否则"图片存到哪了"无从追查
            log.warn("无法从类的加载位置推断模块目录，上传目录将基于工作目录解析：{}", e.toString());
            return null;
        }
    }

    /**
     * 取本类加载位置的绝对路径。
     * <p>
     * 打成可执行 jar 后，Boot 用的是嵌套 jar 布局，地址形如
     * {@code jar:file:/path/sky-server/target/xxx.jar!/BOOT-INF/classes!/}，
     * 这种嵌套地址不能直接用 Paths.get(URI) 打开（会抛 FileSystemNotFoundException），
     * 所以退化到字符串处理，只要 jar 文件本身的位置。
     */
    private static Path codeSourceLocation() throws URISyntaxException {
        CodeSource codeSource = SkyApplication.class.getProtectionDomain().getCodeSource();
        if (codeSource == null) {
            return null;
        }
        URL url = codeSource.getLocation();
        if (!"jar".equals(url.getProtocol())) {
            return Paths.get(url.toURI());
        }
        String spec = url.toString();
        int bang = spec.indexOf('!');
        String fileSpec = bang < 0 ? spec : spec.substring(0, bang);
        return Paths.get(URI.create(fileSpec.substring(fileSpec.indexOf("file:"))));
    }
}
