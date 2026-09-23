package com.sky.utils;

import com.sky.constant.MessageConstant;
import com.sky.exception.FileUploadException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 本地磁盘文件上传工具，替代 AliOssUtil 的云端存储。
 * <p>
 * 保存后的文件落在 {@code root} 目录下，按 {@code yyyy/MM/dd} 分目录，文件名重新生成为 UUID，
 * 对外返回以 {@link #URL_PREFIX} 开头的相对路径（如 {@code /uploads/2026/09/23/xxx.jpg}）。
 * 相对路径由浏览器按当前站点解析，因此与域名、端口、项目所在绝对路径全都无关，
 * 项目搬迁或换访问地址时，数据库里已经存好的路径依旧有效。
 * <p>
 * 本类只负责存盘、判断存在、删除与拼路径，不做任何环境探测（工作目录、CodeSource 之类由 UploadConfiguration 处理），
 * 这样既保证存文件与读文件的目录是同一个，也让逻辑可以用临时目录直接做单元测试。
 */
@Getter
@Slf4j
public class LocalFileUtil {

    /**
     * 对外访问前缀，同时也是 WebMvcConfiguration 注册的静态资源路径。
     * 它已经写进了数据库中 image 一类的字段，属于持久化契约，不要修改。
     */
    public static final String URL_PREFIX = "/uploads";

    /** 判断某个目录是不是 Maven 模块目录的依据 */
    private static final String POM_FILE_NAME = "pom.xml";

    private static final DateTimeFormatter DATE_DIR_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** 白名单与前端页面文案"仅能上传 PNG JPEG JPG类型图片"保持一致 */
    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList("jpg", "jpeg", "png");

    private final Path root;

    public LocalFileUtil(Path root) {
        this.root = root;
    }

    /**
     * 保存文件并返回可直接用于 img src 的相对路径。
     *
     * @param bytes            文件内容
     * @param originalFilename 客户端提交的原始文件名，只用来取扩展名，不参与路径拼接
     * @return 形如 {@code /uploads/2026/09/23/6f1c...a8b2.jpg} 的相对路径
     */
    public String upload(byte[] bytes, String originalFilename) {
        String extension = extensionOf(originalFilename);
        // 重新生成文件名：客户端文件名既不安全（可能带 ../ 或各种畸形字符）也没有唯一性，
        // UUID 同时充当缓存键，换图后 URL 必然变化，不会读到旧图。
        String relativePath = String.join("/",
                DATE_DIR_FORMATTER.format(LocalDate.now()),
                UUID.randomUUID() + "." + extension);
        Path target = root.resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            log.error("文件写入失败：{}", target, e);
            throw new FileUploadException(MessageConstant.UPLOAD_FAILED);
        }
        // 用 String.join 而不是 Path/File 拼 URL：Windows 上 File.separator 是反斜杠，
        // 拼进 URL 浏览器会把 \ 当普通字符，直接 404。
        return URL_PREFIX + "/" + relativePath;
    }

    /**
     * 判断一个路径是不是本地上传的图片（以 {@link #URL_PREFIX} 开头）。
     * <p>
     * 库里 image 一类的字段存在两种形态：本次改造之前留下的阿里云 OSS 绝对地址，
     * 和本地磁盘上传返回的相对路径。只有后者才对应这个目录下的文件，
     * 调用 {@link #exists} 与 {@link #delete} 之前必须先区分，否则会把 URL 当文件路径处理。
     *
     * @param urlPath 库里存的图片路径，可以是 null
     */
    public boolean isLocalPath(String urlPath) {
        return urlPath != null && urlPath.startsWith(URL_PREFIX + "/");
    }

    /**
     * 判断本地上传的文件是否还在磁盘上。
     * <p>
     * 不是本地上传路径（OSS 绝对地址、null、空串）一律返回 false，且不报错也不算异常情况：
     * 调用方的判断是"能不能当文件处理"，而不是"这个值合不合法"。
     *
     * @param urlPath 形如 {@code /uploads/2026/09/23/xxx.png} 的相对路径
     */
    public boolean exists(String urlPath) {
        Path file = storedFile(urlPath);
        return file != null && Files.isRegularFile(file);
    }

    /**
     * 删除本地上传的文件，用于"菜品新增失败后把这次请求引用的图片一并回滚掉"这类补偿。
     * <p>
     * 两条硬约束：
     * 一是只会删 {@code root} 目录内的文件，解析结果一旦越界就拒绝——这个方法的入参来自客户端提交的
     * JSON，跟读图那侧同样要按"不可信输入"对待，不能让 {@code ../} 把删除带出上传目录；
     * 二是永不抛异常，删不掉只记日志——它会被放在事务完成回调里调用，那里抛出去会盖掉真正的业务错误。
     *
     * @param urlPath 形如 {@code /uploads/2026/09/23/xxx.png} 的相对路径
     * @return 是否真的删掉了一个文件；文件本来就不在、或不是本地上传路径，都返回 false
     */
    public boolean delete(String urlPath) {
        Path file = storedFile(urlPath);
        if (file == null) {
            if (isLocalPath(urlPath)) {
                // 带前缀却被判为越界，说明这是个刻意构造的路径，值得留一条记录
                log.warn("拒绝删除上传目录之外的路径：{}", urlPath);
            }
            return false;
        }
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                log.info("删除上传文件：{}", urlPath);
            }
            return deleted;
        } catch (IOException e) {
            log.warn("删除上传文件失败：{}", file, e);
            return false;
        }
    }

    /**
     * 把对外路径还原成上传目录内的真实文件，路径越界或根本不是本地上传路径时返回 null。
     * <p>
     * 与 WebMvcConfiguration 里读图那侧的围栏是同一套判据（归一化后必须仍在 root 内），
     * 区别只是这里由本类持有 root，所以规则写在离 root 最近的地方。
     */
    private Path storedFile(String urlPath) {
        if (!isLocalPath(urlPath)) {
            return null;
        }
        Path root = this.root.normalize();
        // 去掉前缀与紧跟的那一个 /：子串如果以 / 开头，resolve 会直接把它当成绝对路径，
        // 归一化后自然落在 root 之外，被下面的 startsWith 挡掉。
        Path candidate = root.resolve(urlPath.substring(URL_PREFIX.length() + 1)).normalize();
        return candidate.startsWith(root) ? candidate : null;
    }

    /**
     * 确定相对路径的基准目录：优先用模块目录，拿不到时回退到 JVM 工作目录。
     * <p>
     * 不直接用工作目录，是因为它随启动方式变化（IDEA、mvn spring-boot:run、java -jar 各不相同），
     * 那会让同一个项目在不同启动方式下把图片存到不同地方，表现为"图片时有时无"。
     *
     * @param moduleAnchor 模块目录候选，判据是该目录下存在 pom.xml
     * @param fallback     模块目录不可用时的兜底基准，通常是 JVM 工作目录
     */
    public static Path resolveBase(Path moduleAnchor, Path fallback) {
        return isModuleDir(moduleAnchor) ? moduleAnchor : fallback;
    }

    /**
     * 解析配置中的上传目录：绝对路径原样使用，相对路径基于 {@code base} 解析。
     *
     * @param configuredDir 配置值，如 uploads
     * @param base          相对路径基准，由 {@link #resolveBase} 得到
     */
    public static Path resolveRoot(String configuredDir, Path base) {
        Path configured = Paths.get(configuredDir);
        return configured.isAbsolute() ? configured.normalize() : base.resolve(configured).normalize();
    }

    /**
     * 静态资源映射用的位置，形如 {@code file:///path/to/sky-server/uploads/}。
     * <p>
     * 显式保证尾部斜杠：toUri() 只在"目录此刻已存在"时才补斜杠，
     * 一旦映射先于建目录执行，Spring 会把没有尾斜杠的地址当成文件位置，
     * 出现"删掉目录后启动就 404、重启一次又自愈"这种极难复现的问题。
     */
    public static String fileLocation(Path root) {
        String location = root.toUri().toString();
        return location.endsWith("/") ? location : location + "/";
    }

    private static String extensionOf(String originalFilename) {
        String extension = StringUtils.getFilenameExtension(originalFilename);
        extension = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new FileUploadException(MessageConstant.UPLOAD_TYPE_NOT_ALLOWED);
        }
        return extension;
    }

    private static boolean isModuleDir(Path dir) {
        return dir != null && Files.isRegularFile(dir.resolve(POM_FILE_NAME));
    }
}
