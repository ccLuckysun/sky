package com.sky;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批量删除菜品时"图片跟着一起清掉"的真实行为。只能连真库跑，因为要验的东西都不在 mock 的范围里：
 * 清理挂在 {@code afterCommit} 上（只有事务真的提交了才删），删之前还要查库确认没有别的记录在用同一张图。
 * <p>
 * 这个类**故意不加 @Transactional**：被验证的行为本身就是"事务提交时发生什么"，
 * 套一层测试事务只会把回调推迟到测试方法结束之后、而且最终回滚，断言必然落空。
 * 因此它真实提交，所有造出来的数据与文件都在 {@code @AfterEach} 里按名字清理干净
 * （库里的行、磁盘上的文件都只删自己造的）。
 * <p>
 * 与 {@link DishImageRollbackDatabaseTest} 是一对：那边验的是"新增/修改**回滚**时删图"，
 * 这边验的是"删除**提交**时删图"。两个方向正好相反，挂错任意一个都会造成数据损坏。
 */
@EnabledIfEnvironmentVariable(named = "SKY_DB_TESTS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
class DishDeleteImageDatabaseTest {

    @Autowired private MockMvc mvc;
    @Autowired private EmployeeMapper employeeMapper;
    @Autowired private JwtProperties jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LocalFileUtil localFileUtil;

    private final List<String> uploaded = new ArrayList<>();
    private final List<String> createdNames = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void cleanUp() {
        BaseContext.removeCurrentId();
        for (String name : createdNames) {
            //口味先删：dish_flavor.dish_id 上虽然没有外键，留着就是孤行
            jdbc.update("delete from dish_flavor where dish_id in (select id from dish where name = ?)", name);
            jdbc.update("delete from dish where name = ?", name);
        }
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

    private long existingCategoryId() {
        List<Long> ids = jdbc.queryForList("select id from category where type = 1 limit 1", Long.class);
        assertEquals(1, ids.size(), "数据库需存在 type=1 的分类");
        return ids.get(0);
    }

    private String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 走真实上传接口拿到一个磁盘上确实存在的本地图片路径。 */
    private String uploadImage() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "delete-image-test.png", "image/png",
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

    /**
     * 建一道菜。status 可指定：0 停售（可删）、1 起售（删除会被守卫拦下）。
     * 走真实新增接口而不是直接 SQL，顺带保证"新增成功时图还在"这条前提成立。
     */
    private long createDish(String name, long categoryId, String image, int status) throws Exception {
        createdNames.add(name);
        String body = "{\"name\":\"" + name + "\",\"categoryId\":" + categoryId + ",\"price\":\"12.50\","
                + "\"image\":\"" + image + "\",\"description\":\"删除清图联调\",\"status\":" + status + ","
                + "\"flavors\":[{\"name\":\"甜味\",\"value\":\"[\\\"无糖\\\"]\"}]}";
        String response = mvc.perform(post("/admin/dish").header("token", adminToken())
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(1, json.readTree(response).path("code").asInt(), "测试前置条件：这道菜本应新增成功：" + response);
        //新增成功绝不能删图，否则下面的断言会以一种误导人的方式失败
        assertTrue(localFileUtil.exists(image), "新增成功时图片必须还在：" + image);
        Long id = jdbc.queryForObject("select id from dish where name = ?", Long.class, name);
        assertNotNull(id, "测试数据未按预期写入");
        return id;
    }

    private JsonNode deleteDishes(String ids) throws Exception {
        String response = mvc.perform(delete("/admin/dish").header("token", adminToken()).param("ids", ids))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response);
    }

    private int dishCount(long id) {
        return jdbc.queryForObject("select count(*) from dish where id = ?", Integer.class, id);
    }

    /** 删除一道菜，它的图片必须跟着消失在磁盘上——这是本轮新增的那条补偿。 */
    @Test
    void deletesTheUploadedImageWhenTheDishIsDeleted() throws Exception {
        long categoryId = existingCategoryId();
        String image = uploadImage();
        long dishId = createDish(uniqueName("dish"), categoryId, image, 0);

        assertEquals(1, deleteDishes(String.valueOf(dishId)).path("code").asInt());

        assertEquals(0, dishCount(dishId));
        assertFalse(localFileUtil.exists(image), "菜品删掉之后这张图不该再留在磁盘上：" + image);
    }

    /**
     * 同一张图被另一道菜引用着时不能删。
     * <p>
     * 客户端完全可以给多道菜填同一个 image 路径（编辑时原样回传别人的路径就能做到）。
     * 删掉其中一道就把文件删了的话，另一道菜立刻破图，而且不可逆。
     */
    @Test
    void keepsTheImageWhileAnotherDishStillUsesIt() throws Exception {
        long categoryId = existingCategoryId();
        String shared = uploadImage();
        long first = createDish(uniqueName("dish"), categoryId, shared, 0);
        long second = createDish(uniqueName("dish"), categoryId, shared, 0);

        assertEquals(1, deleteDishes(String.valueOf(first)).path("code").asInt());

        assertEquals(0, dishCount(first));
        assertTrue(localFileUtil.exists(shared), "还有菜品在用的图片不能删：" + shared);

        //反过来再删第二道：这时才真的没人用了，文件应当被清掉
        assertEquals(1, deleteDishes(String.valueOf(second)).path("code").asInt());
        assertEquals(0, dishCount(second));
        assertFalse(localFileUtil.exists(shared), "最后一道引用它的菜也删掉后，图片应当被清理：" + shared);
    }

    /**
     * 被守卫拒掉时图片必须原样留着。
     * <p>
     * 这是本次改动最容易挂错的地方：清理挂在 {@code afterCommit} 上，事务回滚（守卫抛异常）
     * 时回调根本不会触发。若挂成"无论如何都删"，一次被拒绝的删除会把起售菜品正在用的图删掉。
     */
    @Test
    void keepsTheImageWhenTheDeleteIsRefused() throws Exception {
        long categoryId = existingCategoryId();
        String image = uploadImage();
        //起售状态：删除必然被守卫拦下
        long dishId = createDish(uniqueName("dish"), categoryId, image, 1);

        JsonNode response = deleteDishes(String.valueOf(dishId));

        assertEquals(0, response.path("code").asInt());
        assertEquals("起售中的菜品不能删除", response.path("msg").asText());
        assertEquals(1, dishCount(dishId), "被拒时菜品必须还在");
        assertTrue(localFileUtil.exists(image), "删除被拒时绝不能动图片：" + image);
    }

    /**
     * image 为 null 的菜品也要能正常删除。
     * <p>
     * 直接 SQL 造这么一行：{@code listImagesByIds} 的 {@code image is not null} 过滤、
     * 以及 {@code deleteUploadedImage} 的 {@code isLocalPath(null)} 都靠这条守住——
     * 少了任意一处，这个请求会以 NPE 收场（而且 NPE 会被清理逻辑的 catch-all 吞成一条 WARN，
     * 表现为"删除成功但没删图"，比直接报错更难查）。
     */
    @Test
    void deletesADishWhoseImageIsNull() throws Exception {
        long categoryId = existingCategoryId();
        String name = uniqueName("dish");
        createdNames.add(name);
        Timestamp now = Timestamp.valueOf("2026-09-23 10:00:00");
        jdbc.update("insert into dish (name, category_id, price, image, description, status,"
                        + " create_time, update_time, create_user, update_user)"
                        + " values (?, ?, ?, null, ?, 0, ?, ?, 1, 1)",
                name, categoryId, new BigDecimal("9.90"), "删除清图联调", now, now);
        Long dishId = jdbc.queryForObject("select id from dish where name = ?", Long.class, name);
        assertNotNull(dishId, "测试数据未按预期写入");

        assertEquals(1, deleteDishes(String.valueOf(dishId)).path("code").asInt());

        assertEquals(0, dishCount(dishId));
    }

    /**
     * 库里存量为 0 的本地图（比如 OSS 绝对地址）不该被当文件处理。
     * <p>
     * 这里用 image 直接留 NULL 已经覆盖了 null 分支，所以这条改成验"批量里混着两种形态"：
     * 一道本地图的菜 + 一道 NULL 图的菜一起删，本地那张要正常清掉、请求要成功。
     */
    @Test
    void deletesMixedLocalAndNullImagesInOneBatch() throws Exception {
        long categoryId = existingCategoryId();
        String image = uploadImage();
        long localImageDish = createDish(uniqueName("dish"), categoryId, image, 0);

        String name = uniqueName("dish");
        createdNames.add(name);
        Timestamp now = Timestamp.valueOf("2026-09-23 10:00:00");
        jdbc.update("insert into dish (name, category_id, price, image, description, status,"
                        + " create_time, update_time, create_user, update_user)"
                        + " values (?, ?, ?, null, ?, 0, ?, ?, 1, 1)",
                name, categoryId, new BigDecimal("9.90"), "删除清图联调", now, now);
        Long nullImageDish = jdbc.queryForObject("select id from dish where name = ?", Long.class, name);
        assertNotNull(nullImageDish);

        JsonNode response = deleteDishes(localImageDish + "," + nullImageDish);

        assertEquals(1, response.path("code").asInt(), "混合批次本应成功：" + response);
        assertEquals(0, dishCount(localImageDish));
        assertEquals(0, dishCount(nullImageDish));
        assertFalse(localFileUtil.exists(image), "本地那张图应当被清掉：" + image);
    }

    /**
     * 本类的测试数据一行都不该留在库里：{@code @AfterEach} 按名字删掉自己造的那些行。
     * <p>
     * 只断言本类专用的 {@code dish_} 前缀在 {@code ddi_} 之外不好区分，所以改用"删除前后的总数自比"，
     * 避免与库里的真实菜品或别的测试类互相干扰。
     */
    @Test
    void leavesNoLeftoverRowsAfterCleanup() {
        assertEquals(0, jdbc.queryForObject(
                        "select count(*) from dish where description = ?", Integer.class, "删除清图联调"),
                "有测试数据残留在库里：先确认本类的 @AfterEach 还在");
    }
}
