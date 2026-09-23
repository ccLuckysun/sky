package com.sky;

import com.sky.constant.MessageConstant;
import com.sky.exception.FileUploadException;
import com.sky.utils.LocalFileUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单独测上传工具，因为这里有两处最容易出问题的地方：
 * 目录基准的推导（决定文件到底写到哪）和返回 URL 的拼接（决定浏览器能不能取到图片）。
 */
class LocalFileUtilTest {

    @TempDir
    Path tempDir;

    private Path moduleDir;

    private Path moduleDir() throws IOException {
        if (moduleDir == null) {
            moduleDir = tempDir.resolve("sky-server");
            Files.createDirectories(moduleDir);
            //pom.xml 就是"这是模块目录"的判据
            Files.writeString(moduleDir.resolve("pom.xml"), "<project/>");
        }
        return moduleDir;
    }

    @Test
    void relativeDirIsResolvedAgainstModuleDirectory() throws IOException {
        Path fallback = tempDir.resolve("fallback");
        Path base = LocalFileUtil.resolveBase(moduleDir(), fallback);
        assertEquals(moduleDir(), base, "有 pom.xml 的目录应当被认作模块目录");
        assertEquals(moduleDir().resolve("uploads").normalize(), LocalFileUtil.resolveRoot("uploads", base));
    }

    @Test
    void fallsBackToGivenDirectoryWhenAnchorIsNotAModule() {
        Path fallback = tempDir.resolve("fallback");
        Path notAModule = tempDir.resolve("no-pom-here");
        assertEquals(fallback, LocalFileUtil.resolveBase(notAModule, fallback),
                "没有 pom.xml 就不能当基准，否则文件会被写到意料之外的目录");
        assertEquals(fallback, LocalFileUtil.resolveBase(null, fallback));
        assertEquals(fallback.resolve("uploads").normalize(), LocalFileUtil.resolveRoot("uploads", fallback));
    }

    @Test
    void absoluteConfiguredDirIsUsedAsIs() {
        Path absolute = tempDir.resolve("anywhere").toAbsolutePath();
        assertEquals(absolute.normalize(), LocalFileUtil.resolveRoot(absolute.toString(), Paths.get("/fallback")));
    }

    @Test
    void fileLocationAlwaysEndsWithSlashEvenWhenDirectoryDoesNotExistYet() {
        //toUri() 只在"目录此刻已存在"时才补尾斜杠。静态资源位置缺了它会被当成文件位置，
        //表现为"删掉目录后启动图片全 404、重启一次又自愈"，所以这里用不存在的目录来卡住这个行为
        Path notCreatedYet = tempDir.resolve("not-created-yet");
        assertFalse(Files.exists(notCreatedYet));
        assertTrue(LocalFileUtil.fileLocation(notCreatedYet).endsWith("/"),
                "静态资源位置必须以 / 结尾：" + LocalFileUtil.fileLocation(notCreatedYet));
    }

    @Test
    void uploadBuildsSlashSeparatedUrlAndKeepsExtensionLowerCase() throws IOException {
        LocalFileUtil fileUtil = new LocalFileUtil(tempDir);
        String url = fileUtil.upload("x".getBytes(StandardCharsets.UTF_8), "DISH.JPG");
        //URL 里必须只有 /：Windows 上若用 File.separator 拼会出现反斜杠，浏览器取不到图片
        assertTrue(url.matches("^/uploads/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]{36}\\.jpg$"), "实际是 " + url);
        Path stored = tempDir.resolve(url.substring(LocalFileUtil.URL_PREFIX.length() + 1));
        assertTrue(Files.isRegularFile(stored), "返回的路径必须能对应到真实文件：" + stored);
    }

    @Test
    void uploadRejectsUnsupportedExtension() {
        LocalFileUtil fileUtil = new LocalFileUtil(tempDir);
        FileUploadException exception = assertThrows(FileUploadException.class,
                () -> fileUtil.upload("x".getBytes(StandardCharsets.UTF_8), "shell.sh"));
        assertEquals(MessageConstant.UPLOAD_TYPE_NOT_ALLOWED, exception.getMessage());
    }

    @Test
    void existsAndDeleteFollowTheFileOnDisk() throws IOException {
        LocalFileUtil fileUtil = new LocalFileUtil(tempDir);
        String url = fileUtil.upload("x".getBytes(StandardCharsets.UTF_8), "a.png");

        assertTrue(fileUtil.exists(url), "刚上传的文件必须判断为存在：" + url);
        assertTrue(fileUtil.delete(url));
        assertFalse(Files.exists(onDisk(url)), "delete 必须真的把文件删掉：" + onDisk(url));
        assertFalse(fileUtil.exists(url));
        //文件已经不在时返回 false 而不是抛异常：调用它的是事务回滚后的清理逻辑，
        //那里抛出去会把真正的业务错误顶掉
        assertFalse(fileUtil.delete(url));
    }

    @Test
    void existsAndDeleteIgnoreAnythingThatIsNotALocalUploadPath() {
        LocalFileUtil fileUtil = new LocalFileUtil(tempDir);
        //库里存量数据是 OSS 绝对地址，它不是这个目录下的文件：既不能当文件处理，也不能报错
        String oss = "https://sky-itcast.oss-cn-hangzhou.aliyuncs.com/legacy.png";

        assertFalse(fileUtil.isLocalPath(oss));
        assertFalse(fileUtil.isLocalPath(null));
        assertFalse(fileUtil.isLocalPath(""));
        assertFalse(fileUtil.isLocalPath("/upload/2026/09/23/a.png"), "前缀少一个 s 就不能认");

        assertFalse(fileUtil.exists(oss));
        assertFalse(fileUtil.delete(oss));

        assertTrue(fileUtil.isLocalPath("/uploads/2026/09/23/a.png"));
        assertFalse(fileUtil.exists("/uploads/2026/09/23/a.png"), "本地路径但文件不在，同样是 false");
    }

    @Test
    void deleteRefusesToWalkOutOfTheUploadRoot() throws IOException {
        LocalFileUtil fileUtil = new LocalFileUtil(tempDir.resolve("uploads-root"));
        //根目录外真实放一个文件：它还在，就证明越界的路径没有把删除带出去
        Path outside = tempDir.resolve("outside.png");
        Files.write(outside, "x".getBytes(StandardCharsets.UTF_8));

        assertFalse(fileUtil.delete("/uploads/../outside.png"), "../ 越界必须拒绝");
        assertFalse(fileUtil.delete("/uploads//" + outside.toAbsolutePath()),
                "以 / 开头的子串会被 resolve 当成绝对路径，同样越界");
        assertTrue(Files.exists(outside), "越界路径不能删到根目录外的文件：" + outside);
    }

    private Path onDisk(String url) {
        return tempDir.resolve(url.substring(LocalFileUtil.URL_PREFIX.length() + 1));
    }
}
