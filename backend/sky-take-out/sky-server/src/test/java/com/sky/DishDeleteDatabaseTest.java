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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批量删除菜品的真实 MySQL 联调：验证 mock 测不到的 SQL 本身。
 * <p>
 * 几件事只有连真库才能证明：
 * <ul>
 *   <li>{@code in <foreach>} 的绑定真的解析成功（单参数集合漏写 {@code @Param("ids")} 时，
 *       这里会在第一次调用抛 BindingException，而 mock 测试完全看不出来）；</li>
 *   <li>两条守卫查的是"这批 id 里"的起售菜品与套餐引用，而不是整表；</li>
 *   <li>{@code deleteByDishIds} 的 in 条件只命中所删菜品的口味，别的菜品一行不动；</li>
 *   <li>解析失败时一条 SQL 都没发出去（表里的行数与调用前一致）。</li>
 * </ul>
 * 类上的 {@code @Transactional} 让每个用例结束时回滚，测试自己造的数据不会留在库里。
 * <p>
 * 注意：库里存量 24 道菜全部是 {@code status = 1}，所以成功路径必须自己插 {@code status = 0} 的菜；
 * 直接拿存量菜品当删除目标会撞上"起售中的菜品不能删除"守卫。
 */
@EnabledIfEnvironmentVariable(named = "SKY_DB_TESTS", matches = "true")
@SpringBootTest(properties = "logging.level.com.sky.mapper=INFO")
@AutoConfigureMockMvc
@Transactional
class DishDeleteDatabaseTest {

    /** 联调用的分类id。dish.category_id 上没有外键，取一个库中存在的分类即可（与分页联调一致）。 */
    private static final long CATEGORY_ID = 16L;
    /** 套餐id同理：setmeal 表当前是空的，setmeal_dish 上没有外键，随便一个值就能插进去。 */
    private static final long SETMEAL_ID = 999999L;

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

    private JsonNode call(String ids) throws Exception {
        String body = mvc.perform(delete("/admin/dish").header("token", adminToken()).param("ids", ids))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        assertNotNull(value);
        return value;
    }

    private int dishFlavorCount(long dishId) {
        return count("select count(*) from dish_flavor where dish_id = ?", dishId);
    }

    /** 菜品名有全库唯一索引，用 UUID 保证每次联调都不撞名。 */
    private String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 插一道菜，主键由数据库分配；返回自增主键。 */
    private long insertDish(String name, int status) {
        Timestamp now = Timestamp.valueOf("2026-09-23 10:00:00");
        jdbc.update("insert into dish (name, category_id, price, image, description, status,"
                        + " create_time, update_time, create_user, update_user)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                name, CATEGORY_ID, new BigDecimal("12.50"), "/uploads/2026/09/23/a.png", "删除联调",
                status, now, now, 1L, 1L);
        Long id = jdbc.queryForObject("select id from dish where name = ?", Long.class, name);
        assertNotNull(id, "测试数据未按预期写入");
        return id;
    }

    /** dish_flavor 没有审计列，直接给 dish_id/name/value 三列。 */
    private void insertFlavor(long dishId, String name, String value) {
        jdbc.update("insert into dish_flavor (dish_id, name, value) values (?, ?, ?)", dishId, name, value);
    }

    /** 把菜品挂到一个套餐上（setmeal_dish 是中间表，没有外键）。 */
    private void insertSetmealDish(long dishId) {
        jdbc.update("insert into setmeal_dish (setmeal_id, dish_id, name, price, copies) values (?, ?, ?, ?, ?)",
                SETMEAL_ID, dishId, "删除联调菜品", new BigDecimal("12.50"), 1);
    }

    @Test
    void deletesTheDishAndItsFlavors() throws Exception {
        long dishId = insertDish(uniqueName("dd"), 0);
        insertFlavor(dishId, "甜味", "[\"无糖\",\"多糖\"]");
        insertFlavor(dishId, "温度", "[\"常温\"]");
        assertEquals(2, dishFlavorCount(dishId), "测试前置条件：口味没插进去");

        JsonNode response = call(String.valueOf(dishId));

        assertEquals(1, response.path("code").asInt());
        //主表与子表都要干净：只删主表会留下两条 dish_id 悬空的口味行
        assertEquals(0, count("select count(*) from dish where id = ?", dishId));
        assertEquals(0, dishFlavorCount(dishId));
        //顺带证明 countOnSaleByIds 的 in 条件真的生效：库里另外 24 道菜全是起售，
        //那条 SQL 一旦丢掉 id in (...) 就会命中它们，这次删除会直接报"起售中的菜品不能删除"。
    }

    @Test
    void refusesToDeleteAnOnSaleDish() throws Exception {
        //存量菜品全是 status = 1，成功路径必须自己造停售的菜；这条守卫则专门造一道起售的
        long dishId = insertDish(uniqueName("dd"), 1);
        insertFlavor(dishId, "甜味", "[\"无糖\"]");

        JsonNode response = call(String.valueOf(dishId));

        assertEquals(0, response.path("code").asInt());
        assertEquals("起售中的菜品不能删除", response.path("msg").asText());
        //被拒时菜品与口味都必须原样还在
        assertEquals(1, count("select count(*) from dish where id = ?", dishId));
        assertEquals(1, dishFlavorCount(dishId));
    }

