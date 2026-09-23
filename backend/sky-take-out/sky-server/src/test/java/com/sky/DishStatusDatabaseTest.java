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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 菜品起售、停售的真实 MySQL 联调：验证 mock 测不到的 SQL 本身与切面织入。
 * <p>
 * 几件事只有连真库才能证明：
 * <ul>
 *   <li>{@code @AutoFill(UPDATE)} 真的织到了 {@code DishMapper.update} 上：库里
 *       {@code update_time} / {@code update_user} 被刷新，而 {@code create_time} /
 *       {@code create_user} 一行没动（mock 掉 mapper 就绕过了整个代理，证明不了这一点）；</li>
 *   <li>动态 {@code <set>} 生成的 SQL 只含 status 一列，{@code where id} 只改目标行
 *       ——名称、价格、图片、描述与旁边那道菜都原样不动；</li>
 *   <li><b>存量 24 道全起售的菜终于可以停售、进而可以删除了</b>，这是本接口存在的理由
 *       （第 20 节记录的"界面上连停售都点不动"，那条限制到此为止）；</li>
 *   <li>被拒时（状态值非法、菜品不存在）表里一行都不变。</li>
 * </ul>
 * 类上的 {@code @Transactional} 让每个用例结束时回滚，测试自己造的数据不会留在库里。
 */
@EnabledIfEnvironmentVariable(named = "SKY_DB_TESTS", matches = "true")
@SpringBootTest(properties = "logging.level.com.sky.mapper=INFO")
@AutoConfigureMockMvc
@Transactional
class DishStatusDatabaseTest {

