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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 按分类id或名称查询菜品列表的真实 MySQL 联调：验证 mock 测不到的动态 SQL 本身。
 * <p>
 * 几件事只有连真库才能证明：
 * <ul>
 *   <li>动态 {@code <where>} 的三种组合（只有 categoryId、只有 name、两个都要）生成的 SQL 都能被
 *       MySQL 执行，且条件真的生效——不是"看起来筛了其实没筛"；</li>
 *   <li><b>停售的菜品确实会出现在结果里</b>，这是本轮那个业务决定的真库守卫：一旦有人给这条 SQL
 *       加上 {@code status = 1}，下面第一条用例就会变红；</li>
 *   <li>调用方传 {@code ?status=1} 也不会改变结果（该参数根本没有被声明，只是被忽略）；</li>
 *   <li>返回的是实体形态，{@code create_time} / {@code create_user} / {@code update_user} 三列真的带出来了；</li>
 *   <li>没有命中时 {@code data} 是 {@code []} 而不是 null。</li>
 * </ul>
 * 类上的 {@code @Transactional} 让每个用例结束时回滚，测试自己造的数据不会留在库里。
 */
@EnabledIfEnvironmentVariable(named = "SKY_DB_TESTS", matches = "true")
@SpringBootTest(properties = "logging.level.com.sky.mapper=INFO")
@AutoConfigureMockMvc
@Transactional
class DishListDatabaseTest {

