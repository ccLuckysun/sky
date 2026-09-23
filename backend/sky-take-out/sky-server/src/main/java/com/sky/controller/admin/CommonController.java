package com.sky.controller.admin;

import com.sky.constant.MessageConstant;
import com.sky.exception.FileUploadException;
import com.sky.result.Result;
import com.sky.utils.LocalFileUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 通用接口
 */
@RestController
@RequestMapping("/admin/common")
@Api(tags = "通用接口")
@Slf4j
public class CommonController {

    @Autowired
    private LocalFileUtil localFileUtil;

    /**
     * 文件上传
     *
     * @param file
     * @return 文件访问路径，形如 /uploads/2026/09/23/xxx.jpg
     */
    @PostMapping("/upload")
    @ApiOperation("文件上传")
    //MultipartFile 不会被 springfox 自动识别成文件上传，不写这段则 Knife4j 的"调试"会按 JSON 发请求
    @ApiImplicitParams(@ApiImplicitParam(name = "file", value = "图片文件",
            required = true, dataType = "__file", paramType = "form"))
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileUploadException(MessageConstant.UPLOAD_FILE_EMPTY);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("读取上传文件失败", e);
            throw new FileUploadException(MessageConstant.UPLOAD_FAILED);
        }
        //存盘与合法性校验都在 LocalFileUtil 里，这里只负责取字节、返回相对路径
        String url = localFileUtil.upload(bytes, file.getOriginalFilename());
        //有意不打印客户端提交的文件名：它是用户可控字符串，可能带换行等字符伪造日志行
        log.info("文件上传成功：{}（{} 字节）", url, bytes.length);
        return Result.success(url);
    }
}
