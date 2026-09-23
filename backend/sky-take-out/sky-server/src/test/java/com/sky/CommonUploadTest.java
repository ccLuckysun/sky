package com.sky;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.constant.MessageConstant;
import com.sky.controller.admin.CommonController;
import com.sky.handler.GlobalExceptionHandler;
import com.sky.utils.LocalFileUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件上传接口。上传目录用临时目录注入，所以不需要数据库，默认 mvn test 就会执行。
 */
class CommonUploadTest {

    /** 返回路径的形状：/uploads/yyyy/MM/dd/<uuid>.<扩展名> */
    private static final String RELATIVE_URL_PATTERN =
            "^/uploads/\\d{4}/\\d{2}/\\d{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|jpeg|png)$";

    @TempDir
    Path uploadRoot;

    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        CommonController controller = new CommonController();
        ReflectionTestUtils.setField(controller, "localFileUtil", new LocalFileUtil(uploadRoot));
        //异常处理器必须显式挂上，否则上传失败的分支（返回 4xx）根本测不到
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private MockMultipartFile file(String originalFilename, String contentType, String content) {
        //三个参数都要显式给：只给内容的话 contentType 为 null，与真实上传的形状不一致
        return new MockMultipartFile("file", originalFilename, contentType, content.getBytes(StandardCharsets.UTF_8));
    }

    private String upload(MvcResult result) throws IOException {
        return json.readTree(result.getResponse().getContentAsString()).path("data").asText();
    }

    private List<Path> storedFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(uploadRoot)) {
            return walk.filter(Files::isRegularFile).collect(Collectors.toList());
        }
    }

    @Test
    void uploadStoresFileUnderDateDirectoryAndReturnsRelativeUrl() throws Exception {
        byte[] content = "fake-png-content".getBytes(StandardCharsets.UTF_8);
        MvcResult result = mvc.perform(multipart("/admin/common/upload")
                        .file(new MockMultipartFile("file", "dish.png", "image/png", content)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andReturn();

        //msg 字段存在且为 null，不要断言它不存在
        String url = upload(result);
        assertTrue(url.matches(RELATIVE_URL_PATTERN),
                "应当返回以 /uploads 开头的相对路径（与域名、项目路径无关），实际是 " + url);
        Path stored = uploadRoot.resolve(url.substring(LocalFileUtil.URL_PREFIX.length() + 1));
        assertTrue(Files.isRegularFile(stored), "文件应当落在上传目录里：" + stored);
        assertArrayEquals(content, Files.readAllBytes(stored), "落盘内容应当与上传的一致");
    }

    @Test
    void clientFileNameNeverInfluencesStoredLocation() throws Exception {
        //客户端文件名不参与路径拼接，所以带 ../ 的名字会被正常接受并改名为 UUID。
        //这里断言"名字不影响落盘位置"而不是"路径穿越被拒绝"：
        //后者会诱导实现去做多余且脆弱的 .. 字符串检查，而真正的保障是根本不用这个文件名
        mvc.perform(multipart("/admin/common/upload")
                        .file(file("../../evil.jpg", "image/jpeg", "x")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));

        List<Path> stored = storedFiles();
        assertEquals(1, stored.size(), "一次上传只应新增一个文件");
        assertTrue(stored.get(0).normalize().startsWith(uploadRoot),
                "落盘位置必须仍在上传目录内，实际是 " + stored.get(0));
        assertTrue(stored.get(0).getFileName().toString().endsWith(".jpg"), "扩展名应当保留");
    }

    @Test
    void acceptsUpperCaseExtension() throws Exception {
        //macOS 上传 .JPG 很常见，扩展名比较前必须转小写，否则会被白名单误拒
        MvcResult result = mvc.perform(multipart("/admin/common/upload")
                        .file(file("DISH.JPG", "image/jpeg", "x")))
                .andExpect(status().isOk())
                .andReturn();
        String url = upload(result);
        assertTrue(url.matches(RELATIVE_URL_PATTERN), "大写扩展名应当被接受并统一成小写，实际是 " + url);
    }

    @Test
    void rejectsEmptyFileWithoutStoringAnything() throws Exception {
        mvc.perform(multipart("/admin/common/upload").file(file("empty.png", "image/png", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(MessageConstant.UPLOAD_FILE_EMPTY));
        assertTrue(storedFiles().isEmpty(), "校验失败时不应该留下文件");
    }

    @Test
    void rejectsNonImageWithNon2xxSoElementUiDoesNotStoreNullString() throws Exception {
        //前端 el-upload 只按 HTTP 状态码分流：非 2xx 走 onError 弹"图片上传失败"，
        //2xx 则无条件把 data 拼成图片地址写进表单。若这里返回 200 + {code:0,data:null}，
        //前端会把字符串 "null" 存进 image 字段并入库，页面破图且没有任何报错
        mvc.perform(multipart("/admin/common/upload").file(file("evil.sh", "application/x-sh", "#!/bin/sh")))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(MessageConstant.UPLOAD_TYPE_NOT_ALLOWED));
        assertTrue(storedFiles().isEmpty(), "校验失败时不应该留下文件");
    }

    @Test
    void rejectsMissingFileNameAsBusinessErrorInsteadOfNullPointer() throws Exception {
        mvc.perform(multipart("/admin/common/upload")
                        .file(new MockMultipartFile("file", null, null, "x".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.msg").value(MessageConstant.UPLOAD_TYPE_NOT_ALLOWED));
    }
}
