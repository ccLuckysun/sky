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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 新增菜品失败时"图片一并回滚"的真实行为。只能连真库跑，因为要验的两件事都不在 mock 的范围里：
 * 补偿挂在事务完成回调上，删图前还要查库确认没有别的菜品在用同一张图。
 * <p>
 * 这个类**故意不加 @Transactional**：被验证的行为本身就是"事务结束时发生什么"，
 * 套一层测试事务只会把回调推迟到测试方法结束之后，断言必然落空。因此它真实提交，
 * 所有造出来的数据与文件都在 @AfterEach 里按名字清理干净（库里的行、磁盘上的文件都只删自己造的）。
 */
@EnabledIfEnvironmentVariable(named = "SKY_DB_TESTS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
class DishImageRollbackDatabaseTest {

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
        MockMultipartFile file = new MockMultipartFile("file", "rollback-test.png", "image/png",
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

    private String body(String name, long categoryId, String image) {
        return "{\"name\":\"" + name + "\",\"categoryId\":" + categoryId + ",\"price\":\"12.50\","
                + "\"image\":\"" + image + "\",\"description\":\"图片回滚联调\",\"status\":0,"
                + "\"flavors\":[{\"name\":\"甜味\",\"value\":\"[\\\"无糖\\\"]\"}]}";
    }

    /** 提交一次新增，返回响应体，由调用方断言成功还是失败。 */
    private String submit(String name, long categoryId, String image) throws Exception {
        createdNames.add(name);
        return mvc.perform(post("/admin/dish").header("token", adminToken())
                        .contentType("application/json").content(body(name, categoryId, image)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void saveOk(String name, long categoryId, String image) throws Exception {
        assertEquals(1, json.readTree(submit(name, categoryId, image)).path("code").asInt(), "本次新增本应成功");
    }

    private int dishCount(String name) {
        return jdbc.queryForObject("select count(*) from dish where name = ?", Integer.class, name);
    }

    private long dishId(String name) {
        Long id = jdbc.queryForObject("select id from dish where name = ?", Long.class, name);
        assertNotNull(id, "测试数据未按预期写入：" + name);
        return id;
    }

    /**
     * 修改接口的请求体，形状与编辑页提交的一致：带上回显多出来的 categoryName、
     * price 是字符串、每个口味带着回显拿到的 id/dishId。
     */
    private String updateBody(long id, String name, long categoryId, String image) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"categoryId\":" + categoryId + ","
                + "\"price\":\"13.50\",\"image\":\"" + image + "\",\"description\":\"图片回滚联调\","
                + "\"status\":0,\"categoryName\":\"不参与更新的字段\","
                + "\"flavors\":[{\"id\":1,\"dishId\":" + id + ",\"name\":\"甜味\",\"value\":\"[\\\"无糖\\\"]\"}]}";
    }

    /** 提交一次修改，返回响应体，由调用方断言成功还是失败。 */
    private String submitUpdate(long id, String name, long categoryId, String image) throws Exception {
        return mvc.perform(put("/admin/dish").header("token", adminToken())
                        .contentType("application/json").content(updateBody(id, name, categoryId, image)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void deletesTheUploadedImageWhenTheInsertRollsBack() throws Exception {
        long categoryId = existingCategoryId();
        String name = uniqueName("dish");
        String keptImage = uploadImage();
        saveOk(name, categoryId, keptImage);

        //同名再提交一次：dish.name 上有全库唯一索引，这次必然回滚
        String doomedImage = uploadImage();
        String response = submit(name, categoryId, doomedImage);

        assertEquals(0, json.readTree(response).path("code").asInt());
        assertEquals("菜品名称已存在", json.readTree(response).path("msg").asText());
        assertEquals(1, dishCount(name), "失败的那次不能留下任何行");
        //两个接口是两个请求，上传的图不在数据库事务里，所以回滚必须靠补偿把图删掉
        assertFalse(localFileUtil.exists(doomedImage), "回滚后本次请求引用的图片必须被删掉：" + doomedImage);
        assertTrue(localFileUtil.exists(keptImage), "第一次成功新增的图片不能被牵连：" + keptImage);
    }

    @Test
    void keepsTheUploadedImageWhenTheDishIsSaved() throws Exception {
        long categoryId = existingCategoryId();
        String name = uniqueName("dish");
        String image = uploadImage();

        saveOk(name, categoryId, image);

        long dishId = jdbc.queryForObject("select id from dish where name = ?", Long.class, name);
        assertEquals("甜味", jdbc.queryForObject("select name from dish_flavor where dish_id = ?",
                String.class, dishId), "口味挂在回填出来的主键上");
        //提交成功绝不能删图：删了的话刚建出来的菜品立刻挂着一张不存在的图
        assertTrue(localFileUtil.exists(image), "提交成功时不能删除图片：" + image);
    }

    @Test
    void keepsAnImageThatAnotherDishStillReferences() throws Exception {
        long categoryId = existingCategoryId();
        String name = uniqueName("dish");
        String image = uploadImage();
        saveOk(name, categoryId, image);

        //再拿同一个路径提交一次（同名，必失败）：它已经不是"本次上传、没被用掉"的图，
        //而是另一道菜正在用的图。回滚时的清理必须发现这一点，否则那道菜品的图就凭空消失了。
        String response = submit(name, categoryId, image);

        assertEquals(0, json.readTree(response).path("code").asInt());
        assertTrue(localFileUtil.exists(image), "还被菜品引用着的图片不能删：" + image);
    }

    @Test
    void rejectsAnImageThatIsNoLongerOnDisk() throws Exception {
        long categoryId = existingCategoryId();
        String name = uniqueName("dish");
        String missing = "/uploads/2026/09/23/gone-" + UUID.randomUUID() + ".png";

        String response = submit(name, categoryId, missing);

        //失败会把图删掉，客户端手里的路径可能已经失效；这条校验挡住的就是"拿一个已被删掉的路径重提交"
        assertEquals(0, json.readTree(response).path("code").asInt());
        assertEquals("图片文件不存在，请重新上传", json.readTree(response).path("msg").asText());
        assertEquals(0, dishCount(name));
    }

    /**
     * 修改失败时，<b>这次请求新换的那张图</b>要跟着回滚一并删掉。
     * <p>
     * 造法：先真上传两张图，分别建成两道菜（这样库里确实有"别人正在用"的图，能验证误删判断不会过度触发）；
     * 再把第二道菜改名成第一道菜的名字并换上新上传的第三张图 —— dish.name 上的全库唯一索引必然让这次修改回滚。
     * 换图是编辑页最常见的操作，而"图换了、行没改"正是最需要补偿的场景：不删就是一张永远没人用的孤儿图。
     */
    @Test
    void deletesTheNewlyChosenImageWhenTheUpdateRollsBack() throws Exception {
        long categoryId = existingCategoryId();
        String keptName = uniqueName("dish");
        String keptImage = uploadImage();
        saveOk(keptName, categoryId, keptImage);

        String targetName = uniqueName("dish");
        String targetImage = uploadImage();
        saveOk(targetName, categoryId, targetImage);
        long targetId = dishId(targetName);

        //编辑页换了一张图再提交，但名字改成了另一道菜的 → 唯一索引冲突，整个修改回滚
        String doomedImage = uploadImage();
        String response = submitUpdate(targetId, keptName, categoryId, doomedImage);

        assertEquals(0, json.readTree(response).path("code").asInt());
        assertEquals("菜品名称已存在", json.readTree(response).path("msg").asText());
        //这一行必须原样：改名没成功，图片也不该被改掉
        assertEquals(targetImage, jdbc.queryForObject("select image from dish where id = ?", String.class, targetId),
                "回滚后这一行的 image 必须还是原值");
        //新换的那张图没人用了，必须删掉
        assertFalse(localFileUtil.exists(doomedImage), "修改回滚后本次请求新换的图片必须被删掉：" + doomedImage);
        //别的菜在用的图不能被牵连
        assertTrue(localFileUtil.exists(keptImage), "别的菜品在用的图片不能被牵连：" + keptImage);
        assertTrue(localFileUtil.exists(targetImage), "这道菜原本的图片也还在用，不能删：" + targetImage);
    }

    /**
     * 图片没换（请求里的 image 就是这道菜当前的 image）时，修改失败<b>不能</b>删图。
     * <p>
     * 编辑页只改个价格、不碰图片时走的就是这条路径。回滚那一刻这一行还在（image 没变），
     * {@code countByImage > 0} 会查到它→ 判定为"还有人在用"→ 跳过删除。
     * 少了这道守卫，任何一次修改失败都会把这道菜的图片删掉，页面立刻破图。
     * <p>
     * 与上一条用例共用同一套失败手段（撞另一道菜的名字），只有 image 一个变量不同。
     */
    @Test
    void keepsTheCurrentImageWhenTheUpdateFailsWithoutChangingIt() throws Exception {
        long categoryId = existingCategoryId();
        String keptName = uniqueName("dish");
        saveOk(keptName, categoryId, uploadImage());

        String targetName = uniqueName("dish");
        String targetImage = uploadImage();
        saveOk(targetName, categoryId, targetImage);
        long targetId = dishId(targetName);

        //image 原样回传（编辑页没动图片），名字改成另一道菜的 → 同样回滚
        String response = submitUpdate(targetId, keptName, categoryId, targetImage);

        assertEquals(0, json.readTree(response).path("code").asInt());
        assertEquals("菜品名称已存在", json.readTree(response).path("msg").asText());
        assertTrue(localFileUtil.exists(targetImage),
                "图片没换时，修改失败不能删掉这道菜正在用的图片：" + targetImage);
    }
}
