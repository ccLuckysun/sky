package com.sky.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sky.upload")
@Data
public class UploadProperties {

    /**
     * 上传文件的存放目录。绝对路径原样使用；相对路径以 sky-server 模块目录为基准解析，
     * 因此项目整体搬迁后无需修改配置。解析逻辑见 UploadConfiguration。
     */
    private String dir;

}
