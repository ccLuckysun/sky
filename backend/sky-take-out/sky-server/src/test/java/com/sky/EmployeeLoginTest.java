package com.sky;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.constant.JwtClaimsConstant;
import com.sky.context.BaseContext;
import com.sky.controller.admin.EmployeeController;
import com.sky.entity.Employee;
import com.sky.handler.GlobalExceptionHandler;
import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.mapper.EmployeeMapper;
import com.sky.properties.JwtProperties;
import com.sky.service.impl.EmployeeServiceImpl;
import com.sky.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.nio.charset.StandardCharsets;
import com.sky.exception.BaseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EmployeeLoginTest {
    private static final String SECRET = "employee-login-test-signing-secret";
    private EmployeeMapper mapper;
    private final org.springframework.security.crypto.password.PasswordEncoder encoder =
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mapper = mock(EmployeeMapper.class);
        EmployeeServiceImpl service = new EmployeeServiceImpl();
        ReflectionTestUtils.setField(service, "employeeMapper", mapper);
        ReflectionTestUtils.setField(service, "passwordEncoder", encoder);
        when(mapper.insert(any(Employee.class))).thenReturn(1);
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
                .addMappedInterceptors(new String[]{"/admin/employee", "/admin/employee/logout"}, interceptor)
                .build();
    }

    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
    }

    private Employee employee(int status) {
        return Employee.builder().id(7L).username("admin").name("管理员")
                .password(encoder.encode("123456")).status(status).build();
    }

    private MvcResult login(String body) throws Exception {
        return mvc.perform(post("/admin/employee/login")
                .contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private String token(long ttl, Map<String, Object> claims) {
        return JwtUtil.createJWT(SECRET, ttl, claims);
    }

    private Map<String, Object> employeeBody() {
        return new HashMap<>(Map.of("username", "new-employee", "name", "测试员工",
                "phone", "13800000000", "sex", "1", "idNumber", "110101199001010011"));
    }

    private org.springframework.test.web.servlet.ResultActions createEmployee(Map<String, Object> body) throws Exception {
        when(mapper.getById(7L)).thenReturn(employee(1));
        return mvc.perform(post("/admin/employee").header("token", token(60000, Map.of("empId", 7L)))
                .contentType("application/json").content(json.writeValueAsString(body)));
    }

    @Test
    void loginQueriesDatabaseAndReturnsUsableTokenWithoutPassword() throws Exception {
        when(mapper.getByUsername("admin")).thenReturn(employee(1));
        JsonNode response = json.readTree(login("{\"username\":\"admin\",\"password\":\"123456\"}")
                .getResponse().getContentAsString());
        assertEquals(1, response.path("code").asInt());
        assertEquals("admin", response.path("data").path("userName").asText());
        assertFalse(response.path("data").has("password"));
        String token = response.path("data").path("token").asText();
        Claims claims = JwtUtil.parseJWT(SECRET, token);
        assertEquals("7", claims.get(JwtClaimsConstant.EMP_ID).toString());
        assertTrue(claims.getExpiration().getTime() > System.currentTimeMillis());
        verify(mapper).getByUsername("admin");

        when(mapper.getById(7L)).thenReturn(employee(1));
        mvc.perform(post("/admin/employee/logout").header("token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
        verify(mapper).getById(7L);
        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void rejectsUnknownAccountWrongPasswordAndDisabledAccount() throws Exception {
        assertEquals(0, json.readTree(login("{\"username\":\"missing\",\"password\":\"123456\"}")
                .getResponse().getContentAsString()).path("code").asInt());
        when(mapper.getByUsername("admin")).thenReturn(employee(1));
        assertEquals(0, json.readTree(login("{\"username\":\"admin\",\"password\":\"wrong\"}")
                .getResponse().getContentAsString()).path("code").asInt());
        when(mapper.getByUsername("admin")).thenReturn(employee(0));
        JsonNode denied = json.readTree(login("{\"username\":\"admin\",\"password\":\"123456\"}")
                .getResponse().getContentAsString());
        assertEquals(0, denied.path("code").asInt());
        assertTrue(denied.path("data").isNull());
    }

    @Test
    void missingCredentialsFailBeforeDatabaseQuery() throws Exception {
        for (String body : new String[]{"{}", "{\"username\":\"admin\"}",
                "{\"username\":\" \",\"password\":\"123456\"}"}) {
            assertEquals(0, json.readTree(login(body).getResponse().getContentAsString()).path("code").asInt());
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void invalidExpiredOrMissingClaimsAreRejectedBeforeDatabaseQuery() throws Exception {
        String[] tokens = {"", "not-a-jwt", token(-10000, Map.of("empId", 7L)),
                token(60000, Map.of("userId", 7L)), token(60000, Map.of("empId", 0L)),
                Jwts.builder().setClaims(Map.of("empId", 7L))
                        .signWith(SignatureAlgorithm.HS256, SECRET.getBytes(StandardCharsets.UTF_8)).compact(),
                JwtUtil.createJWT("different-secret", 60000, Map.of("empId", 7L))};
        for (String token : tokens) {
            BaseContext.setCurrentId(99L);
            mvc.perform(post("/admin/employee/logout").header("token", token))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(0));
            assertNull(BaseContext.getCurrentId());
        }
        mvc.perform(post("/admin/employee/logout")).andExpect(status().isUnauthorized());
        verifyNoInteractions(mapper);
    }

    @Test
    void validTokenCannotAccessDeletedOrDisabledAccount() throws Exception {
        String token = token(60000, Map.of("empId", 7L));
        mvc.perform(post("/admin/employee/logout").header("token", token))
                .andExpect(status().isUnauthorized());
        when(mapper.getById(7L)).thenReturn(employee(0));
        mvc.perform(post("/admin/employee/logout").header("token", token))
                .andExpect(status().isUnauthorized());
        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void databaseFailureIsNotMisreportedAsUnauthorized() {
        when(mapper.getById(7L)).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(Exception.class, () -> mvc.perform(post("/admin/employee/logout")
                .header("token", token(60000, Map.of("empId", 7L)))));
        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void businessFailureAlsoCleansEmployeeContext() throws Exception {
        when(mapper.getById(7L)).thenReturn(employee(1));
        when(mapper.insert(any(Employee.class))).thenThrow(new BaseException("insert rejected"));
        createEmployee(employeeBody())
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(mapper).insert(any(Employee.class));
        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void authenticatedInsertUsesTokenEmployeeForAuditFieldsAndCleansContext() throws Exception {
        when(mapper.getById(7L)).thenReturn(employee(1));
        doAnswer(invocation -> {
            Employee inserted = invocation.getArgument(0);
            assertEquals(7L, inserted.getCreateUser());
            assertEquals(7L, inserted.getUpdateUser());
            assertEquals(7L, BaseContext.getCurrentId());
            assertEquals(1, inserted.getStatus());
            return 1;
        }).when(mapper).insert(any(Employee.class));
        createEmployee(employeeBody())
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
        verify(mapper).insert(any(Employee.class));
        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void createPersistsRequiredFieldsAndServerDefaultsIgnoringClientId() throws Exception {
        Map<String, Object> body = employeeBody();
        body.put("id", 123L);
        body.put("status", 0);
        body.put("password", "client-password");
        body.put("createUser", 999L);
        createEmployee(body).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data").isEmpty());
        ArgumentCaptor<Employee> capture = ArgumentCaptor.forClass(Employee.class);
        verify(mapper).insert(capture.capture());
        Employee inserted = capture.getValue();
        assertNull(inserted.getId());
        assertEquals(body.get("username"), inserted.getUsername());
        assertEquals(body.get("name"), inserted.getName());
        assertEquals(body.get("phone"), inserted.getPhone());
        assertEquals(body.get("sex"), inserted.getSex());
        assertEquals(body.get("idNumber"), inserted.getIdNumber());
        assertNotEquals("123456", inserted.getPassword());
        assertTrue(encoder.matches("123456", inserted.getPassword()));
        assertEquals(1, inserted.getStatus());
        assertEquals(7L, inserted.getCreateUser());
        assertEquals(7L, inserted.getUpdateUser());
        assertNotNull(inserted.getCreateTime());
        assertEquals(inserted.getCreateTime(), inserted.getUpdateTime());
    }

    @ParameterizedTest
    @ValueSource(strings = {"username", "name", "phone", "sex", "idNumber"})
    void rejectsEachMissingNullEmptyOrBlankRequiredField(String field) throws Exception {
        Map<String, Object> body = employeeBody();
        body.remove(field);
        createEmployee(body).andExpect(jsonPath("$.code").value(0));
        for (String value : new String[]{null, "", " \t "}) {
            body.put(field, value);
            createEmployee(body).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.msg").isNotEmpty());
        }
        verify(mapper, never()).insert(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"username", "name", "phone", "sex", "idNumber"})
    void rejectsValuesLongerThanDatabaseColumn(String field) throws Exception {
        int limit = Map.of("username", 32, "name", 32, "phone", 11, "sex", 2, "idNumber", 18).get(field);
        Map<String, Object> body = employeeBody();
        body.put(field, "x".repeat(limit + 1));
        createEmployee(body).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(mapper, never()).insert(any());
    }

    @Test
    void duplicateUsernameIncludingConcurrentInsertReturnsBusinessError() throws Exception {
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("unique username conflict"));
        createEmployee(employeeBody()).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.msg").value("用户名已存在"));
        verify(mapper).insert(any());
        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void unauthenticatedCreateDoesNotWriteDatabase() throws Exception {
        mvc.perform(post("/admin/employee").contentType("application/json")
                .content(json.writeValueAsString(employeeBody())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(0));
        verifyNoInteractions(mapper);
    }

    @Test
    void malformedBodyOrWrongIdTypeReturnsJsonError() throws Exception {
        when(mapper.getById(7L)).thenReturn(employee(1));
        for (String body : new String[]{"", "null", "{", "{\"id\":\"not-an-integer\"}"}) {
            mvc.perform(post("/admin/employee").header("token", token(60000, Map.of("empId", 7L)))
                    .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(0));
        }
        verify(mapper, never()).insert(any());
        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void requiresJsonContentType() throws Exception {
        mvc.perform(post("/admin/employee").contentType("text/plain").content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        verify(mapper, never()).insert(any());
    }
    @Test
    void plaintextAndHashAsPasswordCannotAuthenticate() throws Exception {
        Employee employee = employee(1);
        when(mapper.getByUsername("admin")).thenReturn(employee);
        String hash = employee.getPassword();
        assertEquals(0, json.readTree(login(json.writeValueAsString(Map.of("username", "admin", "password", hash)))
                .getResponse().getContentAsString()).path("code").asInt());
        employee.setPassword("123456");
        assertEquals(0, json.readTree(login("{\"username\":\"admin\",\"password\":\"123456\"}")
                .getResponse().getContentAsString()).path("code").asInt());
    }

    @Test
    void zeroInsertedRowsAreNotReportedAsSuccess() throws Exception {
        when(mapper.insert(any())).thenReturn(0);
        createEmployee(employeeBody()).andExpect(jsonPath("$.code").value(0));
    }
}
