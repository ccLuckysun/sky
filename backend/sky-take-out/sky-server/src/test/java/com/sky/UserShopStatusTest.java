package com.sky;

import com.sky.constant.MessageConstant;
import com.sky.constant.RedisConstant;
import com.sky.controller.user.ShopController;
import com.sky.handler.GlobalExceptionHandler;
import com.sky.json.JacksonObjectMapper;
import com.sky.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户端营业状态：GET /user/shop/status。
 * <p>
 * 与管理端那条的关键差别是**没有令牌**：本仓库只有拦 {@code /admin/**} 的 jwt 拦截器，用户端
 * 拦截器尚未实现，而用户端首页/登录页在未登录时就要显示"店铺已打烊"横幅。所以这里按"不带任何
 * 令牌也要能成功"来断言（真实上下文里注册的拦截器是否真的放行，由
 * {@code ShopStatusDatabaseTest} 用完整 Spring 上下文证明，standalone 的 MockMvc 里本来就没有拦截器）。
 * <p>
 * 读的是与后台同一个 {@code SHOP_STATUS} 键，用户端看到的营业状态必然与后台设置一致——这点在
 * {@code ShopStatusDatabaseTest} 里用真 Redis 跨两个入口验证。
 */
class UserShopStatusTest {

    private ValueOperations<String, String> valueOps;
    private MockMvc mvc;

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

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new JacksonObjectMapper()))
                .build();
    }

    /** 不带令牌也能读；键不存在时按打烊返回，且不会顺手写键。 */
    @Test
    void statusIsPublicAndFallsBackToClosed() throws Exception {
        when(valueOps.get(RedisConstant.SHOP_STATUS)).thenReturn(null);

        mvc.perform(get("/user/shop/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data").value(0))
                .andExpect(jsonPath("$.msg").isEmpty());

        verify(valueOps, never()).set(anyString(), anyString());
    }

    /** 后台设过的状态用户端读得到（1 营业）。 */
    @Test
    void statusReflectsStoredValue() throws Exception {
        when(valueOps.get(RedisConstant.SHOP_STATUS)).thenReturn("1");

        mvc.perform(get("/user/shop/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data").value(1));

        verify(valueOps).get(RedisConstant.SHOP_STATUS);
    }

    /** 读侧守卫与管理端共用，被改坏的键一样报错，而不是悄悄当打烊。 */
    @Test
    void corruptedValueIsRejected() throws Exception {
        when(valueOps.get(RedisConstant.SHOP_STATUS)).thenReturn("营业");

        mvc.perform(get("/user/shop/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(MessageConstant.SHOP_STATUS_ERROR));
    }
}
