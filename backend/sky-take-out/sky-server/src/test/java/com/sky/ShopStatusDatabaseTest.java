package com.sky;

import com.sky.constant.JwtClaimsConstant;
import com.sky.constant.MessageConstant;
import com.sky.constant.RedisConstant;
import com.sky.entity.Employee;
import com.sky.mapper.EmployeeMapper;
import com.sky.properties.JwtProperties;
import com.sky.utils.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 店铺营业状态的真实联调：走完整 HTTP 链路（含 jwt 拦截器）→ ShopService → 本机 Redis，
 * 证明"接口读写的键"和"redis-cli 看到的键"是同一个，且值就是明文的 1/0；同时跨管理端与用户端
 * 两个入口验证它们读的是同一份状态（用户端那条还验证了不带令牌也放行，见 {@link #userEntrySeesAdminSetting()}）。
 * <p>
 * 与其它 {@code *DatabaseTest} 同一个开关 {@code SKY_DB_TESTS=true} 显式启用，默认跳过。
 * <p>
 * 这个类动的是**真实业务键** {@code SHOP_STATUS}（不像 {@code RedisTemplateDatabaseTest} 那样挂在
 * {@code sky:test:} 前缀下）：要用真键才谈得上"证明接口与前端看的是同一个键"。所以 {@link #setUp()}
 * 先把真值记下来，{@link #restoreRealStatus()} 原样还原（本来没有这个键就删掉），
 * 跑测试不会改变店铺实际的营业状态。
 */
@EnabledIfEnvironmentVariable(named = "SKY_DB_TESTS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
class ShopStatusDatabaseTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private EmployeeMapper employeeMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private String token;
    private String realStatusBeforeTest;

    @BeforeEach
    void setUp() {
        realStatusBeforeTest = stringRedisTemplate.opsForValue().get(RedisConstant.SHOP_STATUS);

        Employee admin = employeeMapper.getByUsername("admin");
        assertNotNull(admin, "数据库需存在启用的 admin 员工");
        assertEquals(1, admin.getStatus());
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.EMP_ID, admin.getId());
        token = JwtUtil.createJWT(jwtProperties.getAdminSecretKey(), jwtProperties.getAdminTtl(), claims);

        //每个用例都从"这个键没设过"开始，否则上一个用例留下的值会让默认值断言失去意义
        stringRedisTemplate.delete(RedisConstant.SHOP_STATUS);
    }

    @AfterEach
    void restoreRealStatus() {
        if (realStatusBeforeTest == null) {
            stringRedisTemplate.delete(RedisConstant.SHOP_STATUS);
        } else {
            stringRedisTemplate.opsForValue().set(RedisConstant.SHOP_STATUS, realStatusBeforeTest);
        }
    }

    /** 键不存在时接口返回打烊，且不会顺手把键写进 Redis。 */
    @Test
    void missingKeyReadsAsClosed() throws Exception {
        mvc.perform(get("/admin/shop/status").header("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data").value(0))
                //msg 字段存在且为 null（不是不序列化），前端与联调脚本按这个形状解析
                .andExpect(jsonPath("$.msg").isEmpty());

        assertEquals(Boolean.FALSE, stringRedisTemplate.hasKey(RedisConstant.SHOP_STATUS));
    }

    /** 设置后 Redis 里的值与接口读回的值一致，前后端看的是同一份状态。 */
    @Test
    void setThenGetRoundTripsThroughRealRedis() throws Exception {
        mvc.perform(put("/admin/shop/1").header("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data").isEmpty());
        assertEquals("1", stringRedisTemplate.opsForValue().get(RedisConstant.SHOP_STATUS));

        mvc.perform(get("/admin/shop/status").header("token", token))
                .andExpect(jsonPath("$.data").value(1));

        mvc.perform(put("/admin/shop/0").header("token", token))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals("0", stringRedisTemplate.opsForValue().get(RedisConstant.SHOP_STATUS));

        mvc.perform(get("/admin/shop/status").header("token", token))
                .andExpect(jsonPath("$.data").value(0));
    }

    /** 非法状态值被拒后，Redis 里原有的状态必须原样还在（不能写坏，也不能被清零）。 */
    @Test
    void invalidStatusIsRejectedAndLeavesStoredValueUntouched() throws Exception {
        stringRedisTemplate.opsForValue().set(RedisConstant.SHOP_STATUS, "1");

        mvc.perform(put("/admin/shop/2").header("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(MessageConstant.SHOP_STATUS_ERROR));

        assertEquals("1", stringRedisTemplate.opsForValue().get(RedisConstant.SHOP_STATUS));
    }

    /**
     * 管理端设置、用户端读取：两个入口读的是 Redis 里同一个键，用户端那条不带令牌也要放行
     * （用户端首页在未登录时就要显示"店铺已打烊"横幅，所以它不能要求登录态）。
     */
    @Test
    void userEntrySeesAdminSetting() throws Exception {
        mvc.perform(put("/admin/shop/1").header("token", token))
                .andExpect(jsonPath("$.code").value(1));
        mvc.perform(get("/user/shop/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data").value(1));

        mvc.perform(put("/admin/shop/0").header("token", token))
                .andExpect(jsonPath("$.code").value(1));
        mvc.perform(get("/user/shop/status"))
                .andExpect(jsonPath("$.data").value(0));
    }

    /** 同一个状态重复设置不应被当成失败（前端连点两次只是空转）。 */
    @Test
    void repeatedSetOfSameStatusSucceeds() throws Exception {
        mvc.perform(put("/admin/shop/1").header("token", token)).andExpect(jsonPath("$.code").value(1));
        mvc.perform(put("/admin/shop/1").header("token", token)).andExpect(jsonPath("$.code").value(1));

        assertEquals("1", stringRedisTemplate.opsForValue().get(RedisConstant.SHOP_STATUS));
    }
}
