package com.sky;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.entity.Employee;
import com.sky.mapper.EmployeeMapper;
import com.sky.properties.JwtProperties;
import com.sky.utils.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修改菜品与编辑页回显的真实 MySQL 联调：验证 mock 测不到的 SQL 与切面织入。
 * <p>
 * 几件事只有连真库才能证明：
 * <ul>
 *   <li>{@code @AutoFill(UPDATE)} 真的织到了 {@code DishMapper.update} 上：
 *       库里 {@code update_time} / {@code update_user} 被填了，而 {@code create_time} /
 *       {@code create_user} 一行没动（mock 掉 mapper 就绕过了整个代理，证明不了这一点）；</li>
 *   <li>动态 {@code <set>} 生成的 SQL 真的能被 MySQL 执行，且 {@code where id} 只改目标行；</li>
 *   <li><b>把某行改回它自己当前的名字不触发唯一冲突</b>——真库唯一索引下的关键行为，mock 证明不了；</li>
 *   <li>{@code dish_flavor} 的整组替换真的删掉了旧口味、只影响这一道菜；</li>
 *   <li>{@code GET /admin/dish/{id}} 与 {@code GET /admin/dish/page} 两条路由不打架。</li>
 * </ul>
 * 类上的 {@code @Transactional} 让每个用例结束时回滚，测试自己造的数据不会留在库里。
 */
@EnabledIfEnvironmentVariable(named = "SKY_DB_TESTS", matches = "true")
@SpringBootTest(properties = "logging.level.com.sky.mapper=INFO")
@AutoConfigureMockMvc
@Transactional
class DishUpdateDatabaseTest {

    /** 联调用的分类id：dish.category_id 上没有外键，取一个库中存在的分类即可（与前两轮联调一致）。 */
    private static final long CATEGORY_ID = 16L;
    /** 测试数据一律插成这个"很久以前"的时间，用来证明 update_time 确实被切面刷新了。 */
    private static final String OLD_TIME = "2020-01-01 00:00:00";
    /**
     * 用 OSS 绝对地址而不是 /uploads/...：本地路径会被"图片文件必须真的在磁盘上"的校验拦下，
     * 而本类不关心文件系统（那是 DishImageRollbackDatabaseTest 的活）。库里 24 条存量数据也是这个形态。
     */
    private static final String IMAGE = "https://sky-itcast.oss-cn-hangzhou.aliyuncs.com/legacy.png";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private EmployeeMapper employeeMapper;
    @Autowired private JwtProperties jwt;
    @Autowired private JdbcTemplate jdbc;

    private String adminToken() {
        Employee admin = employeeMapper.getByUsername("admin");
        assertNotNull(admin, "数据库需存在启用的 admin 员工");
        assertEquals(1, admin.getStatus());
        return JwtUtil.createJWT(jwt.getAdminSecretKey(), jwt.getAdminTtl(), Map.of("empId", admin.getId()));
    }

    private long adminId() {
        return employeeMapper.getByUsername("admin").getId();
    }

    /** 菜品名有全库唯一索引，用 UUID 保证每次联调都不撞名。 */
    private String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 插一道菜，主键由数据库分配；审计列用固定的旧时间，方便断言哪些被改了、哪些没被改。 */
    private long insertDish(String name) {
        Timestamp old = Timestamp.valueOf(OLD_TIME);
        jdbc.update("insert into dish (name, category_id, price, image, description, status,"
                        + " create_time, update_time, create_user, update_user)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                name, CATEGORY_ID, new BigDecimal("12.50"), IMAGE, "修改联调", 0, old, old, 1L, 1L);
        Long id = jdbc.queryForObject("select id from dish where name = ?", Long.class, name);
        assertNotNull(id, "测试数据未按预期写入");
        return id;
    }

    /** dish_flavor 没有审计列，直接给 dish_id/name/value 三列。 */
    private void insertFlavor(long dishId, String name, String value) {
        jdbc.update("insert into dish_flavor (dish_id, name, value) values (?, ?, ?)", dishId, name, value);
    }

    private List<String> flavorNames(long dishId) {
        return jdbc.queryForList("select name from dish_flavor where dish_id = ? order by id", String.class, dishId);
    }

    private Map<String, Object> row(long dishId) {
        return jdbc.queryForMap("select name, category_id, price, image, description, status,"
                + " create_time, update_time, create_user, update_user from dish where id = ?", dishId);
    }

    /** 前端编辑页提交的真实形状：带 categoryName、price 是字符串、口味带 id/dishId。 */
    private String updateBody(long id, String name, String price, int status, String flavorsJson) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"categoryId\":" + CATEGORY_ID + ","
                + "\"price\":\"" + price + "\",\"image\":\"" + IMAGE + "\",\"description\":\"改过的描述\","
                + "\"status\":" + status + ",\"categoryName\":\"不参与更新的字段\","
                + "\"flavors\":" + flavorsJson + "}";
    }

