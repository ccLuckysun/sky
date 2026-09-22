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

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 显式启用本机 MySQL 联调，验证 pageQuery 的真实 SQL（列清单、like 条件、排序、PageHelper 的 limit 与 count）。
 * 整个测试事务结束后自动回滚测试数据。
 */
@EnabledIfEnvironmentVariable(named = "SKY_DB_TESTS", matches = "true")
@SpringBootTest(properties = "logging.level.com.sky.mapper=INFO")
@AutoConfigureMockMvc
@Transactional
class EmployeePageQueryDatabaseTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private EmployeeMapper mapper;
    @Autowired private JwtProperties jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder encoder;

    private static final int INSERTED = 5;
    private static final int PAGE_SIZE = 2;

    private String token() {
        Employee operator = mapper.getByUsername("admin");
        assertNotNull(operator, "数据库需存在启用的 admin 员工");
        assertEquals(1, operator.getStatus());
        return JwtUtil.createJWT(jwt.getAdminSecretKey(), jwt.getAdminTtl(), Map.of("empId", operator.getId()));
    }

    /**
     * 插入 5 条同名前缀的员工，其中第 2、3 条与第 4、5 条的 update_time 相同，
     * 用于验证排序稳定（先按 update_time 倒序，再按 id 倒序兜底）。
     * @return 按 id 升序排列的测试数据主键，共 {@value INSERTED} 个
     */
    private List<Long> insertEmployees(String prefix) {
        Timestamp base = Timestamp.valueOf("2000-01-01 00:00:00");
        String hash = encoder.encode("123456");
        assertTrue(hash.length() <= 64, "BCrypt 哈希必须能放进 password 列");
        for (int i = 1; i <= INSERTED; i++) {
            // i=1 → 第 0 分钟，i=2/3 → 第 1 分钟，i=4/5 → 第 2 分钟
            Timestamp updateTime = new Timestamp(base.getTime() + (i / 2) * 60_000L);
            jdbc.update("insert into employee (name, username, password, phone, sex, id_number, status,"
                            + " create_time, update_time, create_user, update_user)"
                            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    prefix + "员工" + i,
                    prefix + "_" + i + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                    hash, "13800000000", "1", "110101199001010011", 1, base, updateTime, 1L, 1L);
        }
        return jdbc.queryForList("select id from employee where name like ? order by id", Long.class, prefix + "%");
    }

    /**
     * 用 param() 传参，值已是解码后的明文；直接把 %20 写进 URL 模板不会被 MockMvc 解码，
     * 会把 "%20" 当成姓名字符发给服务端。
     * @param name 为 null 时不带 name 参数，用于验证姓名可选
     */
    private JsonNode page(String token, int page, int pageSize, String name) throws Exception {
        var request = get("/admin/employee/page")
                .param("page", String.valueOf(page)).param("pageSize", String.valueOf(pageSize));
        if (name != null) {
            request.param("name", name);
        }
        String body = mvc.perform(request.header("token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body).path("data");
    }

    private List<Long> recordIds(JsonNode data) {
        List<Long> ids = new ArrayList<>();
        data.path("records").forEach(record -> ids.add(record.path("id").asLong()));
        return ids;
    }

    @Test
    void limitCountFilterAndOrderingMatchTheDatabase() throws Exception {
        String prefix = "pq" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        List<Long> ids = insertEmployees(prefix);
        assertEquals(INSERTED, ids.size(), "测试数据未按预期写入");
        String token = token();

        // 按 name 模糊筛选后共有 INSERTED 条，排序为 update_time 倒序、同值再按 id 倒序。
        List<Long> expected = new ArrayList<>(ids);
        java.util.Collections.reverse(expected);

        assertEquals(INSERTED, jdbc.queryForObject("select count(*) from employee where name like ?",
                Integer.class, prefix + "%"));
        for (int page = 1; page <= 3; page++) {
            JsonNode data = page(token, page, PAGE_SIZE, prefix);
            // total 来自 PageHelper 的 count 查询，与当前页码无关。
            assertEquals(INSERTED, data.path("total").asLong(), "第 " + page + " 页 total 不正确");
            List<Long> expectedPage = expected.subList((page - 1) * PAGE_SIZE, Math.min(page * PAGE_SIZE, INSERTED));
            assertEquals(expectedPage, recordIds(data), "第 " + page + " 页记录或顺序不正确");
        }

        // 越界页返回空 records，但 total 仍然是筛选后的总数。
        JsonNode overflow = page(token, 99, PAGE_SIZE, prefix);
        assertEquals(INSERTED, overflow.path("total").asLong());
        assertEquals(0, overflow.path("records").size());
    }

    @Test
    void selectedColumnsExcludePasswordHash() throws Exception {
        String prefix = "pq" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        insertEmployees(prefix);
        String storedHash = jdbc.queryForObject("select password from employee where name like ? limit 1",
                String.class, prefix + "%");
        assertTrue(storedHash.startsWith("$2"), "测试前置条件：数据库里存的是 BCrypt 哈希");

        String body = mvc.perform(get("/admin/employee/page?page=1&pageSize=" + INSERTED + "&name=" + prefix)
                        .header("token", token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertFalse(body.contains(storedHash), "响应体中出现了 BCrypt 哈希");
        assertFalse(body.contains("123456"), "响应体中出现了明文口令");
        JsonNode data = json.readTree(body).path("data");
        assertEquals(INSERTED, data.path("records").size());
        for (JsonNode record : data.path("records")) {
            assertTrue(!record.has("password") || record.path("password").isNull(),
                    "接口返回了口令哈希：" + record);
            assertEquals(1, record.path("status").asInt());
            assertEquals("13800000000", record.path("phone").asText());
            assertEquals("110101199001010011", record.path("idNumber").asText());
            // 走真实 MVC 消息转换器，时间字段必须是接口文档约定的字符串，而不是时间戳数组。
            for (String timeField : new String[]{"createTime", "updateTime"}) {
                assertTrue(record.path(timeField).isTextual(),
                        timeField + " 应为字符串，实际为 " + record.path(timeField));
                assertTrue(record.path(timeField).asText()
                                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}(:\\d{2})?"),
                        timeField + " 格式不符合约定：" + record.path(timeField).asText());
            }
        }
    }

    @Test
    void nameFilterIsOptionalAndCombinesWithPaging() throws Exception {
        String prefix = "pq" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        insertEmployees(prefix);
        String token = token();

        int allEmployees = jdbc.queryForObject("select count(*) from employee", Integer.class);
        assertTrue(allEmployees >= INSERTED, "全表员工数不应少于本次插入的测试数据");

        // name 为空时不过滤，<where> 不应渲染出多余条件。
        JsonNode unfiltered = page(token, 1, 1, null);
        assertEquals(allEmployees, unfiltered.path("total").asLong());
        assertEquals(1, unfiltered.path("records").size());

        // 姓名按包含匹配，且两端空白已被 Service 去掉。
        JsonNode matched = page(token, 1, INSERTED, " " + prefix + " ");
        assertEquals(INSERTED, matched.path("total").asLong());
        assertEquals(INSERTED, matched.path("records").size());

        // 姓名中间部分也能命中，确认是 like '%name%' 而不是等值或前缀匹配。
        JsonNode partial = page(token, 1, INSERTED, prefix + "员工3");
        assertEquals(1, partial.path("total").asLong());
        assertEquals(prefix + "员工3", partial.path("records").get(0).path("name").asText());

        JsonNode noMatch = page(token, 1, PAGE_SIZE, prefix + "不存在");
        assertEquals(0, noMatch.path("total").asLong());
        assertEquals(0, noMatch.path("records").size());
    }
}
