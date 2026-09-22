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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 显式启用本机 MySQL 联调，验证“编辑员工”这条链路：
 * 回显接口从库中读到的字段不带口令哈希，保存接口把五个字段写回真实列、status 与 password 不受影响，
 * 且 username 唯一索引照常生效。整个测试事务结束后自动回滚。
 */
@EnabledIfEnvironmentVariable(named = "SKY_DB_TESTS", matches = "true")
@SpringBootTest(properties = "logging.level.com.sky.mapper=INFO")
@AutoConfigureMockMvc
@Transactional
class EmployeeUpdateDatabaseTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private EmployeeMapper mapper;
    @Autowired private JwtProperties jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder encoder;

    private static final String PASSWORD = "123456";

    private Long adminId() {
        Employee admin = mapper.getByUsername("admin");
        assertNotNull(admin, "数据库需存在 admin 员工");
        assertEquals(1, admin.getStatus());
        return admin.getId();
    }

    private String adminToken() {
        return JwtUtil.createJWT(jwt.getAdminSecretKey(), jwt.getAdminTtl(), Map.of("empId", adminId()));
    }

    /** 插入一名启用状态的员工，返回其 id。 */
    private long insertEmployee(String username, int status) {
        String past = "2000-01-01 00:00:00";
        jdbc.update("insert into employee (name, username, password, phone, sex, id_number, status,"
                        + " create_time, update_time, create_user, update_user)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "原姓名", username, encoder.encode(PASSWORD), "13800000000", "1",
                "110101199001010011", status, past, past, 1L, 1L);
        return jdbc.queryForObject("select id from employee where username = ?", Long.class, username);
    }

    private String uniqueUsername(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private Map<String, Object> body(long id, String username, String name, String phone,
                                     String sex, String idNumber) {
        return new HashMap<>(Map.of("id", id, "username", username, "name", name,
                "phone", phone, "sex", sex, "idNumber", idNumber));
    }

    private void edit(Map<String, Object> body) throws Exception {
        mvc.perform(put("/admin/employee").header("token", adminToken())
                        .contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    @Test
    void editFormPrefillsFromTheDatabaseWithoutThePasswordHash() throws Exception {
        String username = uniqueUsername("up");
        long id = insertEmployee(username, 1);
        String storedHash = jdbc.queryForObject(
                "select password from employee where id = ?", String.class, id);
        assertTrue(storedHash.startsWith("$2"), "测试前置条件：数据库里存的是 BCrypt 哈希");

        MvcResult result = mvc.perform(get("/admin/employee/" + id).header("token", adminToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode data = json.readTree(body).path("data");
        assertEquals(id, data.path("id").asLong());
        assertEquals(username, data.path("username").asText());
        assertEquals("原姓名", data.path("name").asText());
        assertEquals("13800000000", data.path("phone").asText());
        // 前端按 sex 是否为 "0" 渲染女/男，回显必须是库里存的 0/1 而不是文字。
        assertEquals("1", data.path("sex").asText());
        assertEquals("110101199001010011", data.path("idNumber").asText());
        assertEquals(1, data.path("status").asInt());
        assertFalse(body.contains(storedHash), "回显响应里出现了 BCrypt 哈希");
        assertTrue(!data.has("password") || data.path("password").isNull(), "回显不应带出口令哈希");
    }

    @Test
    void prefillAndSaveRoundTripWithoutChangingAnythingElse() throws Exception {
        String username = uniqueUsername("up");
        long id = insertEmployee(username, 1);
        Map<String, Object> before = jdbc.queryForMap("select * from employee where id = ?", id);

        // 按前端流程走一遍：先回显，再原样提交，最后回显应保持不变。
        JsonNode echo = json.readTree(mvc.perform(get("/admin/employee/" + id).header("token", adminToken()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        edit(body(id, echo.path("username").asText(), echo.path("name").asText(),
                echo.path("phone").asText(), echo.path("sex").asText(),
                echo.path("idNumber").asText()));

        Map<String, Object> after = jdbc.queryForMap("select * from employee where id = ?", id);
        for (String column : new String[]{"username", "name", "phone", "sex", "id_number", "status", "password"}) {
            assertEquals(before.get(column), after.get(column), column + " 原样提交后不应变化");
        }
    }

    @Test
    void rewritesSubmittedColumnsAndLeavesStatusAndPasswordUntouched() throws Exception {        String username = uniqueUsername("up");
        long id = insertEmployee(username, 0);
        Map<String, Object> before = jdbc.queryForMap("select * from employee where id = ?", id);
        LocalDateTime beforeTime = jdbc.queryForObject(
                "select update_time from employee where id = ?", LocalDateTime.class, id);

        String newUsername = uniqueUsername("up");
        edit(body(id, newUsername, "新姓名", "13911112222", "0", "110101199001010022"));

        Map<String, Object> after = jdbc.queryForMap("select * from employee where id = ?", id);
        assertEquals(newUsername, after.get("username"));
        assertEquals("新姓名", after.get("name"));
        assertEquals("13911112222", after.get("phone"));
        assertEquals("0", after.get("sex"));
        assertEquals("110101199001010022", after.get("id_number"));
        // 接口文档的请求体里没有 status 和 password，这两列必须原样保留。
        assertEquals(((Number) before.get("status")).intValue(), ((Number) after.get("status")).intValue(),
                "编辑接口不得改动 status");
        assertEquals(before.get("password"), after.get("password"), "编辑接口不得改动口令哈希");
        // 审计列照常更新：操作人是登录的管理员，不是被编辑的员工。
        assertEquals(adminId().longValue(), ((Number) after.get("update_user")).longValue());
        LocalDateTime afterTime = jdbc.queryForObject(
                "select update_time from employee where id = ?", LocalDateTime.class, id);
        assertTrue(afterTime.isAfter(beforeTime), "update_time 应被刷新");
        assertEquals(before.get("create_time"), after.get("create_time"), "create_time 不应改动");
        assertEquals(before.get("create_user"), after.get("create_user"), "create_user 不应改动");
    }

    @Test
    void keepingOwnUsernameIsNotTreatedAsDuplicate() throws Exception {
        String username = uniqueUsername("up");
        long id = insertEmployee(username, 1);

        // 提交与原值相同的用户名，唯一索引不应把自己判成重复。
        edit(body(id, username, "只改姓名", "13800000000", "1", "110101199001010011"));

        Map<String, Object> after = jdbc.queryForMap("select * from employee where id = ?", id);
        assertEquals(username, after.get("username"));
        assertEquals("只改姓名", after.get("name"));
    }

    @Test
    void reusingAnotherEmployeesUsernameIsRejectedAndLeavesBothRowsIntact() throws Exception {
        String first = uniqueUsername("up");
        String second = uniqueUsername("up");
        long firstId = insertEmployee(first, 1);
        long secondId = insertEmployee(second, 1);

        mvc.perform(put("/admin/employee").header("token", adminToken())
                        .contentType("application/json")
                        .content(json.writeValueAsString(
                                body(secondId, first, "改名失败", "13900000000", "1", "110101199001010022"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("用户名已存在"));

        // 撞名失败后两行都必须保持原样，不能被写坏一半。
        assertEquals(first, jdbc.queryForObject(
                "select username from employee where id = ?", String.class, firstId));
        Map<String, Object> secondRow = jdbc.queryForMap("select * from employee where id = ?", secondId);
        assertEquals(second, secondRow.get("username"), "失败的编辑不应改动目标行");
        assertEquals("原姓名", secondRow.get("name"), "失败的编辑不应改动其他列");
    }
}