    /** 联调用的分类id：dish.category_id 上没有外键，取一个库中存在的分类即可（与前几轮联调一致）。 */
    private static final long CATEGORY_ID = 16L;
    /** 另一个分类，用来证明 categoryId 条件真的在筛而不是恒真。 */
    private static final long OTHER_CATEGORY_ID = 17L;
    private static final String OLD_TIME = "2020-01-01 00:00:00";

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

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        assertNotNull(value);
        return value;
    }

    /** 菜品名有全库唯一索引，用 UUID 保证每次联调都不撞名。 */
    private String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private long insertDish(String name, long categoryId, int status) {
        Timestamp old = Timestamp.valueOf(OLD_TIME);
        jdbc.update("insert into dish (name, category_id, price, image, description, status,"
                        + " create_time, update_time, create_user, update_user)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                name, categoryId, new BigDecimal("18.80"),
                "https://sky-itcast.oss-cn-hangzhou.aliyuncs.com/legacy.png", "列表联调",
                status, old, old, 1L, 2L);
        Long id = jdbc.queryForObject("select id from dish where name = ?", Long.class, name);
        assertNotNull(id, "测试数据未按预期写入");
        return id;
    }

    /** 按前端实际请求方式调用：查询参数用 .param 传，避免百分号转义不被解码的坑。名字不叫 get。 */
    private JsonNode fetch(Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request = get("/admin/dish/list").header("token", adminToken());
        params.forEach(request::param);
        String response = mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response);
    }

    private Set<Long> idsOf(JsonNode data) {
        Set<Long> ids = new HashSet<>();
        data.forEach(node -> ids.add(node.path("id").asLong()));
        return ids;
    }

    /**
     * 分类下的菜全都要返回，**包括停售的**。
     * <p>
     * 这条用例是本轮那个业务决定的真库守卫：选菜组件对 {@code status = 0} 的菜渲染「停售」标签，
     * 而 {@code SETMEAL_ENABLE_FAILED}「套餐内包含未启售菜品，无法启售」说明停售菜品本就可以先进
     * 套餐。谁要给 {@code DishMapper.xml} 的 list 加上 {@code status = 1}，这里立刻变红。
     */
    @Test
    void returnsEveryDishOfTheCategoryIncludingOffSaleOnes() throws Exception {
        long onSale = insertDish(uniqueName("dl"), CATEGORY_ID, 1);
        long offSale = insertDish(uniqueName("dl"), CATEGORY_ID, 0);
        long otherCategory = insertDish(uniqueName("dl"), OTHER_CATEGORY_ID, 1);

        JsonNode data = fetch(Map.of("categoryId", String.valueOf(CATEGORY_ID))).path("data");

        assertTrue(data.isArray(), "data 必须是数组：" + data);
        Set<Long> ids = idsOf(data);
        assertTrue(ids.contains(onSale), "起售的菜应该在结果里");
        assertTrue(ids.contains(offSale), "停售的菜也必须在结果里——本接口有意不过滤 status");
        assertFalse(ids.contains(otherCategory), "别的分类的菜不该混进来");
        //每一条的 categoryId 都得是筛的那个，顺带证明条件不是恒真
        data.forEach(node -> assertEquals(CATEGORY_ID, node.path("categoryId").asLong()));
    }

    /** 只发 name（前端关键字搜索那一支）：不能因为缺 categoryId 而失败，且模糊匹配真的生效。 */
    @Test
    void searchesByNameWithoutRequiringACategoryId() throws Exception {
        String token = uniqueName("dl");
        long target = insertDish("招牌" + token + "鱼", CATEGORY_ID, 0);
        long unrelated = insertDish(uniqueName("dl"), CATEGORY_ID, 0);

        JsonNode data = fetch(Map.of("name", token)).path("data");

        Set<Long> ids = idsOf(data);
        assertTrue(ids.contains(target), "按关键字应当能搜到：" + data);
        assertFalse(ids.contains(unrelated), "不该把无关的菜也带出来");
    }

    /** 两端空白要被去掉：带空格的输入与不带的结果一致。 */
    @Test
    void trimsWhitespaceAroundTheName() throws Exception {
        String token = uniqueName("dl");
        long target = insertDish("香辣" + token, CATEGORY_ID, 0);

        JsonNode padded = fetch(Map.of("name", "  " + token + "  ")).path("data");

        assertTrue(idsOf(padded).contains(target), "两端空白应当被去掉后匹配：" + padded);
    }

    /** 两个条件叠加时是 AND：分类不对就该查不到，哪怕名字能匹配上。 */
    @Test
    void combinesCategoryAndNameWithAndSemantics() throws Exception {
        String token = uniqueName("dl");
        long target = insertDish("干锅" + token, CATEGORY_ID, 0);

        JsonNode hit = fetch(Map.of("categoryId", String.valueOf(CATEGORY_ID), "name", token)).path("data");
        assertTrue(idsOf(hit).contains(target), "分类与名字都对时应当命中：" + hit);

        JsonNode miss = fetch(Map.of("categoryId", String.valueOf(OTHER_CATEGORY_ID), "name", token)).path("data");
        assertFalse(idsOf(miss).contains(target), "分类不对时不该命中（两个条件应当是 AND）：" + miss);
        assertTrue(miss.isArray(), "没命中时也必须是数组");
        assertEquals(0, miss.size());
    }

    /**
     * 调用方传 {@code ?status=1} 也不会改变结果。
     * <p>
     * 本接口没有声明 status 参数，Spring 会直接忽略它。这条钉住的是"别顺手把它接进来当筛选条件"
     * ——一旦接了，上面第一条用例里那道德停售的菜就会消失。
     */
    @Test
    void ignoresAStatusParameterPassedByTheCaller() throws Exception {
        long offSale = insertDish(uniqueName("dl"), CATEGORY_ID, 0);

        JsonNode data = fetch(Map.of("categoryId", String.valueOf(CATEGORY_ID), "status", "1")).path("data");

        assertTrue(idsOf(data).contains(offSale), "status 参数应当被忽略，停售的菜还在：" + data);
    }

    /** 返回实体形态：接口文档的响应模型列了 createTime / createUser / updateUser，这三列只在实体上有。 */
    @Test
    void returnsTheDishEntityShapeIncludingTheAuditColumns() throws Exception {
        long dishId = insertDish(uniqueName("dl"), CATEGORY_ID, 1);

        JsonNode data = fetch(Map.of("categoryId", String.valueOf(CATEGORY_ID))).path("data");
        JsonNode mine = null;
        for (JsonNode node : data) {
            if (node.path("id").asLong() == dishId) {
                mine = node;
            }
        }
        assertNotNull(mine, "自己插的那条应当出现在结果里：" + data);

        assertEquals(CATEGORY_ID, mine.path("categoryId").asLong());
        assertTrue(mine.path("price").isNumber(), "price 应为 JSON number：" + mine.path("price"));
        assertEquals(1, mine.path("status").asInt());
        //updateTime 经 JacksonObjectMapper 输出成 "yyyy-MM-dd HH:mm" 字符串
        assertTrue(mine.path("updateTime").asText().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"),
                "updateTime 格式不符合 yyyy-MM-dd HH:mm：" + mine.path("updateTime"));
        //这三列是"必须返回实体而非 DishVO"的依据（DishVO 里没有它们）
        assertTrue(mine.has("createTime"), "响应应含 createTime：" + mine);
        assertTrue(mine.has("createUser"), "响应应含 createUser：" + mine);
        assertTrue(mine.has("updateUser"), "响应应含 updateUser：" + mine);
    }

    /**
     * 没有命中时 {@code data} 是 {@code []} 而不是 null。
     * <p>
     * 前端选菜组件直接读 {@code res.data.data.length}，null 会让弹窗抛 TypeError
     * （与第 21 节 flavors 那条同一类硬约束）。
     */
    @Test
    void returnsAnEmptyArrayInsteadOfNullWhenNothingMatches() throws Exception {
        JsonNode data = fetch(Map.of("name", "绝不存在的菜名_" + UUID.randomUUID())).path("data");

        assertTrue(data.isArray(), "没有命中时 data 必须是数组：" + data);
        assertEquals(0, data.size());
    }

    /**
     * 不分页：分类下的菜有多少返回多少。
     * <p>
     * 用"调用前后的行数自比"而不是写死数字：将来正常业务往库里加菜不会让它变红。
     * 顺带证明这条 SQL 没有受 PageHelper 的影响——同类接口里只有 pageQuery 会走分页。
     */
    @Test
    void doesNotPaginate() throws Exception {
        int expected = count("select count(*) from dish where category_id = ?", CATEGORY_ID);

        JsonNode data = fetch(Map.of("categoryId", String.valueOf(CATEGORY_ID))).path("data");

        assertEquals(expected, data.size(), "分类下的菜应当一条不漏地返回（本接口不分页）");
    }

    /**
     * 本类的测试数据一行都不该留在库里：类上的 {@code @Transactional} 会在每个用例结束时回滚。
     * <p>
     * 只断言本类专用的 {@code dl_} 名字前缀，所以不会与库里的真实菜品或别的测试类互相干扰。
     */
    @Test
    void leavesNoTestDataBehind() {
        assertEquals(0, count("select count(*) from dish where name like ?", "%dl\\_%"),
                "有测试数据提交进库了：先确认类上的 @Transactional 还在");
    }
}
