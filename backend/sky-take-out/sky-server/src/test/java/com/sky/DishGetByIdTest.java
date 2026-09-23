package com.sky;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.context.BaseContext;
import com.sky.controller.admin.DishController;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.entity.Employee;
import com.sky.handler.GlobalExceptionHandler;
import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.json.JacksonObjectMapper;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.EmployeeMapper;
import com.sky.properties.JwtProperties;
import com.sky.service.impl.DishServiceImpl;
import com.sky.utils.JwtUtil;
import com.sky.utils.LocalFileUtil;
import com.sky.vo.DishVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 编辑页回显：GET /admin/dish/{id}。
 * <p>
 * 前端契约（从已构建 bundle 的 source map 核对，{@code src/views/dish/addDishtype.vue} 的 init()）：
 * <pre>
 *   this.ruleForm = { ...res.data.data }
 *   this.dishFlavors = res.data.data.flavors &amp;&amp; res.data.data.flavors.map(o => ({...o, value: JSON.parse(o.value)}))
 * </pre>
 * 由此得出本类守住的两条硬约束：<b>flavors 必须是数组</b>（没有口味的菜要给 {@code []} 而不是 null，
 * 否则 dishFlavors 变成 null，后面 .map 直接 TypeError 把整页打废），
 * 以及 {@code /admin/dish/page} 不能被 {@code @GetMapping("/{id}")} 抢走。
 */
class DishGetByIdTest {

    private static final String SECRET = "dish-get-by-id-test-signing-secret";
    private static final long OPERATOR_ID = 7L;
    private static final long DISH_ID = 46L;
    private static final long CATEGORY_ID = 16L;

