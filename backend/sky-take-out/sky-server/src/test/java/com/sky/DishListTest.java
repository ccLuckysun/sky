package com.sky;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.context.BaseContext;
import com.sky.controller.admin.DishController;
import com.sky.entity.Dish;
import com.sky.entity.Employee;
import com.sky.handler.GlobalExceptionHandler;
import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.json.JacksonObjectMapper;
import com.sky.mapper.CategoryMapper;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.EmployeeMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.properties.JwtProperties;
import com.sky.service.impl.DishServiceImpl;
import com.sky.utils.JwtUtil;
import com.sky.utils.LocalFileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 按分类id或名称查询菜品列表：GET /admin/dish/list。
 * <p>
 * 前端契约（从已构建 bundle 的 source map 核对）：唯一的调用方是套餐页的选菜组件
 * {@code setmeal/components/AddDish.vue}，它发两种形状——
 * {@code queryDishList({categoryId: id})} 与 {@code queryDishList({name})}（关键字搜索那一支，
 * 由 {@code @Watch('seachKey')} 触发），并且无条件读 {@code res.data.data.length}。
 * <p>
 * 用 Mock 替换五个 mapper，重点覆盖几件 mock 之外证明不了的事：两个条件都只是"可选"而不是必填、
 * status 不会被当成筛选条件、以及空结果必须是数组而不是 null。
 */
class DishListTest {

    private static final String SECRET = "dish-list-test-signing-secret";
    private static final long OPERATOR_ID = 7L;
    private static final long CATEGORY_ID = 16L;

