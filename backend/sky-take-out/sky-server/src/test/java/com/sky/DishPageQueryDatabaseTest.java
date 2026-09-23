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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 菜品分页查询的真实 MySQL 联调：验证 mock 测不到的 SQL 本身。
 * 几件事只有连真库才能证明：
 * <ul>
 *   <li>left join category 取到了分类名称，且 category_id 悬空的菜品不会因为 join 不上而消失；</li>
 *   <li>status 传空串时 SQL 里确实没有 status 条件（不是被拼成 = ''）；</li>
 *   <li>排序是 update_time desc, id desc，翻页拼接起来不重不漏；</li>
 *   <li>时间字段经真实消息转换器输出为 "yyyy-MM-dd HH:mm" 字符串。</li>
 * </ul>
 * 测试事务结束自动回滚，不修改库里的存量数据。
 */
@EnabledIfEnvironmentVariable(named = "SKY_DB_TESTS", matches = "true")
@SpringBootTest(properties = "logging.level.com.sky.mapper=INFO")
@AutoConfigureMockMvc
@Transactional
class DishPageQueryDatabaseTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private EmployeeMapper employeeMapper;
    @Autowired private JwtProperties jwt;
    @Autowired private JdbcTemplate jdbc;

    private static final int PAGE_SIZE = 5;

    private String adminToken() {
        Employee admin = employeeMapper.getByUsername("admin");
        assertNotNull(admin, "数据库需存在启用的 admin 员工");
        assertEquals(1, admin.getStatus());
        return JwtUtil.createJWT(jwt.getAdminSecretKey(), jwt.getAdminTtl(), Map.of("empId", admin.getId()));
    }

    /**
     * 用 param() 传参，值已是解码后的明文；filters 里值为空串时会发出 ?key= 形状的请求，
     * 这正是前端 dishStatus 初始值为 '' 时发出来的样子。
     */
    private JsonNode page(int pageNo, int pageSize, Map<String, String> filters) throws Exception {
        var request = get("/admin/dish/page")
                .header("token", adminToken())
                .param("page", String.valueOf(pageNo))
                .param("pageSize", String.valueOf(pageSize));
        filters.forEach(request::param);
        String body = mvc.perform(request)
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body).path("data");
    }

    private JsonNode page(Map<String, String> filters) throws Exception {
        return page(1, PAGE_SIZE, filters);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        assertNotNull(value);
        return value;
    }

    private List<Long> recordIds(JsonNode data) {
        List<Long> ids = new ArrayList<>();
        data.path("records").forEach(record -> ids.add(record.path("id").asLong()));
        return ids;
    }

    private String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 库里为 null 的列，响应里也应当是 null，而不是空串或凭空多出来的值。 */
    private void assertSameText(Object dbValue, JsonNode jsonValue, String field) {
        if (dbValue == null) {
            assertTrue(jsonValue.isNull() || jsonValue.isMissingNode(),
                    field + " 在库中为 null，响应里却是：" + jsonValue);
        } else {
            assertEquals(String.valueOf(dbValue), jsonValue.asText(), field + " 与库中不一致");
        }
    }

    /** 插一条起售菜品，主键由数据库分配；update_time 由调用方指定，用于验证排序。 */
    private long insertDish(String name, long categoryId, String updateTime) {
        return insertDish(name, categoryId, updateTime, 1);
    }

    private long insertDish(String name, long categoryId, String updateTime, int status) {
        jdbc.update("insert into dish (name, category_id, price, image, description, status,"
                        + " create_time, update_time, create_user, update_user)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                name, categoryId, new BigDecimal("12.50"), "/uploads/2026/09/23/a.png", "分页联调",
                status, Timestamp.valueOf(updateTime), Timestamp.valueOf(updateTime), 1L, 1L);
        Long id = jdbc.queryForObject("select id from dish where name = ?", Long.class, name);
        assertNotNull(id, "测试数据未按预期写入");
        return id;
    }

    @Test
    void returnsTheJoinedCategoryNameAndEveryDocumentedColumn() throws Exception {
        //挑一道分类确实存在的菜，断言 categoryName 来自 category 表而不是菜品表
        Map<String, Object> row = jdbc.queryForMap("select d.id, d.name, d.category_id, d.price,"
                + " d.image, d.description, d.status, c.name as category_name"
                + " from dish d join category c on d.category_id = c.id order by d.id limit 1");
        String name = (String) row.get("name");
        String categoryName = (String) row.get("category_name");
        assertNotNull(categoryName, "测试前置条件：库里要有分类存在的菜品");

        JsonNode data = page(1, 10, Map.of("name", name));

        assertEquals(1, data.path("total").asLong());
        JsonNode record = data.path("records").get(0);
        assertEquals(((Number) row.get("id")).longValue(), record.path("id").asLong());
        assertEquals(name, record.path("name").asText());
        //列表页的"分类"一列读的就是 categoryName；没有它前端只能显示空单元格
        assertEquals(categoryName, record.path("categoryName").asText());
        assertEquals(((Number) row.get("category_id")).longValue(), record.path("categoryId").asLong());
        assertEquals(((Number) row.get("status")).intValue(), record.path("status").asInt());
        assertSameText(row.get("image"), record.path("image"), "image");
        assertSameText(row.get("description"), record.path("description"), "description");
        //价格是 decimal(10,2)，必须是 JSON number（前端要调 toFixed(2)），且数值一致
        assertTrue(record.path("price").isNumber(), "price 应为 JSON number：" + record.path("price"));
        assertTrue(((BigDecimal) row.get("price"))
                .compareTo(new BigDecimal(record.path("price").asText())) == 0, "price 与库中不一致");

        //走真实 MVC 消息转换器，时间字段必须是接口文档约定的字符串，而不是时间戳数组
        assertTrue(record.path("updateTime").isTextual(), "updateTime 应为字符串：" + record.path("updateTime"));
        assertTrue(record.path("updateTime").asText().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"),
                "updateTime 格式不符合 yyyy-MM-dd HH:mm：" + record.path("updateTime").asText());
    }

    @Test
    void leftJoinKeepsDishesWhoseCategoryIsMissing() throws Exception {
        String name = uniqueName("dq");
        //dish.category_id 上没有外键，这条菜品挂着一个不存在的分类：它必须照常出现在列表里
        long danglingId = insertDish(name, 999999999L, "2000-01-01 00:00:00");
        int allDishes = count("select count(*) from dish");

        //total 等于全表行数：inner join 会把这行滤掉，这里就会少 1
        JsonNode all = page(1, allDishes + 1, Map.of());
        assertEquals(allDishes, all.path("total").asLong(), "left join 丢行：悬空分类的菜品被过滤掉了");
        assertEquals(allDishes, all.path("records").size());

        //按名称筛出这一条。用 contains 而不是"第一条就是它"：测试数据名里带下划线，
        //而 _ 在 like 里是单字符通配符，理论上可能多匹配几行，这里只关心它在不在结果里。
        JsonNode record = null;
        for (JsonNode candidate : page(1, 50, Map.of("name", name)).path("records")) {
            if (candidate.path("id").asLong() == danglingId) {
                record = candidate;
            }
        }
        assertNotNull(record, "悬空分类的菜品没有出现在查询结果里");
        assertTrue(!record.has("categoryName") || record.path("categoryName").isNull(),
                "关联不上分类时 categoryName 应为 null，而不是报错或整行消失");
    }

    @Test
    void nameCategoryAndStatusFiltersEachTakeEffect() throws Exception {
        //先插一条停售菜品。库里的存量菜品全是 status=1，不插这条的话 "status='' 不过滤"
        //与 "status 被当成 1" 的返回结果一模一样，测不出空串到底有没有进 SQL。
        insertDish(uniqueName("dq"), 16L, "2000-01-01 00:00:00", 0);

        int allDishes = count("select count(*) from dish");
        assertTrue(allDishes > 0, "测试前置条件：库里要有菜品数据");
        assertTrue(count("select count(*) from dish where status = 0") > 0, "测试前置条件：要有停售菜品");

        //name 两端空白由 Service 去掉后再模糊匹配：' 汤 ' 应当按含"汤"匹配
        int soup = count("select count(*) from dish where name like '%汤%'");
        assertTrue(soup > 0, "测试前置条件：库里要有名称含「汤」的菜品");
        JsonNode byName = page(Map.of("name", " 汤 "));
        assertEquals(soup, byName.path("total").asLong());
        for (JsonNode record : byName.path("records")) {
            assertTrue(record.path("name").asText().contains("汤"), "name 过滤未生效：" + record.path("name"));
        }

        //categoryId 等值过滤
        long categoryId = 16L;
        int inCategory = count("select count(*) from dish where category_id = ?", categoryId);
        assertTrue(inCategory > 0, "测试前置条件：分类 16 下要有菜品");
        JsonNode byCategory = page(Map.of("categoryId", String.valueOf(categoryId)));
        assertEquals(inCategory, byCategory.path("total").asLong());
        for (JsonNode record : byCategory.path("records")) {
            assertEquals(categoryId, record.path("categoryId").asLong());
        }

        //status：0 是有效筛选值，不能和"没传"混为一谈
        int onSale = count("select count(*) from dish where status = 1");
        int stopped = count("select count(*) from dish where status = 0");
        assertEquals(onSale, page(Map.of("status", "1")).path("total").asLong());
        assertEquals(stopped, page(Map.of("status", "0")).path("total").asLong());

        //前端 dishStatus 初始值是 ''，会以 ?status= 发出。空串必须等同不筛选：
        //绑成 0 会让列表页默认只看停售，绑不进 Integer 则整个页面直接 400。
        assertEquals(allDishes, page(Map.of("status", "")).path("total").asLong(),
                "status= 空串不该过滤掉任何行");
        //再比一次整表行数：total 对不上时 records 也会跟着对不上，这里顺带确认返回的确实是全部菜品
        JsonNode blankStatus = page(1, allDishes + 1, Map.of("status", ""));
        assertEquals(allDishes, blankStatus.path("records").size(), "status= 空串时返回的行数不对");

        //三个条件叠加
        int combined = count("select count(*) from dish where category_id = ? and status = 1 and name like ?",
                categoryId, "%鱼%");
        JsonNode byAll = page(1, 50, Map.of("categoryId", String.valueOf(categoryId), "status", "1", "name", "鱼"));
        assertEquals(combined, byAll.path("total").asLong());
        for (JsonNode record : byAll.path("records")) {
            assertEquals(categoryId, record.path("categoryId").asLong());
            assertEquals(1, record.path("status").asInt());
            assertTrue(record.path("name").asText().contains("鱼"));
        }
    }

    @Test
    void ordersByUpdateTimeDescendingWithIdAsTiebreakAndPagesWithoutGaps() throws Exception {
        //两条 update_time 完全相同的菜品：只按 update_time 排序时它俩的先后是任意的，
        //id desc 兜底保证顺序确定，翻页才不会重复或漏行。
        String first = uniqueName("dq");
        String second = uniqueName("dq");
        long firstId = insertDish(first, 16L, "2099-01-01 00:00:00");
        long secondId = insertDish(second, 16L, "2099-01-01 00:00:00");
        assertTrue(secondId > firstId, "测试前置条件：自增主键应递增");

        int allDishes = count("select count(*) from dish");
        List<Long> expected = jdbc.queryForList("select id from dish order by update_time desc, id desc", Long.class);
        assertEquals(allDishes, expected.size());
        assertEquals(List.of(secondId, firstId), expected.subList(0, 2), "同 update_time 时应按 id 倒序");

        int pages = (allDishes + PAGE_SIZE - 1) / PAGE_SIZE;
        List<Long> actual = new ArrayList<>();
        for (int pageNo = 1; pageNo <= pages; pageNo++) {
            JsonNode data = page(pageNo, PAGE_SIZE, Map.of());
            assertEquals(allDishes, data.path("total").asLong(), "第 " + pageNo + " 页 total 不正确");
            actual.addAll(recordIds(data));
        }
        assertEquals(expected, actual, "翻页拼接的顺序与数据库排序不一致");

        //倒序的第一条确实是 update_time 最新、同值时 id 最大的那条
        JsonNode firstPage = page(1, PAGE_SIZE, Map.of());
        assertEquals(secondId, firstPage.path("records").get(0).path("id").asLong());
        assertEquals("2099-01-01 00:00", firstPage.path("records").get(0).path("updateTime").asText());
    }
}