    private DishMapper dishMapper;
    private DishFlavorMapper dishFlavorMapper;
    private LocalFileUtil localFileUtil;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        dishMapper = mock(DishMapper.class);
        dishFlavorMapper = mock(DishFlavorMapper.class);
        //回显接口不该碰文件系统：用 mock 才能断言"一次都没调用过"
        localFileUtil = mock(LocalFileUtil.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        when(employeeMapper.getById(OPERATOR_ID))
                .thenReturn(Employee.builder().id(OPERATOR_ID).username("admin").status(1).build());

        DishServiceImpl service = new DishServiceImpl();
        ReflectionTestUtils.setField(service, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(service, "dishFlavorMapper", dishFlavorMapper);
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
                //用真实转换器：LocalDateTime 输出成 "yyyy-MM-dd HH:mm"、BigDecimal 出成 JSON number 都由它决定
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new JacksonObjectMapper()))
                .build();
    }

    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
        PageHelper.clearPage();
    }

    private String token() {
        return JwtUtil.createJWT(SECRET, 7200000, Map.of("empId", OPERATOR_ID));
    }

    private MvcResult call(String path, String token) throws Exception {
        var request = get(path);
        if (token != null) {
            request.header("token", token);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult call(String path) throws Exception {
        return call(path, token());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 库里的一行菜品。updateTime 是 LocalDateTime，输出格式由 JacksonObjectMapper 决定。 */
    private Dish storedDish() {
        return Dish.builder()
                .id(DISH_ID).name("草鱼2斤").categoryId(CATEGORY_ID)
                .price(new BigDecimal("38.00")).image("/uploads/2026/09/23/a.png")
                .description("描述").status(1)
                .createTime(LocalDateTime.of(2026, 9, 1, 9, 0, 0))
                .updateTime(LocalDateTime.of(2026, 9, 22, 11, 0, 0))
                .createUser(1L).updateUser(OPERATOR_ID)
                .build();
    }

    private DishFlavor flavor(long id, String name, String value) {
        return DishFlavor.builder().id(id).dishId(DISH_ID).name(name).value(value).build();
    }

    @Test
    void returnsTheDishWithItsFlavors() throws Exception {
        when(dishMapper.getById(DISH_ID)).thenReturn(storedDish());
        when(dishFlavorMapper.getByDishId(DISH_ID)).thenReturn(List.of(
                flavor(1L, "甜味", "[\"无糖\",\"多糖\"]"),
                flavor(2L, "温度", "[\"常温\"]")));

        MvcResult result = call("/admin/dish/" + DISH_ID);
        JsonNode response = body(result);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(1, response.path("code").asInt());
        JsonNode data = response.path("data");
        assertEquals(DISH_ID, data.path("id").asLong());
        assertEquals("草鱼2斤", data.path("name").asText());
        assertEquals(CATEGORY_ID, data.path("categoryId").asLong());
        assertEquals("/uploads/2026/09/23/a.png", data.path("image").asText());
        assertEquals("描述", data.path("description").asText());
        assertEquals(1, data.path("status").asInt());

        //price 必须是 JSON number：前端这一页直接把它 String() 塞回表单，出成字符串会多一层引号
        assertTrue(data.path("price").isNumber(), "price 应为 JSON number：" + data.path("price"));
        assertEquals(0, new BigDecimal("38.00").compareTo(new BigDecimal(data.path("price").asText())));

        //时间必须是接口文档约定的字符串，而不是时间戳数组（JacksonObjectMapper 的格式）
        assertTrue(data.path("updateTime").isTextual(), "updateTime 应为字符串：" + data.path("updateTime"));
        assertTrue(data.path("updateTime").asText().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"),
                "updateTime 格式不符合 yyyy-MM-dd HH:mm：" + data.path("updateTime").asText());
        assertEquals("2026-09-22 11:00", data.path("updateTime").asText());

        //编辑页不读 categoryName（分类下拉框自己取），本轮不为它多写一条 join
        assertTrue(data.path("categoryName").isNull(), "本轮不 join category，categoryName 应为 null");

        JsonNode flavors = data.path("flavors");
        assertTrue(flavors.isArray(), "flavors 必须是数组：" + flavors);
        assertEquals(2, flavors.size());
        assertEquals(1L, flavors.get(0).path("id").asLong());
        assertEquals(DISH_ID, flavors.get(0).path("dishId").asLong());
        assertEquals("甜味", flavors.get(0).path("name").asText());
        //value 是 JSON 字符串（前端会 JSON.parse 它），服务端原样返回不解析
        assertEquals("[\"无糖\",\"多糖\"]", flavors.get(0).path("value").asText());
        assertEquals("温度", flavors.get(1).path("name").asText());
    }

    /**
     * 没有口味的菜必须返回 {@code []} 而不是 null。
     * <p>
     * 这是编辑页能否打开的硬前提：前端拿到 data 后立刻执行
     * {@code this.dishFlavors = res.data.data.flavors && res.data.data.flavors.map(...)}，
     * {@code &&} 只在 flavors 为假值时短路（null 会让 dishFlavors 变成 null），
     * 页面随后的渲染会对 dishFlavors 调 .map 而抛 TypeError，整页白屏。
     * <p>
     * 注意这条用例同时守住了"别用 {@code DishVO.builder()} 构造"：{@code @Builder} 不应用字段初始值，
     * builder 出来的 flavors 是 null，本用例会立刻变红。
     */
    @Test
    void returnsAnEmptyFlavorArrayWhenTheDishHasNoFlavor() throws Exception {
        when(dishMapper.getById(DISH_ID)).thenReturn(storedDish());
        when(dishFlavorMapper.getByDishId(DISH_ID)).thenReturn(List.of());

        JsonNode flavors = body(call("/admin/dish/" + DISH_ID)).path("data").path("flavors");

        assertFalse(flavors.isNull(), "没有口味时必须给 []，null 会让前端 .map 抛 TypeError");
        assertTrue(flavors.isArray(), "flavors 必须是数组（不能是 null）：" + flavors);
        assertEquals(0, flavors.size());
    }

    /** mapper 返回 null 时也要兜成空列表：接口的返回值永远不能是 null。 */
    @Test
    void fallsBackToAnEmptyArrayWhenTheFlavorQueryReturnsNull() throws Exception {
        when(dishMapper.getById(DISH_ID)).thenReturn(storedDish());
        when(dishFlavorMapper.getByDishId(DISH_ID)).thenReturn(null);

        JsonNode flavors = body(call("/admin/dish/" + DISH_ID)).path("data").path("flavors");

        assertTrue(flavors.isArray(), "flavors 必须是数组：" + flavors);
        assertEquals(0, flavors.size());
    }

    @Test
    void reportsDishNotFoundAsABusinessError() throws Exception {
        when(dishMapper.getById(DISH_ID)).thenReturn(null);

        JsonNode response = body(call("/admin/dish/" + DISH_ID));

        assertEquals(0, response.path("code").asInt());
        assertEquals("菜品不存在", response.path("msg").asText());
        //菜品都没有，就不该再去查口味
        verify(dishFlavorMapper, never()).getByDishId(anyLong());
    }

    /**
     * {@code @GetMapping("/{id}")} 不能抢走 {@code /page}。
     * <p>
     * Spring 的路径匹配里字面量优先于变量，{@code /admin/dish/page} 仍然进 page 方法 ——
     * 但这一条是"靠匹配规则成立"的，谁把 {@code {id}} 改成约束更宽/更窄的写法（或反过来给 page 加前缀）
     * 都可能悄悄改掉它，而列表页会立刻 404/500。所以要有测试守住。
     * <p>
     * 同时断言 {@code dishMapper.getById} 一次都没被调用：这才是"没被抢走"的直接证据，
     * 只看 HTTP 200 是不够的（page 方法本身也可能被调错参数后照样返回 200）。
     */
    @Test
    void keepsTheLiteralPageRouteOnThePageMethod() throws Exception {
        when(dishMapper.pageQuery(any(DishPageQueryDTO.class))).thenAnswer(invocation -> {
            Page<DishVO> page = new Page<>(1, 10);
            page.setTotal(1L);
            page.add(DishVO.builder().id(DISH_ID).name("草鱼2斤").categoryId(CATEGORY_ID)
                    .price(new BigDecimal("38.00")).image("/uploads/2026/09/23/a.png")
                    .status(1).categoryName("蜀味烤鱼")
                    .updateTime(LocalDateTime.of(2026, 9, 22, 11, 0, 0)).build());
            return page;
        });

        MvcResult result = mvc.perform(get("/admin/dish/page")
                        .header("token", token())
                        .param("page", "1").param("pageSize", "10"))
                .andReturn();
        JsonNode response = body(result);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(1, response.path("code").asInt());
        assertEquals(1L, response.path("data").path("total").asLong());
        assertEquals("草鱼2斤", response.path("data").path("records").get(0).path("name").asText());
        //列表页照常带出分类名（它走的是 left join 那条 SQL，不是回显接口）
        assertEquals("蜀味烤鱼", response.path("data").path("records").get(0).path("categoryName").asText());

        verify(dishMapper).pageQuery(any(DishPageQueryDTO.class));
        verify(dishMapper, never()).getById(anyLong());
        verifyNoInteractions(dishFlavorMapper);
        verifyNoInteractions(localFileUtil);
    }

    /**
     * 非数字 id 的实际行为：HTTP 400 + <b>空响应体</b>，不是本项目的 {@code {"code":0,...}} 结构。
     * <p>
     * {@code String "abc" → Long} 的转换在进入 Controller 方法之前就失败了（Spring 抛
     * MethodArgumentTypeMismatchException），由默认的 DefaultHandlerExceptionResolver 处理成 400，
     * GlobalExceptionHandler 里没有对应分支。这与第 13 节记录的 {@code ?page=abc} 是同一类情况，
     * 属于项目现状而不是本接口特有的问题，因此这里如实记录、不假装它返回了业务错误。
     * <p>
     * 真要改的话是全局口径的事（给 GlobalExceptionHandler 加一条 MethodArgumentTypeMismatchException
     * 分支），不该由菜品的某一个接口自己决定。这里断言的是"没有把非法输入当成一次正常查询去查库"。
     */
    @Test
    void rejectsANonNumericIdAsASpringLevelBadRequest() throws Exception {
        MvcResult result = call("/admin/dish/abc");

        assertEquals(400, result.getResponse().getStatus());
        assertEquals("", result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "非数字 id 走的是 Spring 默认 400 空 body，不是项目的 Result 结构");
        assertTrue(result.getResolvedException() instanceof MethodArgumentTypeMismatchException,
                "预期是路径变量类型转换失败：" + result.getResolvedException());
        //参数转换失败就没进方法体，一次库都没查
        verifyNoInteractions(dishMapper, dishFlavorMapper, localFileUtil);
    }

    @Test
    void requiresAuthenticationAndTouchesNothing() throws Exception {
        MvcResult anonymous = call("/admin/dish/" + DISH_ID, null);
        assertEquals(401, anonymous.getResponse().getStatus());
        assertEquals(0, body(anonymous).path("code").asInt());

        MvcResult invalid = call("/admin/dish/" + DISH_ID, "not-a-jwt");
        assertEquals(401, invalid.getResponse().getStatus());
        assertEquals(0, body(invalid).path("code").asInt());

        verifyNoInteractions(dishMapper, dishFlavorMapper, localFileUtil);
    }
}