    /** 联调用的分类id：dish.category_id 上没有外键，取一个库中存在的分类即可（与前三轮联调一致）。 */
    private static final long CATEGORY_ID = 16L;
    /** 测试数据一律插成这个"很久以前"的时间，用来证明 update_time 确实被切面刷新了。 */
    private static final String OLD_TIME = "2020-01-01 00:00:00";
    /**
     * 用 OSS 绝对地址而不是 /uploads/...：本类不关心文件系统，而本地路径会被"图片文件必须真的在磁盘上"
     * 的校验拦下（那是 DishImageRollbackDatabaseTest 的活）。库里 24 条存量数据也是这个形态。
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

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        assertNotNull(value);
        return value;
    }

    /** 菜品名有全库唯一索引，用 UUID 保证每次联调都不撞名。 */
    private String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 插一道菜，主键由数据库分配；审计列用固定的旧时间，方便断言哪些被改了、哪些没被改。 */
    private long insertDish(String name, int status) {
        Timestamp old = Timestamp.valueOf(OLD_TIME);
        jdbc.update("insert into dish (name, category_id, price, image, description, status,"
                        + " create_time, update_time, create_user, update_user)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                name, CATEGORY_ID, new BigDecimal("12.50"), IMAGE, "起售停售联调", status, old, old, 1L, 1L);
        Long id = jdbc.queryForObject("select id from dish where name = ?", Long.class, name);
        assertNotNull(id, "测试数据未按预期写入");
        return id;
    }

    /** dish_flavor 没有审计列，直接给 dish_id/name/value 三列。 */
    private void insertFlavor(long dishId, String name, String value) {
        jdbc.update("insert into dish_flavor (dish_id, name, value) values (?, ?, ?)", dishId, name, value);
    }

    private Map<String, Object> row(long dishId) {
        return jdbc.queryForMap("select name, category_id, price, image, description, status,"
                + " create_time, update_time, create_user, update_user from dish where id = ?", dishId);
    }

    /** 读库里的 status 列本身，用于"被拒 / 停售后状态确实变了"这类断言。 */
    private int statusOf(long dishId) {
        Integer status = jdbc.queryForObject("select status from dish where id = ?", Integer.class, dishId);
        assertNotNull(status, "菜品应存在：" + dishId);
        return status;
    }

    /** 按前端实际请求方式调用：status 在路径上、id 在查询串上。名字不叫 post，避免盖掉静态导入。 */
    private JsonNode switchStatus(long dishId, Object status) throws Exception {
        String response = mvc.perform(post("/admin/dish/status/" + status)
                        .header("token", adminToken()).param("id", String.valueOf(dishId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response);
    }

    @Test
    void putsADishOnSaleAndOnlyRefreshesTheUpdateColumns() throws Exception {
        String name = uniqueName("ds");
        long dishId = insertDish(name, 0);
        insertFlavor(dishId, "甜味", "[\"无糖\"]");
        Map<String, Object> before = row(dishId);

        JsonNode response = switchStatus(dishId, 1);

        assertEquals(1, response.path("code").asInt(), "起售本应成功：" + response);

        Map<String, Object> after = row(dishId);
        assertEquals(1, ((Number) after.get("status")).intValue(), "status 应变成起售(1)");

        //审计列：@AutoFill(UPDATE) 只刷 update_*，create_* 一旦落库不再变动。
        //这一条是 mock 测试证明不了的——mock 掉 mapper 就绕过了整个切面代理。
        //注意 Connector/J 8 的 getObject() 对 DATETIME 返回的是 LocalDateTime 而不是 java.sql.Timestamp。
        LocalDateTime old = LocalDateTime.parse("2020-01-01T00:00:00");
        assertTrue(((LocalDateTime) after.get("update_time")).isAfter(old),
                "update_time 应由切面刷新：" + after.get("update_time"));
        assertEquals(adminId(), ((Number) after.get("update_user")).longValue(), "update_user 应是当前登录员工");
        assertEquals(old, after.get("create_time"), "create_time 不能被起售停售改动");
        assertEquals(1L, ((Number) after.get("create_user")).longValue(), "create_user 不能被起售停售改动");

        //status 是唯一被改的业务列：动态 <set> 只会写这一列，其余列连 SQL 里都不出现
        before.forEach((column, value) -> {
            if (!"update_time".equals(column) && !"update_user".equals(column) && !"status".equals(column)) {
                assertEquals(value, after.get(column), "起售停售不该改动 " + column);
            }
        });
        //带口味的菜也一样：本接口不碰 dish_flavor
        assertEquals(1, count("select count(*) from dish_flavor where dish_id = ?", dishId));
    }

    @Test
    void takesADishOffSale() throws Exception {
        long dishId = insertDish(uniqueName("ds"), 1);
        Map<String, Object> before = row(dishId);

        assertEquals(1, switchStatus(dishId, 0).path("code").asInt());

        assertEquals(0, ((Number) row(dishId).get("status")).intValue(), "status 应变成停售(0)");
        before.forEach((column, value) -> {
            if (!"update_time".equals(column) && !"update_user".equals(column) && !"status".equals(column)) {
                assertEquals(value, row(dishId).get(column), "停售不该改动 " + column);
            }
        });
    }

    /**
     * 存量那 24 道全起售的菜，从此可以在界面上停售并删除了。
     * <p>
     * 这是本接口存在的理由：第 20 节记录过"存量菜品在界面上删不掉——它们全是 status = 1，
     * 而停售接口尚未实现，要删只能在库里 update"。这里把那条限制走一遍完整链路：
     * 挑一道存量起售菜品 → 停售 → 删除，两步都必须成功，且删除后口味也一并清掉。
     * <p>
     * 用的是库里已有的真实菜品（不是自己造的），所以这才是"存量数据"的真实场景；
     * 类上的 {@code @Transactional} 保证用例结束时这道菜连同它的口味原样回滚回来。
     */
    @Test
    void letsAPreviouslyUndeletableDishBeTakenOffSaleAndThenDeleted() throws Exception {
        //挑一道口味数量已知的存量起售菜品，顺带能验证删除是否清干净了子表
        List<Long> candidates = jdbc.queryForList(
                "select id from dish where status = 1 order by id limit 1", Long.class);
        assertEquals(1, candidates.size(), "测试前置条件：库里要有起售的菜品（存量 24 道即如此）");
        long dishId = candidates.get(0);
        int flavorsBefore = count("select count(*) from dish_flavor where dish_id = ?", dishId);

        //第一步：起售中的菜照旧删不掉
        String refused = mvc.perform(delete("/admin/dish").header("token", adminToken())
                        .param("ids", String.valueOf(dishId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, json.readTree(refused).path("code").asInt());
        assertEquals(1, statusOf(dishId), "被删失败不该改变状态");

        //第二步：停售，这一步以前不存在，正是它把界面上的死路打通
        assertEquals(1, switchStatus(dishId, 0).path("code").asInt(), "存量菜品应当可以停售");
        assertEquals(0, statusOf(dishId));

        //第三步：再删就成功了
        String deleted = mvc.perform(delete("/admin/dish").header("token", adminToken())
                        .param("ids", String.valueOf(dishId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(1, json.readTree(deleted).path("code").asInt(), "停售后应当可以删除：" + deleted);
        assertEquals(0, count("select count(*) from dish where id = ?", dishId));
        assertEquals(0, count("select count(*) from dish_flavor where dish_id = ?", dishId),
                "删除应当连口味一起清掉（原样 " + flavorsBefore + " 条）");
    }

    @Test
    void rejectsAnUnknownStatusAndLeavesTheRowUntouched() throws Exception {
        long dishId = insertDish(uniqueName("ds"), 0);
        Map<String, Object> before = row(dishId);

        JsonNode response = switchStatus(dishId, 2);

        assertEquals(0, response.path("code").asInt());
        assertEquals("菜品状态值不合法，只能为1(起售)或0(停售)", response.path("msg").asText());
        assertEquals(before, row(dishId), "被拒时这一行的任何列都不该变");
    }

    @Test
    void rejectsAnUnknownDish() throws Exception {
        JsonNode response = switchStatus(999999L, 1);

        assertEquals(0, response.path("code").asInt());
        assertEquals("菜品不存在", response.path("msg").asText());
    }

    @Test
    void rejectsAMissingDishId() throws Exception {
        String response = mvc.perform(post("/admin/dish/status/1").header("token", adminToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode body = json.readTree(response);
        assertEquals(0, body.path("code").asInt());
        assertEquals("菜品id不能为空", body.path("msg").asText());
    }

    /**
     * 本类的测试数据一行都不该留在库里：类上的 {@code @Transactional} 会在每个用例结束时回滚。
     * <p>
     * 只断言本类专用的 {@code ds_} 名字前缀，所以不会与库里的真实菜品或别的测试类互相干扰。
     */
    @Test
    void leavesNoTestDataBehind() {
        assertEquals(0, count("select count(*) from dish where name like ?", "ds\\_%"),
                "有测试数据提交进库了：先确认类上的 @Transactional 还在");
    }
}
