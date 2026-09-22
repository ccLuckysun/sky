package com.sky;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 编辑员工信息：PUT /admin/employee */
class EmployeeUpdateTest {
    private static final String SECRET = "employee-update-test-signing-secret";
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

    private Employee operator() {
        return Employee.builder().id(OPERATOR_ID).username("admin").name("管理员").status(1).build();
    }

    private Employee target() {
        return Employee.builder().id(TARGET_ID).username("zhangsan").status(1).build();
    }

    /** 一次合法的编辑请求体：接口文档要求的五个字段加上 id。 */
    private Map<String, Object> body() {
        return new HashMap<>(Map.of("id", TARGET_ID, "username", "lisi", "name", "李四",
                "phone", "13900000000", "sex", "0", "idNumber", "110101199001010022"));
    }

    /** 名字不能用 put，否则会遮蔽 MockMvcRequestBuilders.put 的静态导入。 */
    private MvcResult edit(Map<String, Object> body) throws Exception {
        return mvc.perform(put("/admin/employee")
                        .header("token", JwtUtil.createJWT(SECRET, 7200000, Map.of("empId", OPERATOR_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return this.json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void writesSubmittedFieldsAndAuditInfoOnly() throws Exception {
        when(mapper.getById(OPERATOR_ID)).thenReturn(operator());
        when(mapper.getById(TARGET_ID)).thenReturn(target());
        when(mapper.update(any(Employee.class))).thenReturn(1);
        LocalDateTime before = LocalDateTime.now();

        MvcResult result = edit(body());
        assertEquals(200, result.getResponse().getStatus());
        JsonNode response = json(result);
        assertEquals(1, response.path("code").asInt());
        assertNull(response.path("msg").asText(null));

        ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
        verify(mapper).update(captor.capture());
        Employee update = captor.getValue();
        assertEquals(TARGET_ID, update.getId());
        assertEquals("lisi", update.getUsername());
        assertEquals("李四", update.getName());
        assertEquals("13900000000", update.getPhone());
        assertEquals("0", update.getSex());
        assertEquals("110101199001010022", update.getIdNumber());
        assertEquals(OPERATOR_ID, update.getUpdateUser(), "update_user 应为登录人");
        assertFalse(update.getUpdateTime().isBefore(before), "update_time 应刷新为本次操作时间");
        //编辑接口不能改账号状态和口令：这两个属性必须保持 null，动态 <set> 才不会写到它们。
        assertNull(update.getStatus(), "编辑接口不得改动 status");
        assertNull(update.getPassword(), "编辑接口不得改动 password");
    }

    @Test
    void unchangedContentStillSucceedsWhenNoRowIsAffected() throws Exception {
        when(mapper.getById(OPERATOR_ID)).thenReturn(operator());
        when(mapper.getById(TARGET_ID)).thenReturn(target());
        //提交内容与库中完全一致时 MySQL 返回 0 行，未做修改不应报错。
        when(mapper.update(any(Employee.class))).thenReturn(0);

        assertEquals(1, json(edit(body())).path("code").asInt());
        verify(mapper).update(any(Employee.class));
    }

    @Test
    void duplicateUsernameIsReportedAsSuch() throws Exception {
        when(mapper.getById(OPERATOR_ID)).thenReturn(operator());
        when(mapper.getById(TARGET_ID)).thenReturn(target());
        when(mapper.update(any(Employee.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry 'lisi' for key 'employee.username'"));

        JsonNode response = json(edit(body()));
        assertEquals(0, response.path("code").asInt());
        assertEquals("用户名已存在", response.path("msg").asText());
    }

    @Test
    void rejectsUnknownEmployeeAndMissingId() throws Exception {
        when(mapper.getById(OPERATOR_ID)).thenReturn(operator());

        JsonNode unknown = json(edit(body()));
        assertEquals(0, unknown.path("code").asInt());
        assertEquals("账号不存在", unknown.path("msg").asText());

        Map<String, Object> noId = body();
        noId.remove("id");
        assertEquals("员工id不能为空", json(edit(noId)).path("msg").asText());

        verify(mapper, never()).update(any(Employee.class));
    }

    /**
     * 五个字段接口文档都标为必填，逐个清空验证会被拦下且不落库。
     */
    @ParameterizedTest
    @ValueSource(strings = {"username", "name", "phone", "sex", "idNumber"})
    void rejectsBlankRequiredField(String field) throws Exception {
        when(mapper.getById(OPERATOR_ID)).thenReturn(operator());

        for (String blank : new String[]{"", "   "}) {
            Map<String, Object> body = body();
            body.put(field, blank);
            JsonNode response = json(edit(body));
            assertEquals(0, response.path("code").asInt(), field + " 为空时应被拒绝");
            assertTrue(response.path("msg").asText().contains("不能为空"), "错误信息应指出字段为空：" + response);
        }
        //字段缺失等同于不合法，不能再依赖数据库的 not null 约束兜底。
        Map<String, Object> missing = body();
        missing.remove(field);
        assertEquals(0, json(edit(missing)).path("code").asInt());

        verify(mapper, never()).update(any(Employee.class));
    }

    @Test
    void rejectsFieldLongerThanColumnDefinition() throws Exception {
        when(mapper.getById(OPERATOR_ID)).thenReturn(operator());

        Map<String, Object> tooLong = body();
        tooLong.put("phone", "1".repeat(12));

        JsonNode response = json(edit(tooLong));
        assertEquals(0, response.path("code").asInt());
        assertEquals("手机号不能超过11个字符", response.path("msg").asText());
        verify(mapper, never()).update(any(Employee.class));
    }

    @Test
    void emptyJsonBodyIsRejectedBeforeServiceCall() throws Exception {
        when(mapper.getById(OPERATOR_ID)).thenReturn(operator());

        JsonNode response = json(edit(new HashMap<>()));
        assertEquals(0, response.path("code").asInt());
        assertEquals("员工id不能为空", response.path("msg").asText());
        verify(mapper, never()).update(any(Employee.class));
    }
}
