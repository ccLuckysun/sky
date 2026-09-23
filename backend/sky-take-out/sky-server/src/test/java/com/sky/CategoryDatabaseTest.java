package com.sky;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.context.BaseContext;
import com.sky.entity.Category;
import com.sky.entity.Employee;
import com.sky.mapper.CategoryMapper;
import com.sky.mapper.EmployeeMapper;
import com.sky.properties.JwtProperties;
import com.sky.utils.JwtUtil;
import org.junit.jupiter.api.AfterEach;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 显式启用本机 MySQL 联调，验证公共字段自动填充在真实 Spring 上下文里确实织入到了 mapper 代理上：
 * 分类的新增、修改、启用禁用三条链路都不再由业务层写审计字段，落库的值必须来自 AutoFillAspect。
 * 这是 mock 测试证明不了的一环——mock 掉 mapper 就绕过了整个代理。整个测试事务结束后自动回滚。
 */
@EnabledIfEnvironmentVariable(named = "SKY_DB_TESTS", matches = "true")
@SpringBootTest(properties = "logging.level.com.sky.mapper=INFO")
@AutoConfigureMockMvc
@Transactional
class CategoryDatabaseTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private EmployeeMapper employeeMapper;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private JwtProperties jwt;
    @Autowired private JdbcTemplate jdbc;

    private static final Timestamp PAST = Timestamp.valueOf("2000-01-01 00:00:00");

    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
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

    private String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 通过接口新建一个分类，返回其 id。 */
    private long createCategory(long adminId, String name) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "type", 1, "sort", 1));
        mvc.perform(post("/admin/category").header("token", adminToken())
                        .contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
        return jdbc.queryForObject("select id from category where name = ?", Long.class, name);
    }

    private LocalDateTime updateTime(long id) {
        return jdbc.queryForObject("select update_time from category where id = ?", LocalDateTime.class, id);
    }

    @Test
    void insertStampsAllFourPublicFields() throws Exception {
        long adminId = adminId();
        String name = uniqueName("cat");

        long id = createCategory(adminId, name);

        Map<String, Object> row = jdbc.queryForMap("select * from category where id = ?", id);
        //CategoryServiceImpl.save 只负责置为禁用，四个审计字段全部来自切面。
        assertEquals(0, ((Number) row.get("status")).intValue(), "新建分类默认为禁用");
        assertEquals(adminId, ((Number) row.get("create_user")).longValue(), "create_user 应为登录人");
        assertEquals(adminId, ((Number) row.get("update_user")).longValue(), "update_user 应为登录人");
        assertNotNull(row.get("create_time"));
        assertEquals(row.get("create_time"), row.get("update_time"), "新增时创建与修改时间是同一时刻");
    }

    @Test
    void updateOverwritesTheOperatorAndKeepsTheCreationFields() throws Exception {
        long adminId = adminId();
        String name = uniqueName("cat");
        long id = createCategory(adminId, name);
        Map<String, Object> before = jdbc.queryForMap("select * from category where id = ?", id);
        //先把修改人改成“别人”，才看得出这次修改是否真的把 update_user 改写成登录人。
        jdbc.update("update category set update_user = 999, update_time = ? where id = ?", PAST, id);

        String newName = uniqueName("cat");
        mvc.perform(put("/admin/category").header("token", adminToken())
                        .contentType("application/json").content(json.writeValueAsString(
                                new HashMap<>(Map.of("id", id, "name", newName, "sort", 5, "type", 2)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));

        Map<String, Object> after = jdbc.queryForMap("select * from category where id = ?", id);
        assertEquals(newName, after.get("name"));
        assertEquals(5, ((Number) after.get("sort")).intValue());
        assertEquals(2, ((Number) after.get("type")).intValue());
        assertEquals(adminId, ((Number) after.get("update_user")).longValue(), "update_user 应改写为登录人");
        assertTrue(updateTime(id).isAfter(LocalDateTime.of(2000, 1, 1, 0, 0)), "update_time 应被刷新");
        assertEquals(before.get("create_time"), after.get("create_time"), "修改不得改动创建时间");
        assertEquals(before.get("create_user"), after.get("create_user"), "修改不得改动创建人");
    }

    @Test
    void startOrStopStampsTheOperator() throws Exception {
        long adminId = adminId();
        long id = createCategory(adminId, uniqueName("cat"));
        jdbc.update("update category set update_user = 999, update_time = ? where id = ?", PAST, id);

        mvc.perform(post("/admin/category/status/1").header("token", adminToken()).param("id", String.valueOf(id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));

        Map<String, Object> after = jdbc.queryForMap("select * from category where id = ?", id);
        assertEquals(1, ((Number) after.get("status")).intValue(), "启用后 status 应为 1");
        assertEquals(adminId, ((Number) after.get("update_user")).longValue());
        assertTrue(updateTime(id).isAfter(LocalDateTime.of(2000, 1, 1, 0, 0)), "update_time 应被刷新");
    }

    @Test
    void mapperUpdateWithoutLoginContextLeavesTheOperatorAlone() throws Exception {
        long id = createCategory(adminId(), uniqueName("cat"));
        jdbc.update("update category set update_user = 999, update_time = ? where id = ?", PAST, id);
        //直接调 mapper，等价于没有登录上下文：切面不补操作人，动态 <set> 于是跳过该列。
        BaseContext.removeCurrentId();
        assertNull(BaseContext.getCurrentId());

        categoryMapper.update(Category.builder().id(id).sort(9).build());

        Map<String, Object> after = jdbc.queryForMap("select * from category where id = ?", id);
        assertEquals(9, ((Number) after.get("sort")).intValue(), "要改的列照常写入");
        assertEquals(999L, ((Number) after.get("update_user")).longValue(), "缺上下文时不得把修改人清空");
        assertTrue(updateTime(id).isAfter(LocalDateTime.of(2000, 1, 1, 0, 0)), "时间字段与登录上下文无关，照常填充");
    }
}
