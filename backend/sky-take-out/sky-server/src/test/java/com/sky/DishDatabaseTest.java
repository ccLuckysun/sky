package com.sky;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.context.BaseContext;
import com.sky.entity.Employee;
import com.sky.mapper.EmployeeMapper;
import com.sky.properties.JwtProperties;
import com.sky.utils.JwtUtil;
import com.sky.utils.LocalFileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 新增菜品的真实 MySQL 联调。mock 测试绕过 mapper 代理，因此证明不了两件事，只能在这里验：
 * 一是 AutoFillAspect 确实给 dish 的四个审计列填了值，二是回填的主键真的落到了 dish_flavor.dish_id 上。
 * 请求体按前端真实形状发（price 是字符串、flavors[].value 是 JSON 字符串、多一个 code 字段）。
 * 测试事务结束自动回滚，不留脏数据。
 * <p>
 * 图片必须先经上传接口真实落盘再拿去新增：新增接口会校验本地图片是否存在。
 * 落盘的文件不在事务里，所以每个用例上传了哪些文件要自己记着，在 @AfterEach 里删掉。
 */
@EnabledIfEnvironmentVariable(named = "SKY_DB_TESTS", matches = "true")
@SpringBootTest(properties = "logging.level.com.sky.mapper=INFO")
@AutoConfigureMockMvc
@Transactional
class DishDatabaseTest {
    @Autowired private MockMvc mvc;
    @Autowired private EmployeeMapper employeeMapper;
    @Autowired private JwtProperties jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LocalFileUtil localFileUtil;

