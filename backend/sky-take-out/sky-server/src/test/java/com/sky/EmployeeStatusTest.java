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
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 启用、禁用员工账号：POST /admin/employee/status/{status}?id=xx
 * 用 Mock 替换 Mapper，覆盖参数绑定、状态值校验、目标员工存在性与更新内容。
 */
class EmployeeStatusTest {
    private static final String SECRET = "employee-status-test-signing-secret";
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

    /** 操作人（令牌持有者）与目标员工用不同 id，才能确认 update_user 取的是登录人。 */
    private String token() {
        return JwtUtil.createJWT(SECRET, 7200000, Map.of("empId", OPERATOR_ID));
    }

    private Employee operator() {
        return Employee.builder().id(OPERATOR_ID).username("admin").name("管理员").status(1).build();
    }

    private Employee target(int status) {
        return Employee.builder().id(TARGET_ID).username("zhangsan").status(status).build();
    }

    /**
     * 按前端实际请求方式发起调用：状态放在路径上，id 放在查询串上。
     * 接口文档要求 Content-Type: application/json，这里刻意带上该头且不带请求体，
     * 确认不会被 415/400 拦掉。
     */
    private MvcResult call(int status, Long id) throws Exception {
        var request = post("/admin/employee/status/" + status)
                .header("token", token())
                .contentType(MediaType.APPLICATION_JSON);
        if (id != null) {
            request.param("id", String.valueOf(id));
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void disableThenEnableWritesStatusAndLeavesAuditFieldsToTheAspect() throws Exception {
        when(mapper.getById(OPERATOR_ID)).thenReturn(operator());
        when(mapper.getById(TARGET_ID)).thenReturn(target(1));

        MvcResult disabled = call(0, TARGET_ID);
        assertEquals(200, disabled.getResponse().getStatus());
        assertEquals(1, body(disabled).path("code").asInt());

        ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
        verify(mapper).update(captor.capture());
        Employee update = captor.getValue();
        assertEquals(TARGET_ID, update.getId());
        assertEquals(0, update.getStatus());
        //公共字段由 AutoFillAspect 在 mapper 调用前填充，业务层不再经手（见 AutoFillAspectTest）；
        //切面取的是 BaseContext 里的登录人，因此不会把目标员工当成操作人。
        assertNull(update.getUpdateUser());
        assertNull(update.getUpdateTime());
        //动态 <set> 只写非 null 字段，未赋值的属性不能把库里的值覆盖成 null。
        assertNull(update.getUsername());
        assertNull(update.getName());
        assertNull(update.getPassword());
        assertNull(update.getPhone());

        //再从禁用回到启用，两个方向都要生效。
        reset(mapper);
        when(mapper.getById(OPERATOR_ID)).thenReturn(operator());
        when(mapper.getById(TARGET_ID)).thenReturn(target(0));
        assertEquals(1, body(call(1, TARGET_ID)).path("code").asInt());
        ArgumentCaptor<Employee> enableCaptor = ArgumentCaptor.forClass(Employee.class);
        verify(mapper).update(enableCaptor.capture());
        assertEquals(1, enableCaptor.getValue().getStatus());
    }

    @Test
    void repeatingTheSameStatusSucceedsEvenWhenNoRowChanges() throws Exception {
        when(mapper.getById(OPERATOR_ID)).thenReturn(operator());
        when(mapper.getById(TARGET_ID)).thenReturn(target(0));
        //MySQL 在状态与原值相同时返回 0 行，重复点击“禁用”不能报错。
        when(mapper.update(any(Employee.class))).thenReturn(0);

        JsonNode response = body(call(0, TARGET_ID));
        assertEquals(1, response.path("code").asInt());
        assertNull(response.path("msg").asText(null));
        verify(mapper).update(any(Employee.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, -1, 99})
    void rejectsStatusOutsideZeroAndOneWithoutTouchingDatabase(int status) throws Exception {
        when(mapper.getById(OPERATOR_ID)).thenReturn(operator());

        JsonNode response = body(call(status, TARGET_ID));
        assertEquals(0, response.path("code").asInt());
        assertEquals("状态值不合法，只能为1(启用)或0(禁用)", response.path("msg").asText());
        verify(mapper, never()).update(any(Employee.class));
    }

    @Test
    void rejectsUnknownEmployeeAndMissingId() throws Exception {
        when(mapper.getById(OPERATOR_ID)).thenReturn(operator());

        JsonNode missing = body(call(1, TARGET_ID));
        assertEquals(0, missing.path("code").asInt());
        assertEquals("账号不存在", missing.path("msg").asText());

        JsonNode noId = body(call(1, null));
        assertEquals(0, noId.path("code").asInt());
        assertEquals("员工id不能为空", noId.path("msg").asText());

        verify(mapper, never()).update(any(Employee.class));
    }

    @Test
    void disabledAccountLosesAccessWithItsExistingToken() throws Exception {
        when(mapper.getById(OPERATOR_ID)).thenReturn(operator());
        when(mapper.getById(TARGET_ID)).thenReturn(target(1));
        assertEquals(1, body(call(0, TARGET_ID)).path("code").asInt());

        //目标员工被禁用后，他手上未过期的令牌必须立即失效。
        when(mapper.getById(OPERATOR_ID)).thenReturn(Employee.builder()
                .id(OPERATOR_ID).username("admin").status(0).build());
        MvcResult rejected = call(1, OPERATOR_ID);
        assertEquals(401, rejected.getResponse().getStatus());
        assertEquals(0, body(rejected).path("code").asInt());
    }
}