    private DishMapper dishMapper;
    private DishFlavorMapper dishFlavorMapper;
    private CategoryMapper categoryMapper;
    private SetmealMapper setmealMapper;
    private LocalFileUtil localFileUtil;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        dishMapper = mock(DishMapper.class);
        dishFlavorMapper = mock(DishFlavorMapper.class);
        categoryMapper = mock(CategoryMapper.class);
        setmealMapper = mock(SetmealMapper.class);
        localFileUtil = mock(LocalFileUtil.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        when(employeeMapper.getById(OPERATOR_ID))
                .thenReturn(Employee.builder().id(OPERATOR_ID).username("admin").status(1).build());

        DishServiceImpl service = new DishServiceImpl();
        ReflectionTestUtils.setField(service, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(service, "dishFlavorMapper", dishFlavorMapper);
        ReflectionTestUtils.setField(service, "categoryMapper", categoryMapper);
        ReflectionTestUtils.setField(service, "setmealMapper", setmealMapper);
        ReflectionTestUtils.setField(service, "localFileUtil", localFileUtil);

        JwtProperties properties = new JwtProperties();
        properties.setAdminSecretKey(SECRET);
        properties.setAdminTtl(7200000);
        properties.setAdminTokenName("token");

        DishController controller = new DishController();
        ReflectionTestUtils.setField(controller, "dishService", service);

        JwtTokenAdminInterceptor interceptor = new JwtTokenAdminInterceptor();
        ReflectionTestUtils.setField(interceptor, "jwtProperties", properties);
        ReflectionTestUtils.setField(interceptor, "employeeMapper", employeeMapper);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addMappedInterceptors(new String[]{"/admin/**"}, interceptor)
                //用真实转换器：price 是不是 JSON number、updateTime 是不是字符串都由它决定
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new JacksonObjectMapper()))
                .build();
    }

    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
    }

    private String token() {
        return JwtUtil.createJWT(SECRET, 7200000, Map.of("empId", OPERATOR_ID));
    }

    /**
     * 按前端实际请求方式发起调用；没列进来的查询参数整个不出现（前端就是这样发的）。
     * <p>
     * 用 {@code .param()} 而不是把值拼进 URL：{@code get("/...?name=%E9%B1%BC")} 里的百分号转义
     * <b>不会</b>被 MockMvc 解码，参数拿到的就是字面量 {@code %E9%B1%BC}，
     * 断言会以一种看起来像"服务端没处理中文"的方式失败。
     */
    private MvcResult call(Map<String, String> params, String token) throws Exception {
        var request = get("/admin/dish/list");
        if (token != null) {
            request.header("token", token);
        }
        params.forEach(request::param);
        return mvc.perform(request).andReturn();
    }

    private MvcResult call(Map<String, String> params) throws Exception {
        return call(params, token());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 断言交给 dishMapper.list 的条件载体上**只有**预期的字段有值。 */
    private Dish capturedQuery(Long expectedCategoryId, String expectedName) {
        ArgumentCaptor<Dish> captor = ArgumentCaptor.forClass(Dish.class);
        verify(dishMapper).list(captor.capture());
        Dish dish = captor.getValue();
        assertNotNull(dish);
        assertEquals(expectedCategoryId, dish.getCategoryId());
        assertEquals(expectedName, dish.getName());
        //其余列一律为 null，动态 <where> 因此不会按它们筛选。
        assertNull(dish.getStatus(), "status 不该成为筛选条件");
        assertNull(dish.getId());
        assertNull(dish.getPrice());
        assertNull(dish.getImage());
        assertNull(dish.getDescription());
        return dish;
    }

    /** 一条完整的库中记录，字段齐备，用来断言响应形状。 */
    private Dish sampleDish(long id, String name, int status) {
        return Dish.builder()
                .id(id)
                .name(name)
                .categoryId(CATEGORY_ID)
                .price(new BigDecimal("38.50"))
                .image("https://sky-itcast.oss-cn-hangzhou.aliyuncs.com/legacy.png")
                .description("描述")
                .status(status)
                .createTime(LocalDateTime.of(2026, 9, 23, 10, 0))
                .updateTime(LocalDateTime.of(2026, 9, 23, 11, 30))
                .createUser(1L)
                .updateUser(1L)
                .build();
    }

    @Test
    void returnsTheDishesOfACategory() throws Exception {
        when(dishMapper.list(any())).thenReturn(List.of(
                sampleDish(46L, "麻婆豆腐", 1),
                sampleDish(47L, "水煮鱼", 0)));

        JsonNode response = body(call(Map.of("categoryId", String.valueOf(CATEGORY_ID))));

        assertEquals(1, response.path("code").asInt());
        assertNull(response.path("msg").asText(null));

        JsonNode data = response.path("data");
        assertTrue(data.isArray(), "data 必须是数组：" + data);
        assertEquals(2, data.size());
        capturedQuery(CATEGORY_ID, null);
    }

    /**
     * 不传条件时返回全部菜品，而不是报"参数不能为空"。
     * <p>
     * 接口文档把 categoryId 标成必填，但前端的关键字搜索那一支只发 name，所以 categoryId 不能真做成
     * 必填；既然 allow 了"没有 categoryId"，就没有理由再单独禁止"两个都没有"——那是凭空多一条
     * 接口文档没写的限制（与第 17 节价格上限同一立场）。
     */
    @Test
    void returnsEveryDishWhenNoConditionIsGiven() throws Exception {
        when(dishMapper.list(any())).thenReturn(List.of(sampleDish(46L, "麻婆豆腐", 1)));

        JsonNode response = body(call(Map.of()));

        assertEquals(1, response.path("code").asInt());
        assertEquals(1, response.path("data").size());
        capturedQuery(null, null);
    }

    @Test
    void searchesByNameAndTrimsSurroundingWhitespace() throws Exception {
        when(dishMapper.list(any())).thenReturn(List.of(sampleDish(47L, "水煮鱼", 0)));

        JsonNode response = body(call(Map.of("name", " 水煮鱼 ")));

        assertEquals(1, response.path("code").asInt());
        assertEquals("水煮鱼", response.path("data").get(0).path("name").asText());
        //两端空白去掉后才下传，否则 like '% 水煮鱼 %' 什么都匹配不到
        capturedQuery(null, "水煮鱼");
    }

    /** 纯空白等同于不筛选，否则 like '% %' 会把语义悄悄变成"名称里含空格"。 */
    @Test
    void treatsABlankNameAsNoFilter() throws Exception {
        when(dishMapper.list(any())).thenReturn(List.of());

        assertEquals(1, body(call(Map.of("name", "   "))).path("code").asInt());

        capturedQuery(null, null);
    }

    @Test
    void combinesCategoryAndName() throws Exception {
        when(dishMapper.list(any())).thenReturn(List.of());

        assertEquals(1, body(call(Map.of("categoryId", String.valueOf(CATEGORY_ID), "name", "鱼"))).path("code").asInt());

        capturedQuery(CATEGORY_ID, "鱼");
    }

    /**
     * 本接口**不过滤起售状态**，所以就算调用方自己传了 status 也不该被当成筛选条件。
     * <p>
     * 这是本轮那个业务决定的回归守卫：选菜组件对 status = 0 的菜渲染「停售」标签，
     * 而且预置常量 SETMEAL_ENABLE_FAILED「套餐内包含未启售菜品，无法启售」说明停售菜品本就可以
     * 先进套餐、由套餐启售时兜底。一旦有人"顺手"给这条 SQL 加上 {@code status = 1}，
     * 上面两处都会变成永远走不到的死代码。
     */
    @Test
    void neverTreatsStatusAsAFilterEvenWhenTheCallerSendsOne() throws Exception {
        when(dishMapper.list(any())).thenReturn(List.of(
                sampleDish(46L, "麻婆豆腐", 1),
                sampleDish(47L, "水煮鱼", 0)));

        JsonNode response = body(call(Map.of("categoryId", String.valueOf(CATEGORY_ID), "status", "1")));

        assertEquals(1, response.path("code").asInt());
        //停售的那道照常出现在结果里（mock 返回了两条）
        assertEquals(2, response.path("data").size());
        //而交给 mapper 的条件载体上 status 是 null —— 它根本没参与筛选
        capturedQuery(CATEGORY_ID, null);
    }

    /**
     * 空结果必须是 {@code []}，不能是 null。
     * <p>
     * 前端选菜组件直接读 {@code res.data.data.length}，null 会让整个弹窗抛 TypeError
     * （与第 21 节"flavors 一定要是数组"同一类硬约束）。MyBatis 的 select 返回集合时本身不会给
     * null，这条同时把"Service 里那次兜底"钉住。
     */
    @Test
    void returnsAnEmptyArrayInsteadOfNullWhenNothingMatches() throws Exception {
        when(dishMapper.list(any())).thenReturn(List.of());
        JsonNode empty = body(call(Map.of("name", "不存在的菜名"))).path("data");
        assertTrue(empty.isArray(), "没有命中时 data 必须是数组：" + empty);
        assertEquals(0, empty.size());

        //mapper 给 null 时（理论上不会发生）也要兜成数组，而不是把 null 透出去
        setUp();
        when(dishMapper.list(any())).thenReturn(null);
        JsonNode nulled = body(call(Map.of("name", "不存在的菜名"))).path("data");
        assertTrue(nulled.isArray(), "mapper 返回 null 时也要兜成数组：" + nulled);
        assertEquals(0, nulled.size());
    }

    /**
     * 返回的是 Dish 实体而不是 DishVO：接口文档的响应模型列了 createTime / createUser / updateUser，
     * 这三列只在实体上有。同时钉住 price 是 JSON number、updateTime 是 "yyyy-MM-dd HH:mm" 字符串
     * ——两条都由 JacksonObjectMapper 决定（第 6 节），而 standaloneSetup 不执行
     * WebMvcConfiguration.extendMessageConverters，所以上面显式装了它。
     */
    @Test
    void returnsTheDishEntityShapeIncludingTheAuditColumns() throws Exception {
        when(dishMapper.list(any())).thenReturn(List.of(sampleDish(46L, "麻婆豆腐", 1)));

        JsonNode first = body(call(Map.of("categoryId", String.valueOf(CATEGORY_ID)))).path("data").get(0);

        assertEquals(46L, first.path("id").asLong());
        assertEquals("麻婆豆腐", first.path("name").asText());
        assertEquals(CATEGORY_ID, first.path("categoryId").asLong());
        assertTrue(first.path("price").isNumber(), "price 应为 JSON number：" + first.path("price"));
        assertEquals(1, first.path("status").asInt());
        assertTrue(first.path("updateTime").asText().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"),
                "updateTime 格式不符合 yyyy-MM-dd HH:mm：" + first.path("updateTime"));
        //这三列是"必须返回实体而非 VO"的依据
        assertTrue(first.has("createTime"), "响应应含 createTime：" + first);
        assertTrue(first.has("createUser"), "响应应含 createUser：" + first);
        assertTrue(first.has("updateUser"), "响应应含 updateUser：" + first);
    }

    /**
     * {@code /admin/dish/list} 必须进 list 方法，不能被 {@code @GetMapping("/{id}")} 抢走。
     * <p>
     * 不靠"请求返回了 200"来证明——那样 getById 抛了业务异常也是 200。这里断言 {@code getById}
     * 一次都没被调用（与第 21 节 {@code keepsTheLiteralPageRouteOnThePageMethod} 同一手法）。
     */
    @Test
    void keepsTheListRouteOnTheListMethodInsteadOfGetById() throws Exception {
        when(dishMapper.list(any())).thenReturn(List.of());

        assertEquals(1, body(call(Map.of())).path("code").asInt());

        verify(dishMapper, never()).getById(any());
        verifyNoInteractions(dishFlavorMapper, categoryMapper, setmealMapper, localFileUtil);
    }

    @Test
    void requiresAuthenticationAndTouchesNothing() throws Exception {
        MvcResult anonymous = call(Map.of("categoryId", String.valueOf(CATEGORY_ID)), null);
        assertEquals(401, anonymous.getResponse().getStatus());
        assertEquals(0, body(anonymous).path("code").asInt());

        MvcResult invalid = call(Map.of("categoryId", String.valueOf(CATEGORY_ID)), "not-a-jwt");
        assertEquals(401, invalid.getResponse().getStatus());
        assertEquals(0, body(invalid).path("code").asInt());

        verifyNoInteractions(dishMapper);
    }

    /** 查询接口不该碰口味表、分类表、套餐表，更不该碰磁盘。 */
    @Test
    void neverTouchesFlavorsCategoriesSetmealsOrImages() throws Exception {
        when(dishMapper.list(any())).thenReturn(List.of(sampleDish(46L, "麻婆豆腐", 1)));

        assertEquals(1, body(call(Map.of("categoryId", String.valueOf(CATEGORY_ID)))).path("code").asInt());

        verifyNoInteractions(dishFlavorMapper, categoryMapper, setmealMapper, localFileUtil);
    }

    /** 没有被认领的查询参数（比如 status）不会让请求失败，只是被忽略。 */
    @Test
    void ignoresUnknownQueryParameters() throws Exception {
        when(dishMapper.list(any())).thenReturn(List.of());

        assertEquals(1, body(call(Map.of("categoryId", String.valueOf(CATEGORY_ID), "foo", "bar", "page", "1", "pageSize", "10"))).path("code").asInt());

        //顺带证明这里没有走分页：分页参数被忽略，交出去的还是那两个条件
        capturedQuery(CATEGORY_ID, null);
    }
}