    @Test
    void refusesToDeleteADishRelatedByASetmeal() throws Exception {
        long dishId = insertDish(uniqueName("dd"), 0);
        insertFlavor(dishId, "甜味", "[\"无糖\"]");
        insertSetmealDish(dishId);

        JsonNode response = call(String.valueOf(dishId));

        assertEquals(0, response.path("code").asInt());
        assertEquals("当前菜品关联了套餐,不能删除", response.path("msg").asText());
        assertEquals(1, count("select count(*) from dish where id = ?", dishId));
        assertEquals(1, dishFlavorCount(dishId));
        //中间表那一行不能被这次拒绝波及
        assertEquals(1, count("select count(*) from setmeal_dish where dish_id = ?", dishId));
    }

    @Test
    void deletesABatchAndIgnoresUnknownIds() throws Exception {
        long first = insertDish(uniqueName("dd"), 0);
        long second = insertDish(uniqueName("dd"), 0);
        insertFlavor(first, "甜味", "[\"无糖\"]");
        long unknown = 999999L;
        assertEquals(0, count("select count(*) from dish where id = ?", unknown), "测试前置条件：999999 不该存在");

        //前端把勾选的所有 id 一次性发过来，其中可能混着已经被别人删掉的 id
        JsonNode response = call(first + "," + second + "," + unknown);

        assertEquals(1, response.path("code").asInt());
        assertEquals(0, count("select count(*) from dish where id = ?", first));
        assertEquals(0, count("select count(*) from dish where id = ?", second));
        assertEquals(0, dishFlavorCount(first));
    }

    @Test
    void onlyDeletesTheTargetedDishAndItsFlavors() throws Exception {
        long target = insertDish(uniqueName("dd"), 0);
        long bystander = insertDish(uniqueName("dd"), 0);
        insertFlavor(target, "甜味", "[\"无糖\"]");
        insertFlavor(target, "温度", "[\"常温\"]");
        insertFlavor(bystander, "辣度", "[\"微辣\"]");

        assertEquals(1, call(String.valueOf(target)).path("code").asInt());

        assertEquals(0, count("select count(*) from dish where id = ?", target));
        assertEquals(0, dishFlavorCount(target));
        //旁边那道菜一口都不能动：deleteByDishIds 的 in 条件必须只命中所删菜品
        assertEquals(1, count("select count(*) from dish where id = ?", bystander));
        assertEquals(1, dishFlavorCount(bystander));
    }

    @Test
    void rejectsMalformedIdsWithoutTouchingTheTable() throws Exception {
        int dishesBefore = count("select count(*) from dish");
        int flavorsBefore = count("select count(*) from dish_flavor");

        JsonNode response = call("abc");

        assertEquals(0, response.path("code").asInt());
        assertEquals("菜品id格式错误", response.path("msg").asText());
        //解析阶段就整体拒绝，连一条 SQL 都不该发出去
        assertEquals(dishesBefore, count("select count(*) from dish"));
        assertEquals(flavorsBefore, count("select count(*) from dish_flavor"));
    }

    /**
     * 幂等：删一个不存在的 id 不该动到任何现存行。
     * <p>
     * 这是"删除语句丢了 where"的兜底——那种情况下整表会被清空，而按 id 断言的那几条用例
     * 各自只看自己造的那几行，发现不了（{@link #onlyDeletesTheTargetedDishAndItsFlavors}
     * 只能证明旁观的那一道菜还在，证明不了整表没被扫掉）。
     * <p>
     * 用调用前后的行数自比，不写死具体数字：将来正常业务往库里加菜、改状态都不会让它变红。
     */
    @Test
    void deletingAnUnknownIdLeavesEveryExistingRowInPlace() throws Exception {
        int dishesBefore = count("select count(*) from dish");
        int flavorsBefore = count("select count(*) from dish_flavor");
        assertTrue(dishesBefore > 0, "测试前置条件：库里要有菜品数据");

        assertEquals(1, call("999999").path("code").asInt());

        assertEquals(dishesBefore, count("select count(*) from dish"), "删除不存在的 id 不该动到任何现存行");
        assertEquals(flavorsBefore, count("select count(*) from dish_flavor"));
    }

    /**
     * 本类的测试数据一行都不该留在库里：类上的 {@code @Transactional} 会在每个用例结束时回滚。
     * <p>
     * 只断言本类专用的 {@code dd_} 名字前缀，所以不会与库里的真实菜品或别的测试类互相干扰。
     */
    @Test
    void leavesNoTestDataBehind() {
        assertEquals(0, count("select count(*) from dish where name like ?", "dd\\_%"),
                "有测试数据提交进库了：先确认类上的 @Transactional 还在");
    }
}