    /** 每个用例真实上传过的文件，测试结束自己清理（回滚回调可能已经删过，这里是幂等兜底） */
    private final List<String> uploaded = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
        uploaded.forEach(localFileUtil::delete);
    }

    private long adminId() {
        Employee admin = employeeMapper.getByUsername("admin");
        assertNotNull(admin, "数据库需存在启用的 admin 员工");
        assertEquals(1, admin.getStatus());
        return admin.getId();
    }

    private String adminToken() {
        return JwtUtil.createJWT(jwt.getAdminSecretKey(), jwt.getAdminTtl(), Map.of("empId", adminId()));
    }

    /** 走真实上传接口拿到一个磁盘上确实存在的本地图片路径。 */
    private String uploadImage() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "dish-db-test.png", "image/png",
                "png".getBytes(StandardCharsets.UTF_8));
        String response = mvc.perform(multipart("/admin/common/upload")
                        .file(file).header("token", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String url = json.readTree(response).path("data").asText();
        uploaded.add(url);
        return url;
    }

    private String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private long existingCategoryId() {
        List<Long> ids = jdbc.queryForList("select id from category where type = 1 limit 1", Long.class);
        assertEquals(1, ids.size(), "数据库需存在 type=1 的分类");
        return ids.get(0);
    }

    /** 前端新增菜品页面实际会发上来的 body（含 DTO 里没有的 code 字段）。 */
    private String body(String name, long categoryId, String image) {
        return "{\"name\":\"" + name + "\",\"categoryId\":" + categoryId + ",\"price\":\"12.50\","
                + "\"image\":\"" + image + "\",\"description\":\"数据库联调\","
                + "\"status\":0,\"code\":\"SP001\","
                + "\"flavors\":[{\"name\":\"甜味\",\"value\":\"[\\\"无糖\\\",\\\"多糖\\\"]\"},"
                + "{\"name\":\"温度\",\"value\":\"[\\\"常温\\\"]\"}]}";
    }

    private void save(String name, long categoryId) throws Exception {
        save(name, categoryId, uploadImage());
    }

    private void save(String name, long categoryId, String image) throws Exception {
        mvc.perform(post("/admin/dish").header("token", adminToken())
                        .contentType("application/json").content(body(name, categoryId, image)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
    }

    private long dishIdOf(String name) {
        return jdbc.queryForObject("select id from dish where name = ?", Long.class, name);
    }

    private Map<String, String> flavorValuesOf(long dishId) {
        Map<String, String> byName = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("select name, value from dish_flavor where dish_id = ?", dishId)) {
            byName.put((String) row.get("name"), (String) row.get("value"));
        }
        return byName;
    }

    @Test
    void savesDishAndFlavorsWithAuditFieldsFromTheAspect() throws Exception {
        long adminId = adminId();
        long categoryId = existingCategoryId();
        String name = uniqueName("dish");
        String image = uploadImage();

        save(name, categoryId, image);
        long dishId = dishIdOf(name);

        Map<String, Object> dish = jdbc.queryForMap("select * from dish where id = ?", dishId);
        assertEquals(categoryId, ((Number) dish.get("category_id")).longValue());
        assertEquals(0, new BigDecimal("12.50").compareTo((BigDecimal) dish.get("price")));
        assertEquals(image, dish.get("image"), "上传返回的路径要原样入库");
        assertEquals("数据库联调", dish.get("description"));
        assertEquals(0, ((Number) dish.get("status")).intValue(), "前端新增固定传停售");
        //DishServiceImpl 一个审计列都没碰，这四个值只能来自 AutoFillAspect。
        assertEquals(adminId, ((Number) dish.get("create_user")).longValue(), "create_user 应为登录人");
        assertEquals(adminId, ((Number) dish.get("update_user")).longValue(), "update_user 应为登录人");
        assertNotNull(dish.get("create_time"), "create_time 应由切面填充");
        assertEquals(dish.get("create_time"), dish.get("update_time"), "新增时创建与修改时间是同一时刻");

        //口味挂在回填出来的主键上；value 原样保留前端 JSON.stringify 的字符串形态。
        Map<String, String> flavors = flavorValuesOf(dishId);
        assertEquals(2, flavors.size());
        assertEquals("[\"无糖\",\"多糖\"]", flavors.get("甜味"));
        assertEquals("[\"常温\"]", flavors.get("温度"));
    }

    @Test
    void rejectsDuplicateDishNameAndWritesNoFlavorRows() throws Exception {
        long categoryId = existingCategoryId();
        String name = uniqueName("dish");
        save(name, categoryId);
        long dishId = dishIdOf(name);

        //name 上的唯一索引是全库范围，不按分类区分。第二次用一个新上传的图，
        //免得与第一道菜在用的图片混在一起，看不清"回滚删的是哪一张"。
        String secondImage = uploadImage();
        mvc.perform(post("/admin/dish").header("token", adminToken())
                        .contentType("application/json").content(body(name, categoryId, secondImage)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("菜品名称已存在"));

        assertEquals(1, jdbc.queryForObject("select count(*) from dish where name = ?", Integer.class, name));
        //菜品没写进去，口味也不该写：否则这里会多出两条挂着已存在菜品的孤行。
        assertEquals(2, jdbc.queryForObject("select count(*) from dish_flavor where dish_id = ?", Integer.class, dishId));
    }

    @Test
    void rejectsCategoryThatDoesNotExistAndWritesNothing() throws Exception {
        String name = uniqueName("dish");

        //两表没有外键约束，不显式查分类就会写出一条在任何分类列表里都查不到的菜品。
        //这条用例只关心分类校验，图片用存量那种 OSS 绝对地址：它不查磁盘，也就不必为此传文件。
        mvc.perform(post("/admin/dish").header("token", adminToken())
                        .contentType("application/json")
                        .content(body(name, 999999999L, "https://sky-itcast.oss-cn-hangzhou.aliyuncs.com/legacy.png")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("分类不存在"));

        assertEquals(0, jdbc.queryForObject("select count(*) from dish where name = ?", Integer.class, name));
    }

    @Test
    void requiresAuthentication() throws Exception {
        String name = uniqueName("dish");
        long categoryId = existingCategoryId();

        //未登录的请求会被拦截器挡在控制器之前，图片根本不会被校验，这里不需要真上传
        mvc.perform(post("/admin/dish").contentType("application/json")
                        .content(body(name, categoryId, "/uploads/2026/09/23/whatever.png")))
                .andExpect(status().isUnauthorized());

        assertEquals(0, jdbc.queryForObject("select count(*) from dish where name = ?", Integer.class, name));
    }

    @Test
    void ignoresClientSuppliedIdAndUsesTheGeneratedKey() throws Exception {
        long categoryId = existingCategoryId();
        String name = uniqueName("dish");
        String image = uploadImage();
        //客户端硬塞一个 id，新增必须仍然由数据库分配主键。
        String withId = "{\"id\":123456789,\"name\":\"" + name + "\",\"categoryId\":" + categoryId
                + ",\"price\":\"1.00\",\"image\":\"" + image + "\",\"status\":1}";

        mvc.perform(post("/admin/dish").header("token", adminToken())
                        .contentType("application/json").content(withId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));

        long dishId = dishIdOf(name);
        assertEquals(0, jdbc.queryForObject("select count(*) from dish where id = 123456789", Integer.class));
        assertEquals(1, ((Number) jdbc.queryForMap("select status from dish where id = ?", dishId).get("status")).intValue(),
                "status 传了 1 就按起售入库");
    }
}
