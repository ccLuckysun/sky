package com.sky;

import com.sky.constant.JwtClaimsConstant;
import com.sky.constant.MessageConstant;
import com.sky.constant.RedisConstant;
import com.sky.controller.admin.ShopController;
import com.sky.entity.Employee;
import com.sky.handler.GlobalExceptionHandler;
import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.json.JacksonObjectMapper;
import com.sky.mapper.EmployeeMapper;
import com.sky.properties.JwtProperties;
import com.sky.service.impl.ShopServiceImpl;
import com.sky.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 店铺营业状态：GET /admin/shop/status、PUT /admin/shop/{status}。
 * <p>
 * 前端契约（从已构建 bundle 核对，不是照抄接口文档）：页面里发的是 {@code /shop/status} 与
 * {@code /shop/{status}}，axios 的 baseURL 是 {@code /api}，nginx 把 {@code /api/} 代理到后端的
 * {@code /admin/}，所以落到这两个路径上；状态栏按 {@code 1 === status} 显示"营业中"；
 * 设置时前端发的 body 也是那个状态数字（接口文档里标 {@code application/json} 的由来），
 * 本接口只读路径变量。
 * <p>
 * Redis 用 mock 的 {@code StringRedisTemplate} 顶替（连真 Redis 的版本见
 * {@code ShopStatusDatabaseTest}），这样能断言几件真 Redis 测起来很别扭的事：键不存在时
 * **没有**发生写入、非法状态值一次写都没发生。
 */
class ShopStatusTest {

    private static final String SECRET = "shop-status-test-signing-secret";
    private static final long OPERATOR_ID = 7L;

    private ValueOperations<String, String> valueOps;
    private MockMvc mvc;
    private String token;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        valueOps = mock(ValueOperations.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        ShopServiceImpl service = new ShopServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);

        ShopController controller = new ShopController();
        ReflectionTestUtils.setField(controller, "shopService", service);

        JwtProperties properties = new JwtProperties();
        properties.setAdminSecretKey(SECRET);
        properties.setAdminTtl(7200000);
        properties.setAdminTokenName("token");

        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        when(employeeMapper.getById(OPERATOR_ID))
                .thenReturn(Employee.builder().id(OPERATOR_ID).username("admin").status(1).build());
        JwtTokenAdminInterceptor interceptor = new JwtTokenAdminInterceptor();
        ReflectionTestUtils.setField(interceptor, "jwtProperties", properties);
        ReflectionTestUtils.setField(interceptor, "employeeMapper", employeeMapper);

        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.EMP_ID, OPERATOR_ID);
        token = JwtUtil.createJWT(SECRET, 7200000L, claims);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addMappedInterceptors(new String[]{"/admin/**"}, interceptor)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new JacksonObjectMapper()))
                .build();
    }

    /** 键不存在（首次部署 / Redis 没恢复数据）时返回 0，不返回 null，也不顺手写回一个键。 */
    @Test
    void getStatusFallsBackToClosedWhenKeyMissing() throws Exception {
        when(valueOps.get(RedisConstant.SHOP_STATUS)).thenReturn(null);

        mvc.perform(get("/admin/shop/status").header("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data").value(0));

        verify(valueOps, never()).set(anyString(), anyString());
    }

    /** Redis 里存着 1 就返回 1（前端据 1 === status 显示"营业中"）。 */
    @Test
    void getStatusReturnsStoredValue() throws Exception {
        when(valueOps.get(RedisConstant.SHOP_STATUS)).thenReturn("1");

        mvc.perform(get("/admin/shop/status").header("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data").value(1));
    }

    /** 设营业与设打烊都落在同一个键上，值就是明文的 1 / 0。 */
    @Test
    void setStatusWritesPlainZeroOrOne() throws Exception {
        mvc.perform(put("/admin/shop/1").header("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
        verify(valueOps).set(RedisConstant.SHOP_STATUS, "1");

        mvc.perform(put("/admin/shop/0").header("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
        verify(valueOps).set(RedisConstant.SHOP_STATUS, "0");
    }

    /** 路径变量能传任意整数：2、-1 这类值必须被拦在写之前，否则前端按 1 判断会与后端说法不一致。 */
    @Test
    void setStatusRejectsValueOtherThanZeroOrOne() throws Exception {
        mvc.perform(put("/admin/shop/2").header("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(MessageConstant.SHOP_STATUS_ERROR));

        mvc.perform(put("/admin/shop/-1").header("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(valueOps, never()).set(anyString(), anyString());
    }

    /** 读侧守卫：键被手工改坏时报错，而不是悄悄当成打烊（否则页面显示打烊、运维以为在营业）。 */
    @Test
    void getStatusRejectsCorruptedValue() throws Exception {
        when(valueOps.get(RedisConstant.SHOP_STATUS)).thenReturn("营业");
        mvc.perform(get("/admin/shop/status").header("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(MessageConstant.SHOP_STATUS_ERROR));

        when(valueOps.get(RedisConstant.SHOP_STATUS)).thenReturn("2");
        mvc.perform(get("/admin/shop/status").header("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(MessageConstant.SHOP_STATUS_ERROR));
    }

    /** 两个接口都在 /admin/** 下，无令牌或令牌无效一律 401，且不碰 Redis。 */
    @Test
    void requiresValidToken() throws Exception {
        mvc.perform(get("/admin/shop/status")).andExpect(status().isUnauthorized());
        mvc.perform(put("/admin/shop/1")).andExpect(status().isUnauthorized());
        mvc.perform(put("/admin/shop/1").header("token", "不是令牌")).andExpect(status().isUnauthorized());

        verifyNoInteractions(valueOps);
    }
}
