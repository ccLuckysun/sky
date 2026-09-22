package com.sky;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.sky.context.BaseContext;
import com.sky.controller.admin.EmployeeController;
import com.sky.entity.Employee;
import com.sky.handler.GlobalExceptionHandler;
import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.mapper.EmployeeMapper;
import com.sky.properties.JwtProperties;
import com.sky.service.impl.EmployeeServiceImpl;
import com.sky.utils.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 编辑页面回显：GET /admin/employee/{id} */
class EmployeeGetByIdTest {
    private static final String SECRET = "employee-get-test-signing-secret";
    private static final long OPERATOR_ID = 7L;
    private static final long TARGET_ID = 42L;

    private EmployeeMapper mapper;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mapper = mock(EmployeeMapper.class);
        EmployeeServiceImpl service = new EmployeeServiceImpl();
        ReflectionTestUtils.setField(service, "employeeMapper", mapper);

        JwtProperties properties = new JwtProperties();
        properties.setAdminSecretKey(SECRET);
        properties.setAdminTtl(7200000);
        properties.setAdminTokenName("token");

        EmployeeController controller = new EmployeeController();
        ReflectionTestUtils.setField(controller, "employeeService", service);
        ReflectionTestUtils.setField(controller, "jwtProperties", properties);

        JwtTokenAdminInterceptor interceptor = new JwtTokenAdminInterceptor();
        ReflectionTestUtils.setField(interceptor, "jwtProperties", properties);
        ReflectionTestUtils.setField(interceptor, "employeeMapper", mapper);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addMappedInterceptors(new String[]{"/admin/**"}, interceptor)
                .build();
    }

    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
    }

    private String token() {
        return JwtUtil.createJWT(SECRET, 7200000, Map.of("empId", OPERATOR_ID));
    }

    private Employee operator() {
        return Employee.builder().id(OPERATOR_ID).username("admin").name("管理员").status(1).build();
    }

    /** 编辑页面需要回显的字段，sex 在库里存的是 "0"/"1"，前端据此渲染男/女。 */
    private Employee stored() {
        return Employee.builder().id(TARGET_ID).username("zhangsan").name("张三")
                .phone("13800000000").sex("0").idNumber("110101199001010011")
                .status(1).createTime(LocalDateTime.now()).updateTime(LocalDateTime.now()).build();
    }

    private MvcResult fetch(String path) throws Exception {
        return mvc.perform(get(path).header("token", token())).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return this.json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void returnsFieldsTheEditFormNeeds() throws Exception {
        when(mapper.getById(OPERATOR_ID)).thenReturn(operator());
        when(mapper.getDetailById(TARGET_ID)).thenReturn(stored());

        JsonNode response = json(fetch("/admin/employee/" + TARGET_ID));
        assertEquals(1, response.path("code").asInt());
        JsonNode data = response.path("data");
        assertEquals(TARGET_ID, data.path("id").asLong());
        assertEquals("zhangsan", data.path("username").asText());
        assertEquals("张三", data.path("name").asText());
        assertEquals("13800000000", data.path("phone").asText());
        assertEquals("0", data.path("sex").asText(), "sex 必须原样返回 0/1，前端据此显示男/女");
        assertEquals("110101199001010011", data.path("idNumber").asText());
        assertTrue(!data.has("password") || data.path("password").isNull(), "回显不应带出口令哈希");
    }

    @Test
    void unknownIdIsReportedAsAccountNotFound() throws Exception {
        when(mapper.getById(OPERATOR_ID)).thenReturn(operator());

        JsonNode response = json(fetch("/admin/employee/" + TARGET_ID));
        assertEquals(0, response.path("code").asInt());
        assertEquals("账号不存在", response.path("msg").asText());
        assertTrue(response.path("data").isNull());
    }

    @Test
    void requiresAuthentication() throws Exception {
        MvcResult anonymous = mvc.perform(get("/admin/employee/" + TARGET_ID)).andReturn();
        assertEquals(401, anonymous.getResponse().getStatus());
        verify(mapper, never()).getDetailById(any());
    }

    /**
     * /{id} 是路径变量，必须不能抢走 /page、/logout 这类固定路径。
     * 一旦抢走，page 会用 id="page" 进入 getById 并因类型转换失败返回 400。
     */
    @Test
    void literalPathsStillWinOverTheIdPattern() throws Exception {
        when(mapper.getById(OPERATOR_ID)).thenReturn(operator());
        Page<Employee> empty = new Page<>();
        when(mapper.pageQuery(any())).thenReturn(empty);

        MvcResult page = fetch("/admin/employee/page?page=1&pageSize=10");
        assertEquals(200, page.getResponse().getStatus());
        assertEquals(1, json(page).path("code").asInt());
        assertNotNull(json(page).path("data").path("total"));
        verify(mapper, never()).getDetailById(any());

        MvcResult logout = mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/admin/employee/logout").header("token", token())).andReturn();
        assertEquals(200, logout.getResponse().getStatus());
        assertEquals(1, json(logout).path("code").asInt());
    }
}
