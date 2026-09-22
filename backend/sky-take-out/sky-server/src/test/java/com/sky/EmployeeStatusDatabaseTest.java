package com.sky;

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

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 显式启用本机 MySQL 联调，验证 EmployeeMapper.xml 里动态 &lt;set&gt; 生成的 SQL 真实生效：
 * 只更新 status/update_time/update_user，其余列保持原值。整个测试事务结束后自动回滚。
 */
@EnabledIfEnvironmentVariable(named = "SKY_DB_TESTS", matches = "true")
@SpringBootTest(properties = "logging.level.com.sky.mapper=INFO")
@AutoConfigureMockMvc
@Transactional
class EmployeeStatusDatabaseTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private EmployeeMapper mapper;
    @Autowired private JwtProperties jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder encoder;

    private Employee admin() {
        Employee admin = mapper.getByUsername("admin");
        assertNotNull(admin, "数据库需存在启用的 admin 员工");
        assertEquals(1, admin.getStatus());
        return admin;
    }

    private String adminToken() {
        return JwtUtil.createJWT(jwt.getAdminSecretKey(), jwt.getAdminTtl(), Map.of("empId", admin().getId()));
    }

    /** 插入一名启用状态的员工，创建/修改时间留在过去，以便观察本次操作是否刷新了 update_time。 */
    private long insertEmployee() {
        String username = "st_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Timestamp past = Timestamp.valueOf("2000-01-01 00:00:00");
        String hash = encoder.encode("123456");
        jdbc.update("insert into employee (name, username, password, phone, sex, id_number, status,"
                        + " create_time, update_time, create_user, update_user)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "状态测试员工", username, hash, "13800000000", "1", "110101199001010011", 1, past, past, 1L, 1L);
        return jdbc.queryForObject("select id from employee where username = ?", Long.class, username);
    }

    private void callStatus(int status, long id) throws Exception {
        mvc.perform(post("/admin/employee/status/" + status).header("token", adminToken())
                        .contentType("application/json").param("id", String.valueOf(id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void updateTouchesOnlyStatusAndAuditColumns() throws Exception {
        long id = insertEmployee();
        Map<String, Object> before = jdbc.queryForMap("select * from employee where id = ?", id);
        // 按列类型取值：MySQL 驱动会把 datetime 映射成 LocalDateTime，显式指定避免依赖驱动实现。
        LocalDateTime beforeTime = jdbc.queryForObject(
                "select update_time from employee where id = ?", LocalDateTime.class, id);
        long adminId = admin().getId();

        callStatus(0, id);

        Map<String, Object> after = jdbc.queryForMap("select * from employee where id = ?", id);
        assertEquals(0, ((Number) after.get("status")).intValue(), "禁用后 status 应为 0");
        assertEquals(adminId, ((Number) after.get("update_user")).longValue(), "update_user 应为登录人");
        LocalDateTime afterTime = jdbc.queryForObject(
                "select update_time from employee where id = ?", LocalDateTime.class, id);
        assertTrue(afterTime.isAfter(beforeTime), "update_time 应被刷新");
        // 动态 <set> 只写非 null 字段：未赋值的列必须原样保留，尤其是口令哈希。
        for (String column : new String[]{"name", "username", "password", "phone", "sex",
                "id_number", "create_time", "create_user"}) {
            assertEquals(before.get(column), after.get(column), column + " 不应被本次更新改动");
        }

        // 再启用回来，两个方向都要落库。
        callStatus(1, id);
        assertEquals(1, jdbc.queryForObject("select status from employee where id = ?", Integer.class, id));
    }

    @Test
    void disabledAccountCannotLoginOrUseItsToken() throws Exception {
        long id = insertEmployee();
        String username = jdbc.queryForObject("select username from employee where id = ?", String.class, id);

        callStatus(0, id);

        mvc.perform(post("/admin/employee/login").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("username", username, "password", "123456"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("账号被锁定"));

        //被禁用的账号即使持有未过期的令牌也不能再访问后台接口。
        String employeeToken = JwtUtil.createJWT(jwt.getAdminSecretKey(), jwt.getAdminTtl(), Map.of("empId", id));
        mvc.perform(post("/admin/employee/status/0").header("token", employeeToken)
                        .contentType("application/json").param("id", String.valueOf(id)))
                .andExpect(status().isUnauthorized());

        //启用后凭原密码即可重新登录。
        callStatus(1, id);
        mvc.perform(post("/admin/employee/login").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("username", username, "password", "123456"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
    }
}