    /** 名字不能叫 put：会盖掉静态导入的 MockMvcRequestBuilders.put。 */
    private JsonNode submitUpdate(String body) throws Exception {
        String response = mvc.perform(put("/admin/dish").header("token", adminToken())
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response);
    }

    /** 同理，不叫 get。 */
    private JsonNode fetch(String path) throws Exception {
        String response = mvc.perform(get(path).header("token", adminToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response);
    }

    @Test
    void updatesTheRowAndReplacesTheFlavorGroup() throws Exception {
        String name = uniqueName("du");
        long dishId = insertDish(name);
        insertFlavor(dishId, "甜味", "[\"无糖\"]");
        insertFlavor(dishId, "温度", "[\"常温\"]");

        //旁边放一道完全不相关的菜，用来证明替换口味不会波及别人
        long bystanderId = insertDish(uniqueName("du"));
        insertFlavor(bystanderId, "辣度", "[\"微辣\"]");

        JsonNode response = submitUpdate(updateBody(dishId, name, "38.50", 1,
                "[{\"id\":11,\"dishId\":" + dishId + ",\"name\":\"辣度\",\"value\":\"[\\\"中辣\\\"]\"}]"));

        assertEquals(1, response.path("code").asInt(), "修改本应成功：" + response);

        Map<String, Object> row = row(dishId);
        assertEquals(name, row.get("name"));
        assertEquals(0, new BigDecimal("38.50").compareTo((BigDecimal) row.get("price")));
        assertEquals("改过的描述", row.get("description"));
        assertEquals(1, ((Number) row.get("status")).intValue());

        //审计列：@AutoFill(UPDATE) 只刷 update_*，create_* 一旦落库不再变动。
        //这一条是 mock 测试证明不了的——mock 掉 mapper 就绕过了整个切面代理。
        //注意 Connector/J 8 的 getObject() 对 DATETIME 返回的是 LocalDateTime 而不是 java.sql.Timestamp，
        //这里按它实际给的类型断言（写库那边用 Timestamp 传参没问题）。
        LocalDateTime old = LocalDateTime.parse("2020-01-01T00:00:00");
        assertTrue(((LocalDateTime) row.get("update_time")).isAfter(old),
                "update_time 应由切面刷新：" + row.get("update_time"));
        assertEquals(adminId(), ((Number) row.get("update_user")).longValue(), "update_user 应是当前登录员工");
        assertEquals(old, row.get("create_time"), "create_time 不能被更新改动");
        assertEquals(1L, ((Number) row.get("create_user")).longValue(), "create_user 不能被更新改动");

        //口味整组替换：旧的两条没了、新的那条在，且 value 原样存
        assertEquals(List.of("辣度"), flavorNames(dishId));
        assertEquals("[\"中辣\"]", jdbc.queryForObject("select value from dish_flavor where dish_id = ?",
                String.class, dishId));
        //旁边那道菜的口味一行未动
        assertEquals(List.of("辣度"), flavorNames(bystanderId));
    }

    /**
     * 把某行改回它自己当前的名字不能触发唯一冲突。
     * <p>
     * {@code dish.name} 上是全库唯一索引，但"更新成自己已有的值"在 MySQL 里不算冲突——
     * 前端编辑页只要不改名字就必然走这条路径（表单里处处都是原名），
     * 所以这是必须成立的行为，而它在 mock 里根本证明不了（mock 的 update 不会抛）。
     */
    @Test
    void allowsSavingARowUnderItsOwnCurrentName() throws Exception {
        String name = uniqueName("du");
        long dishId = insertDish(name);
        insertFlavor(dishId, "甜味", "[\"无糖\"]");

        //只改价格与口味，名字原样回传
        JsonNode response = submitUpdate(updateBody(dishId, name, "99.90", 0, "[]"));

        assertEquals(1, response.path("code").asInt(), "改回自己当前的名字不该报重名：" + response);
        assertEquals(0, new BigDecimal("99.90").compareTo((BigDecimal) row(dishId).get("price")));
        assertEquals(List.of(), flavorNames(dishId));
    }

    /**
     * 改成另一道存量菜的名字必须被拒，且那一行数据一点没变。
     * <p>
     * 用库里已有的真实菜名（不是自己造的），这才是"撞上存量数据"的真实场景。
     */
    @Test
    void refusesToRenameOntoAnotherExistingDish() throws Exception {
        String name = uniqueName("du");
        long dishId = insertDish(name);
        insertFlavor(dishId, "甜味", "[\"无糖\"]");
        Map<String, Object> before = row(dishId);

        //取另一道菜的菜名（存量 24 道菜，必然存在）
        List<String> other = jdbc.queryForList("select name from dish where id <> ? limit 1", String.class, dishId);
        assertEquals(1, other.size(), "测试前置条件：库里要有别的菜品");
        String occupiedName = other.get(0);

        JsonNode response = submitUpdate(updateBody(dishId, occupiedName, "99.90", 1,
                "[{\"name\":\"新口味\",\"value\":\"[]\"}]"));

        assertEquals(0, response.path("code").asInt());
        //必须显式 catch DuplicateKeyException：否则全局处理器那条分支会渲染成带引号的 '名字'已存在
        assertEquals("菜品名称已存在", response.path("msg").asText());
        assertEquals(before, row(dishId), "被拒时这一行的任何列都不该变");
        assertEquals(List.of("甜味"), flavorNames(dishId), "主表没改成功，口味就不该动");
    }

    /**
     * 编辑页回显的真实行为。
     * <p>
     * flavors 必须是数组、value 是 JSON 字符串、没有口味的菜返回 {@code []}、
     * categoryName 为 null（本轮不 join），并且 {@code /admin/dish/page} 仍然进分页方法
     * ——{@code @GetMapping("/{id}")} 没有抢走这个字面量路径。
     */
    @Test
    void returnsTheDishForTheEditPageAndKeepsThePageRouteWorking() throws Exception {
        String withFlavors = uniqueName("du");
        long dishId = insertDish(withFlavors);
        insertFlavor(dishId, "甜味", "[\"无糖\",\"多糖\"]");
        insertFlavor(dishId, "温度", "[\"常温\"]");

        JsonNode data = fetch("/admin/dish/" + dishId).path("data");

        assertEquals(dishId, data.path("id").asLong());
        assertEquals(withFlavors, data.path("name").asText());
        assertEquals(CATEGORY_ID, data.path("categoryId").asLong());
        assertTrue(data.path("price").isNumber(), "price 应为 JSON number：" + data.path("price"));
        //本轮不 join category：编辑页不读它，不该为此多写一条 SQL
        assertTrue(data.path("categoryName").isNull(), "categoryName 应为 null：" + data.path("categoryName"));
        //updateTime 经 JacksonObjectMapper 输出成 "yyyy-MM-dd HH:mm" 字符串
        assertTrue(data.path("updateTime").asText().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"),
                "updateTime 格式不符合 yyyy-MM-dd HH:mm：" + data.path("updateTime"));

        JsonNode flavors = data.path("flavors");
        assertTrue(flavors.isArray(), "flavors 必须是数组：" + flavors);
        assertEquals(2, flavors.size());
        assertEquals("甜味", flavors.get(0).path("name").asText());
        //value 是 JSON 字符串（前端会 JSON.parse 它），不是被解析开的数组
        assertTrue(flavors.get(0).path("value").isTextual(), "value 应是 JSON 字符串：" + flavors.get(0));
        assertEquals("[\"无糖\",\"多糖\"]", flavors.get(0).path("value").asText());

        //没有口味的菜：必须是 []，null 会让前端 .map 抛 TypeError 把整页打废
        long plainId = insertDish(uniqueName("du"));
        JsonNode plain = fetch("/admin/dish/" + plainId).path("data").path("flavors");
        assertTrue(plain.isArray(), "没有口味时 flavors 必须是数组：" + plain);
        assertEquals(0, plain.size());

        //不存在 → 业务错误，不是 500
        JsonNode missing = fetch("/admin/dish/999999999");
        assertEquals(0, missing.path("code").asInt());
        assertEquals("菜品不存在", missing.path("msg").asText());

        //两条路由不打架：/admin/dish/page 仍然进分页方法，返回的是 {total, records}
        JsonNode page = fetch("/admin/dish/page?page=1&pageSize=5").path("data");
        assertTrue(page.has("total"), "分页响应应含 total：" + page);
        assertTrue(page.has("records"), "分页响应应含 records：" + page);
    }

    /**
     * 非数字 id 的真实行为：HTTP 400 + <b>空响应体</b>。
     * <p>
     * "abc" 到 Long 的转换在进入 Controller 之前就失败了（MethodArgumentTypeMismatchException），
     * 由 Spring 默认的 DefaultHandlerExceptionResolver 处理成 400，GlobalExceptionHandler 没有对应分支。
     * 与第 13 节记录的 {@code ?page=abc} 是同一类现状：不是本接口特有的问题，
     * 改的话是全局口径的事。这里如实断言"它没有 500、也没有查库"。
     */
    @Test
    void rejectsANonNumericIdAsASpringLevelBadRequest() throws Exception {
        var result = mvc.perform(get("/admin/dish/abc").header("token", adminToken())).andReturn();

        assertEquals(400, result.getResponse().getStatus());
        assertEquals("", result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "非数字 id 走 Spring 默认 400 空 body，不是项目的 Result 结构");
        //真库上下文（完整 WebMvcConfiguration）里这个异常同样能被读到，与 mock 测试断言的一致
        assertTrue(result.getResolvedException() instanceof MethodArgumentTypeMismatchException,
                "预期是路径变量类型转换失败：" + result.getResolvedException());
    }
}
