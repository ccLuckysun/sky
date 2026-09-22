package com.sky;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.context.BaseContext;
import com.sky.controller.admin.EmployeeController;
import com.sky.dto.EmployeePageQueryDTO;
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
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EmployeePageQueryTest {
    private static final String SECRET = "employee-page-test-signing-secret";
    private static final long EMP_ID = 7L;
    private static final String RAW_PASSWORD = "123456";

    private EmployeeMapper mapper;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    /** 被查询出来的员工，密码是真实 BCrypt 哈希，用于验证它不会出现在响应里。 */
    private String storedHash;

    @BeforeEach
    void setUp() {
        mapper = mock(EmployeeMapper.class);
        when(mapper.getById(EMP_ID)).thenReturn(Employee.builder().id(EMP_ID).status(1).build());
        EmployeeServiceImpl service = new EmployeeServiceImpl();
        ReflectionTestUtils.setField(service, "employeeMapper", mapper);
        ReflectionTestUtils.setField(service, "passwordEncoder", encoder);
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
        PageHelper.clearPage();
    }

    private Employee employee(long id, String name, int status) {
        storedHash = encoder.encode(RAW_PASSWORD);
        return Employee.builder().id(id).username("employee-" + id).name(name)
                .password(storedHash).phone("1380000000" + id).sex("1")
                .idNumber("110101199001010011").status(status)
                .createTime(LocalDateTime.of(2026, 9, 22, 10, 30, 0))
                .updateTime(LocalDateTime.of(2026, 9, 22, 11, 0, 0))
                .createUser(1L).updateUser(1L).build();
    }

    private ResultActions pageQuery(Map<String, String> params) throws Exception {
        var request = get("/admin/employee/page")
                .header("token", JwtUtil.createJWT(SECRET, 60000, Map.of("empId", EMP_ID)));
        params.forEach(request::param);
        return mvc.perform(request);
    }

    private ResultActions pageQuery() throws Exception {
        return pageQuery(Map.of("page", "1", "pageSize", "10"));
    }

    /** 响应头未带 charset，MockMvc 默认按 ISO-8859-1 解码，中文断言必须显式按 UTF-8 读取。 */
    private JsonNode responseBody(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 按 PageHelper 在真实查询中的行为构造返回值：整体是 Page，同时携带 count 查询得到的 total。 */
    private Page<Employee> page(long total, Employee... records) {
        Page<Employee> page = new Page<>(1, 10);
        page.setTotal(total);
        page.addAll(Arrays.asList(records));
        return page;
    }

    @Test
    void returnsTotalAndCurrentPageRecords() throws Exception {
        when(mapper.pageQuery(any(EmployeePageQueryDTO.class)))
                .thenReturn(page(23L, employee(1L, "张三", 1), employee(2L, "李四", 0)));

        JsonNode response = responseBody(pageQuery(Map.of("page", "1", "pageSize", "2"))
                .andExpect(status().isOk()));

        assertEquals(1, response.path("code").asInt());
        JsonNode data = response.path("data");
        assertEquals(23L, data.path("total").asLong());
        assertEquals(2, data.path("records").size());
        assertEquals("张三", data.path("records").get(0).path("name").asText());
        assertEquals("李四", data.path("records").get(1).path("name").asText());
    }

    @Test
    void recordsCarryDocumentedFieldsButNeverThePasswordHash() throws Exception {
        when(mapper.pageQuery(any(EmployeePageQueryDTO.class))).thenReturn(page(1L, employee(1L, "张三", 1)));
        String hash = storedHash;
        assertTrue(hash.startsWith("$2"), "测试前置条件：数据库里存的是 BCrypt 哈希");

        String body = pageQuery().andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode record = json.readTree(body).path("data").path("records").get(0);

        for (String field : new String[]{"id", "username", "name", "phone", "sex", "idNumber",
                "status", "createTime", "updateTime", "createUser", "updateUser"}) {
            assertTrue(record.has(field), "响应缺少字段：" + field);
        }
        assertTrue(!record.has("password") || record.path("password").isNull(),
                "接口返回了口令哈希：" + record.path("password"));
        assertFalse(body.contains(hash), "响应体中出现了 BCrypt 哈希");
        assertFalse(body.contains(RAW_PASSWORD), "响应体中出现了明文口令");
    }

    @Test
    void passesPagingAndNameParametersToMapperAndClearsPageHelperContext() throws Exception {
        when(mapper.pageQuery(any(EmployeePageQueryDTO.class))).thenReturn(page(0L));

        pageQuery(Map.of("page", "3", "pageSize", "20", "name", "张三"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));

        ArgumentCaptor<EmployeePageQueryDTO> capture = ArgumentCaptor.forClass(EmployeePageQueryDTO.class);
        verify(mapper).pageQuery(capture.capture());
        assertEquals(3, capture.getValue().getPage());
        assertEquals(20, capture.getValue().getPageSize());
        assertEquals("张三", capture.getValue().getName());
        // 分页参数存放在 ThreadLocal，未清理会污染后续复用该线程的查询。
        assertNull(PageHelper.getLocalPage());
    }

    @Test
    void absentOrBlankNameMeansNoFilterAndNameIsTrimmed() throws Exception {
        when(mapper.pageQuery(any(EmployeePageQueryDTO.class))).thenReturn(page(0L));

        pageQuery().andExpect(jsonPath("$.code").value(1));
        pageQuery(Map.of("page", "1", "pageSize", "10", "name", "")).andExpect(jsonPath("$.code").value(1));
        pageQuery(Map.of("page", "1", "pageSize", "10", "name", " \t "))
                .andExpect(jsonPath("$.code").value(1));
        pageQuery(Map.of("page", "1", "pageSize", "10", "name", " 张三 "))
                .andExpect(jsonPath("$.code").value(1));

        ArgumentCaptor<EmployeePageQueryDTO> capture = ArgumentCaptor.forClass(EmployeePageQueryDTO.class);
        verify(mapper, times(4)).pageQuery(capture.capture());
        assertNull(capture.getAllValues().get(0).getName());
        assertNull(capture.getAllValues().get(1).getName());
        assertNull(capture.getAllValues().get(2).getName());
        assertEquals("张三", capture.getAllValues().get(3).getName());
    }

    @Test
    void emptyResultStillReturnsTotalAndEmptyRecords() throws Exception {
        when(mapper.pageQuery(any(EmployeePageQueryDTO.class))).thenReturn(page(0L));

        JsonNode data = responseBody(pageQuery(Map.of("page", "1", "pageSize", "10", "name", "不存在的人")))
                .path("data");

        assertEquals(0L, data.path("total").asLong());
        assertTrue(data.path("records").isArray());
        assertEquals(0, data.path("records").size());
    }

    @Test
    void rejectsMissingZeroOrNegativePagingParametersBeforeQuerying() throws Exception {
        Map<String, String> missingPage = Map.of("pageSize", "10");
        Map<String, String> missingPageSize = Map.of("page", "1");
        Map<String, String>[] invalid = new Map[]{
                Map.of(), missingPage, missingPageSize,
                Map.of("page", "0", "pageSize", "10"),
                Map.of("page", "1", "pageSize", "0"),
                Map.of("page", "-1", "pageSize", "10"),
                Map.of("page", "1", "pageSize", "-5")};

        for (Map<String, String> params : invalid) {
            JsonNode response = responseBody(pageQuery(params).andExpect(status().isOk()));
            assertEquals(0, response.path("code").asInt(), params + " 应被拒绝");
            assertEquals("页码和每页记录数必须为正整数", response.path("msg").asText());
            assertTrue(response.path("data").isNull());
        }
        verify(mapper, never()).pageQuery(any());
    }

    @Test
    void unauthenticatedOrInvalidTokenDoesNotQueryDatabase() throws Exception {
        mvc.perform(get("/admin/employee/page").param("page", "1").param("pageSize", "10"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/admin/employee/page").param("page", "1").param("pageSize", "10")
                        .header("token", "not-a-jwt"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(0));
        verify(mapper, never()).pageQuery(any());
        assertNull(BaseContext.getCurrentId());
    }
}
