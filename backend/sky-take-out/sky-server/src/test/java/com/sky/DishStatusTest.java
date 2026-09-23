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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 菜品起售、停售：POST /admin/dish/status/{status}?id=xx。
 * <p>
 * 前端契约（从已构建 bundle 的 source map 核对）：列表页那一列的按钮调
 * {@code dishStatusByStatus({id, status})}，id 是 {@code row.id}、status 是按当前状态取反后的
 * <b>字符串</b>（停售的菜显示"启售"，点下去发 {@code status=1}）。
 * <p>
 * 用 Mock 替换五个 mapper，重点覆盖几件 mock 之外证明不了的事：实体上只带 id 与 status
 * （动态 {@code <set>} 因此只写 status，改不到名称/价格/图片）、取值校验在任何一次 mapper 调用
 * 之前完成、以及这条链路完全不碰口味与文件系统。
 * <p>
 * 项目里本类只到 mock 层；{@code @AutoFill(UPDATE)} 真的把 update_time / update_user 刷进库
 * 这一条由 {@code DishStatusDatabaseTest} 用真库证明（mock 掉 mapper 就绕过了整个切面代理）。
 */
class DishStatusTest {

    private static final String SECRET = "dish-status-test-signing-secret";
    private static final long OPERATOR_ID = 7L;
    private static final long DISH_ID = 46L;

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
        //起售/停售不该碰文件系统（不删图片）。用 mock 才能断言"一次都没调用过"。
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

