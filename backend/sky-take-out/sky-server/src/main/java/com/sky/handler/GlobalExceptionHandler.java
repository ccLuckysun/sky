package com.sky.handler;

import com.sky.constant.MessageConstant;
import com.sky.exception.BaseException;
import com.sky.exception.FileUploadException;
import com.sky.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全局异常处理器，处理项目中抛出的业务异常
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final Pattern DUPLICATE_ENTRY_PATTERN =
            Pattern.compile("^Duplicate entry ('.*') for key .+$", Pattern.DOTALL);

    /**
     * 捕获业务异常
     * @param ex
     * @return
     */
    @ExceptionHandler
    public Result exceptionHandler(BaseException ex){
        log.error("异常信息：{}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    /**
     * 捕获数据库约束异常，重复记录返回具体的重复值。
     */
    @ExceptionHandler
    public Result<Void> exceptionHandler(SQLIntegrityConstraintViolationException ex) {
        log.error("数据库约束异常：{}", ex.getMessage());
        String message = ex.getMessage();
        if (message != null) {
            // 按 MySQL 消息中的引号边界提取，保留重复值中的空格。
            Matcher matcher = DUPLICATE_ENTRY_PATTERN.matcher(message);
            if (matcher.matches()) {
                return Result.error(matcher.group(1) + MessageConstant.ALREADY_EXISTS);
            }
        }
        return Result.error(MessageConstant.UNKNOWN_ERROR);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> invalidRequestBody(HttpMessageNotReadableException ex) {
        return Result.error("请求体不能为空且必须是有效的JSON，字段类型需符合接口定义");
    }

    /**
     * 捕获文件上传异常。
     * <p>
     * 这里有意偏离项目"业务失败也返回 HTTP 200 + code=0"的惯例：
     * 前端的 el-upload 只按 HTTP 状态码分流，2xx 时会无条件执行 imageUrl = "".concat(data)，
     * 失败时 data 为 null 会被拼成字符串 "null" 填进表单并最终入库，页面破图却没有任何报错。
     */
    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> exceptionHandler(FileUploadException ex) {
        log.error("文件上传失败：{}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    /**
     * 请求体超过 multipart 限制时，Tomcat 在解析阶段就抛错，早于 Controller，
     * 必须在全局异常处理器兜住，否则前端拿到的是裸的 500。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Result<Void> uploadSizeExceeded(MaxUploadSizeExceededException ex) {
        log.error("上传文件超过大小限制：{}", ex.getMessage());
        return Result.error(MessageConstant.UPLOAD_SIZE_EXCEEDED);
    }

    /**
     * 请求里没有 file 部分（表单字段名写错或直接调接口时漏传）。
     * 不处理会走 Spring 默认的 400 + 空响应体，不是项目统一的 Result 结构。
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> uploadPartMissing(MissingServletRequestPartException ex) {
        return Result.error(MessageConstant.UPLOAD_FILE_EMPTY);
    }

}
