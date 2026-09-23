package com.sky;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.context.BaseContext;
import com.sky.controller.admin.DishController;
import com.sky.entity.Employee;
import com.sky.handler.GlobalExceptionHandler;
import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.json.JacksonObjectMapper;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批量删除菜品：DELETE /admin/dish?ids=1,2,3。
 * <p>
 * 前端契约（从已构建 bundle 的 source map 核对）：{@code deleteDish(ids)} 把 ids 拼成逗号分隔的字符串
 * 放在查询串上，单条删除走同一个接口、值是单个 id，调用方只判 {@code res.code === 1}。
 * <p>
 * 用 Mock 替换四个 mapper，重点覆盖三件 mock 之外证明不了的事：解析在任何一次 mapper 调用之前完成、
 * 两条守卫的固定顺序、以及删子表早于删主表。
 */
class DishDeleteTest {

    private static final String SECRET = "dish-delete-test-signing-secret";
    private static final long OPERATOR_ID = 7L;

    private DishMapper dishMapper;
    private DishFlavorMapper dishFlavorMapper;
    private SetmealMapper setmealMapper;
    private LocalFileUtil localFileUtil;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        dishMapper = mock(DishMapper.class);
        dishFlavorMapper = mock(DishFlavorMapper.class);
        setmealMapper = mock(SetmealMapper.class);
        //删除接口不该碰文件系统（不删图片）。用 mock 才能断言"一次都没调用过"。
        localFileUtil = mock(LocalFileUtil.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        when(employeeMapper.getById(OPERATOR_ID))
                .thenReturn(Employee.builder().id(OPERATOR_ID).username("admin").status(1).build());

        DishServiceImpl service = new DishServiceImpl();
        ReflectionTestUtils.setField(service, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(service, "dishFlavorMapper", dishFlavorMapper);
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
                //用真实转换器：Result 的字段名与 null 值的输出都由它决定
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

    /** 按前端实际请求方式发起调用：ids 放在查询串上；ids 为 null 时整个参数不出现。 */
    private MvcResult call(String ids, String token) throws Exception {
        var request = delete("/admin/dish");
        if (token != null) {
            request.header("token", token);
        }
        if (ids != null) {
            request.param("ids", ids);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult call(String ids) throws Exception {
        return call(ids, token());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 前置条件：两条守卫都没命中（库里没有起售、也没有被套餐引用的菜品）。 */
    private void stubNoGuardHit() {
        when(dishMapper.countOnSaleByIds(any())).thenReturn(0);
        when(setmealMapper.countByDishIds(any())).thenReturn(0);
    }

    /** 删除路径不该碰任何一个 mapper 方法：解析阶段就整体拒绝了。 */
    private void assertNoMapperWasCalled() {
        verifyNoInteractions(dishMapper, dishFlavorMapper, setmealMapper);
    }

    @Test
    void deletesTheRequestedDishesWithTheParsedIdsAndReturnsSuccess() throws Exception {
        stubNoGuardHit();

        MvcResult result = call("1,2");
        JsonNode response = body(result);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(1, response.path("code").asInt());
        //接口文档把 data 标为非必须、前端只判 code，所以返回空的 Result（与 POST /admin/dish 同一取舍）
        assertTrue(response.path("data").isNull(), "成功时不该返回 data：" + response.path("data"));
        assertNull(response.path("msg").asText(null));

        //四个 mapper 方法各被调用一次，参数是解析后的 id 列表
        List<Long> expected = List.of(1L, 2L);
        verify(dishMapper).countOnSaleByIds(expected);
        verify(setmealMapper).countByDishIds(expected);
        verify(dishFlavorMapper).deleteByDishIds(expected);
        verify(dishMapper).deleteByIds(expected);
    }

    @Test
    void deletesFlavorsBeforeTheDish() throws Exception {
        stubNoGuardHit();

        assertEquals(1, body(call("1,2")).path("code").asInt());

        //两张表没有外键。先删 dish_flavor：中途失败（数据库断开、约束、进程被杀）
        //留下的是"菜品还在、口味没了"，而不是一批指向不存在菜品的口味行。
        InOrder order = inOrder(dishFlavorMapper, dishMapper);
        order.verify(dishFlavorMapper).deleteByDishIds(List.of(1L, 2L));
        order.verify(dishMapper).deleteByIds(List.of(1L, 2L));
    }

    @Test
    void deduplicatesRepeatedIdsAndKeepsTheOrderOfFirstAppearance() throws Exception {
        stubNoGuardHit();

        assertEquals(1, body(call("1,1,2,2,1")).path("code").asInt());

        //前端批量删除会把所有勾选项 join 起来，重复勾选或点击两次都可能造出重复 id；
        //去重后下发给 SQL 的 in 列表不应有重复项，且顺序与首次出现的顺序一致。
        List<Long> expected = List.of(1L, 2L);
        verify(dishMapper).countOnSaleByIds(expected);
        verify(dishFlavorMapper).deleteByDishIds(expected);
        verify(dishMapper).deleteByIds(expected);
    }

    static Stream<Arguments> blankIds() {
        return Stream.of(
                Arguments.of(null, "菜品id不能为空"),
                Arguments.of("", "菜品id不能为空"),
                Arguments.of("   ", "菜品id不能为空"));
    }

    @ParameterizedTest(name = "ids=[{0}]")
    @MethodSource("blankIds")
    void rejectsMissingOrBlankIds(String ids, String expectedMessage) throws Exception {
        JsonNode response = body(call(ids));

        //Controller 用 @RequestParam(required = false) 而不是让 Spring 直接 400：
        //缺参也要走同一条业务提示（与 EmployeeController.startOrStop 同一风格）。
        assertEquals(0, response.path("code").asInt());
        assertEquals(expectedMessage, response.path("msg").asText());
        assertNoMapperWasCalled();
    }

    static Stream<Arguments> malformedIds() {
        String formatError = "菜品id格式错误";
        return Stream.of(
                //空 token：连续分隔符、以及末尾分隔符切出来的空串（split 默认会丢掉末尾空串，必须用 limit -1 才拦得住）
                Arguments.of("1,,2", formatError),
                Arguments.of("1,2,", formatError),
                Arguments.of(",1", formatError),
                //非十进制数字
                Arguments.of("abc", formatError),
                Arguments.of("1,abc", formatError),
                Arguments.of("1.5", formatError),
                Arguments.of("1;2", formatError),
                //非正数
                Arguments.of("0", formatError),
                Arguments.of("-1", formatError),
                //超出 long 范围：不能让它冒成 500
                Arguments.of("99999999999999999999", formatError));
    }

    @ParameterizedTest(name = "ids=[{0}]")
    @MethodSource("malformedIds")
    void rejectsMalformedIdsBeforeTouchingTheDatabase(String ids, String expectedMessage) throws Exception {
        JsonNode response = body(call(ids));

        assertEquals(0, response.path("code").asInt());
        assertEquals(expectedMessage, response.path("msg").asText());
        //"整体拒绝、不做部分删除"：连查询类的 countOnSaleByIds 都没被调用过，
        //说明 id 串是全部解析完才允许碰数据库的，不存在"删掉合法的那些再报错"。
        assertNoMapperWasCalled();
    }

    @Test
    void refusesToDeleteWhenAnyDishIsOnSale() throws Exception {
        when(dishMapper.countOnSaleByIds(any())).thenReturn(1);

        JsonNode response = body(call("1,2"));

        assertEquals(0, response.path("code").asInt());
        assertEquals("起售中的菜品不能删除", response.path("msg").asText());
        verify(dishFlavorMapper, never()).deleteByDishIds(any());
        verify(dishMapper, never()).deleteByIds(any());
        //守卫一命中就直接返回，不会再问套餐那一侧
        verify(setmealMapper, never()).countByDishIds(any());
    }

    @Test
    void refusesToDeleteWhenADishIsRelatedByASetmeal() throws Exception {
        when(dishMapper.countOnSaleByIds(any())).thenReturn(0);
        when(setmealMapper.countByDishIds(any())).thenReturn(1);

        JsonNode response = body(call("1,2"));

        assertEquals(0, response.path("code").asInt());
        assertEquals("当前菜品关联了套餐,不能删除", response.path("msg").asText());
        verify(dishFlavorMapper, never()).deleteByDishIds(any());
        verify(dishMapper, never()).deleteByIds(any());
    }

    @Test
    void reportsTheOnSaleGuardWhenBothGuardsWouldBlock() throws Exception {
        //两道拦截同时成立时报的是"起售中的菜品不能删除"：守卫顺序是固定的，
        //反过来（先问套餐）会让同一批菜品报出另一个原因，前端提示对不上。
        when(dishMapper.countOnSaleByIds(any())).thenReturn(1);
        when(setmealMapper.countByDishIds(any())).thenReturn(1);

        JsonNode response = body(call("1,2"));

        assertEquals(0, response.path("code").asInt());
        assertEquals("起售中的菜品不能删除", response.path("msg").asText());
        verify(dishFlavorMapper, never()).deleteByDishIds(any());
        verify(dishMapper, never()).deleteByIds(any());
    }

    @Test
    void treatsAnUnknownDishAsANoOp() throws Exception {
        stubNoGuardHit();

        //id 在库里不存在（重复点删除、或已经被别人删掉）不该报错：守卫用的是 count(*)，
        //不存在的 id 自然不计入，删除语句影响 0 行也不是失败。
        JsonNode response = body(call("999999"));

        assertEquals(1, response.path("code").asInt());
        //守卫照常执行（不存在的那条不计入 count），删除语句收到的是这个不存在的 id，且不校验影响行数
        verify(dishMapper).countOnSaleByIds(List.of(999999L));
        verify(dishMapper).deleteByIds(List.of(999999L));
    }

    @Test
    void neverDeletesUploadedImageFiles() throws Exception {
        stubNoGuardHit();

        assertEquals(1, body(call("1,2")).path("code").asInt());

        //有意不删图片：库里存量图片是 OSS 绝对地址，删文件不可逆，而"还有没有人在用"的判据
        //目前只看 dish 表，误删风险大于收益。删除菜品不该碰本地文件系统。
        verifyNoInteractions(localFileUtil);
    }

    @Test
    void requiresAuthenticationAndTouchesNoMapper() throws Exception {
        MvcResult anonymous = call("1,2", null);
        assertEquals(401, anonymous.getResponse().getStatus());
        assertEquals(0, body(anonymous).path("code").asInt());

        MvcResult invalid = call("1,2", "not-a-jwt");
        assertEquals(401, invalid.getResponse().getStatus());
        assertEquals(0, body(invalid).path("code").asInt());

        assertNoMapperWasCalled();
    }
}