    /** 按前端实际请求方式发起调用：status 在路径上、id 在查询串上；id 为 null 时整个参数不出现。 */
    private MvcResult call(String status, String id, String token) throws Exception {
        var request = post("/admin/dish/status/" + status);
        if (token != null) {
            request.header("token", token);
        }
        if (id != null) {
            request.param("id", id);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult call(String status, String id) throws Exception {
        return call(status, id, token());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 库里存在这道菜（前置条件），并按给定的当前状态返回。 */
    private void stubDishExists(int currentStatus) {
        when(dishMapper.getById(DISH_ID)).thenReturn(
                Dish.builder().id(DISH_ID).name("麻婆豆腐").status(currentStatus).build());
    }

    /** 断言交给了 dishMapper.update 的实体上**只有** id 与 status 两个字段有值。 */
    private Dish capturedUpdate() {
        ArgumentCaptor<Dish> captor = ArgumentCaptor.forClass(Dish.class);
        verify(dishMapper).update(captor.capture());
        return captor.getValue();
    }

    private void assertOnlyIdAndStatusAreSet(Dish dish, long expectedId, int expectedStatus) {
        assertEquals(expectedId, dish.getId());
        assertEquals(expectedStatus, dish.getStatus());
        //其余列全部为 null：动态 <set> 只会写 status，名称/价格/图片/描述一列都改不到；
        //update_time / update_user 留给 AutoFillAspect，业务层不该自己填（createTime/createUser 更不该出现）。
        assertNull(dish.getName(), "起售停售不该带上名称");
        assertNull(dish.getCategoryId(), "起售停售不该带上分类");
        assertNull(dish.getPrice(), "起售停售不该带上价格");
        assertNull(dish.getImage(), "起售停售不该带上图片");
        assertNull(dish.getDescription(), "起售停售不该带上描述");
        assertNull(dish.getCreateTime(), "起售停售不该带创建时间");
        assertNull(dish.getCreateUser(), "起售停售不该带创建人");
        assertNull(dish.getUpdateTime(), "updateTime 应由 AutoFillAspect 填充，业务层不设置");
        assertNull(dish.getUpdateUser(), "updateUser 应由 AutoFillAspect 填充，业务层不设置");
    }

    @Test
    void switchesADishToOnSaleAndWritesOnlyIdAndStatus() throws Exception {
        stubDishExists(0);

        MvcResult result = call("1", String.valueOf(DISH_ID));
        JsonNode response = body(result);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(1, response.path("code").asInt());
        //接口文档把 data 标为非必须、前端只判 code，所以返回空的 Result（与新增/修改/删除同一取舍）
        assertTrue(response.path("data").isNull(), "成功时不该返回 data：" + response.path("data"));
        assertNull(response.path("msg").asText(null));

        assertOnlyIdAndStatusAreSet(capturedUpdate(), DISH_ID, 1);
        verify(dishMapper).getById(DISH_ID);
    }

    @Test
    void switchesADishToOffSale() throws Exception {
        //前端按当前状态取反：起售中的菜按钮显示"停售"，发出来的 status 是 '0'
        stubDishExists(1);

        assertEquals(1, body(call("0", String.valueOf(DISH_ID))).path("code").asInt());

        assertOnlyIdAndStatusAreSet(capturedUpdate(), DISH_ID, 0);
    }

    /**
     * 把状态改成它当前的值：MySQL 返回 0 行，但这不是失败。
     * <p>
     * 前端连着点两次同一个按钮就会走到这条路径（第二次的目标状态已经等于库里的值了），
     * 报错会让用户以为操作失败。与员工启停、菜品修改同一约定。
     */
    @Test
    void treatsSettingTheStatusItAlreadyHasAsSuccess() throws Exception {
        stubDishExists(1);
        when(dishMapper.update(any())).thenReturn(0);

        JsonNode response = body(call("1", String.valueOf(DISH_ID)));

        assertEquals(1, response.path("code").asInt());
        assertNull(response.path("msg").asText(null));
    }

    /**
     * status 只能是 0/1。
     * <p>
     * 直接调接口可以传任何整数，不拦就会把 2、-1 写进 status 列，菜品随后既不是起售也不是停售：
     * 列表页按 status 是否为 0 二分显示会把它显示成"启售"，而按 {@code status = 1} 过滤的查询
     * 又查不到它。校验必须在碰数据库之前完成，所以连存在性都不查。
     */
    @ParameterizedTest(name = "status={0}")
    @ValueSource(strings = {"2", "-1", "99"})
    void rejectsAStatusOtherThanZeroOrOneWithoutTouchingTheDatabase(String status) throws Exception {
        JsonNode response = body(call(status, String.valueOf(DISH_ID)));

        assertEquals(0, response.path("code").asInt());
        assertEquals("菜品状态值不合法，只能为1(起售)或0(停售)", response.path("msg").asText());
        verifyNoInteractions(dishMapper);
    }

    /**
     * 缺 id：走业务的 {@code code:0} + 中文提示，而不是 Spring 的 400。
     * <p>
     * Controller 用 {@code @RequestParam(required = false)} 收 id 就是为了这个
     * （与 {@code EmployeeController#startOrStop} 同一风格）。
     */
    @Test
    void rejectsAMissingDishIdWithAChineseMessage() throws Exception {
        JsonNode response = body(call("1", null));

        assertEquals(0, response.path("code").asInt());
        assertEquals("菜品id不能为空", response.path("msg").asText());
        verifyNoInteractions(dishMapper);
    }

    /** 主键从 1 开始，0 与负数永远不可能是库里存在的行，与员工启停同一取值的判据。 */
    @ParameterizedTest(name = "id={0}")
    @ValueSource(strings = {"0", "-1"})
    void rejectsANonPositiveIdWithoutTouchingTheDatabase(String id) throws Exception {
        JsonNode response = body(call("1", id));

        assertEquals(0, response.path("code").asInt());
        assertEquals("菜品id不能为空", response.path("msg").asText());
        verifyNoInteractions(dishMapper);
    }

    @Test
    void rejectsAnUnknownDishWithoutUpdatingAnything() throws Exception {
        //不 stub getById：返回 null 表示库里没有这道菜
        JsonNode response = body(call("1", "999999"));

        assertEquals(0, response.path("code").asInt());
        assertEquals("菜品不存在", response.path("msg").asText());
        verify(dishMapper).getById(999999L);
        verify(dishMapper, never()).update(any());
    }

    /**
     * 本接口只收**单个**菜品id：逗号分隔的多个 id 与非数字 id 一样，绑不进 Long，
     * 在进入 Controller 方法体之前就以 HTTP 400 + 空响应体失败。
     * <p>
     * 这条不是随手写的边界：前端列表页的 {@code statusHandle} 里确实有一个
     * {@code typeof row === 'string'} 的批量分支会把 {@code checkList.join(',')} 当成 id 发出来，
     * 但那个页面的工具栏只有「批量删除」和「+ 新建菜品」，没有批量启售/停售，所以该分支在本页
     * 是死代码，永远走不到这里。真要支持批量就得改契约（像 {@code DELETE /admin/dish} 那样收
     * ids 串），不是把这里的 Long 换成 String 就完事——那会让"哪些菜允许一起改状态"变成新问题。
     * <p>
     * 断言 400：这是项目现状（{@code ?page=abc} 等同类情况都是这样），GlobalExceptionHandler 里没有
     * MethodArgumentTypeMismatchException 分支，改的话是全局口径的事，不该由菜品的某一个接口自己决定。
     * <p>
     * 响应体这一层要说清：MockMvc <b>不做 ERROR 派发</b>，所以这里读到的是空串；真实 Tomcat 下同一个
     * 请求返回的是 Spring Boot {@code BasicErrorController} 的
     * {@code {"timestamp":...,"status":400,"error":"Bad Request","path":"..."}}。
     * 两种形态都不是项目的 {@code {"code":0,...}} 结构 —— 断言"空 body"只在 MockMvc 这一层成立，
     * 别把它当成线上契约。
     */
    @ParameterizedTest(name = "id={0}")
    @ValueSource(strings = {"46,47", "abc"})
    void rejectsIdsThatCannotBindToALongAsASpringLevelBadRequest(String id) throws Exception {
        MvcResult result = call("1", id);

        assertEquals(400, result.getResponse().getStatus());
        assertEquals("", result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "MockMvc 不做 ERROR 派发，所以这里是空 body；真实 Tomcat 会返回 BasicErrorController 的 JSON");
        assertTrue(result.getResolvedException() instanceof MethodArgumentTypeMismatchException,
                "预期是 id 参数类型转换失败：" + result.getResolvedException());
        verifyNoInteractions(dishMapper);
    }

    @Test
    void requiresAuthenticationAndTouchesNothing() throws Exception {
        MvcResult anonymous = call("1", String.valueOf(DISH_ID), null);
        assertEquals(401, anonymous.getResponse().getStatus());
        assertEquals(0, body(anonymous).path("code").asInt());

        MvcResult invalid = call("1", String.valueOf(DISH_ID), "not-a-jwt");
        assertEquals(401, invalid.getResponse().getStatus());
        assertEquals(0, body(invalid).path("code").asInt());

        verifyNoInteractions(dishMapper);
    }

    /**
     * 起售/停售只写 dish.status 一列：不碰口味、不查分类、不读套餐引用、更不碰磁盘上的图片。
     * <p>
     * 图片这一条值得单独守住：删除接口特意不删图（存量图是 OSS 绝对地址，删了不可逆），
     * 起售停售同理——它连 image 列都不带，回滚删图那套补偿逻辑更不该被这条路径触发。
     */
    @Test
    void neverTouchesFlavorsCategoriesSetmealsOrImages() throws Exception {
        stubDishExists(0);

        assertEquals(1, body(call("1", String.valueOf(DISH_ID))).path("code").asInt());

        verifyNoInteractions(dishFlavorMapper, categoryMapper, setmealMapper, localFileUtil);
    }
}
