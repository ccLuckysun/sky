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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 显式启用本机 MySQL 联调；整个测试事务结束后自动回滚测试数据。 */
@EnabledIfEnvironmentVariable(named = "SKY_DB_TESTS", matches = "true")
@SpringBootTest(properties = "logging.level.com.sky.mapper=INFO")
@AutoConfigureMockMvc
@Transactional
class EmployeeDatabaseTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private EmployeeMapper mapper;
    @Autowired private JwtProperties jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder encoder;

    @Test
    void createEmployeePersistsDefaultsSupportsLoginAndRejectsDuplicate() throws Exception {
        Employee operator = mapper.getByUsername("admin");
        assertNotNull(operator, "数据库需存在启用的 admin 员工");
        assertEquals(1, operator.getStatus());
        String token = JwtUtil.createJWT(jwt.getAdminSecretKey(), jwt.getAdminTtl(), Map.of("empId", operator.getId()));
        String username = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Map<String, Object> body = new HashMap<>(Map.of("username", username, "name", "接口测试员工",
                "phone", "13800000000", "sex", "1", "idNumber", "110101199001010011", "id", Long.MAX_VALUE));
        String content = json.writeValueAsString(body);

        mvc.perform(post("/admin/employee").header("token", token)
                .contentType("application/json").content(content))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));

        Map<String, Object> row = jdbc.queryForMap("select * from employee where username = ?", username);
        assertNotEquals(Long.MAX_VALUE, ((Number) row.get("id")).longValue());
        assertEquals(body.get("name"), row.get("name"));
        assertEquals(body.get("phone"), row.get("phone"));
        assertEquals(body.get("sex"), row.get("sex"));
        assertEquals(body.get("idNumber"), row.get("id_number"));
        assertNotEquals("123456", row.get("password"));
        assertTrue(encoder.matches("123456", (String) row.get("password")));
        assertEquals(1, ((Number) row.get("status")).intValue());
        assertEquals(operator.getId().longValue(), ((Number) row.get("create_user")).longValue());
        assertEquals(operator.getId().longValue(), ((Number) row.get("update_user")).longValue());
        assertNotNull(row.get("create_time"));
        assertEquals(row.get("create_time"), row.get("update_time"));

        String response = mvc.perform(post("/admin/employee/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username", username, "password", "123456"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1))
                .andReturn().getResponse().getContentAsString();
        JsonNode login = json.readTree(response);
        assertEquals(((Number) row.get("id")).longValue(), login.path("data").path("id").asLong());
        String employeeToken = login.path("data").path("token").asText();
        mvc.perform(post("/admin/employee/logout").header("token", employeeToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));

        mvc.perform(post("/admin/employee").header("token", token)
                .contentType("application/json").content(content))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("用户名已存在"));
        assertEquals(1, jdbc.queryForObject("select count(*) from employee where username = ?", Integer.class, username));
    }
}
