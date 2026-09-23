package com.sky;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.context.BaseContext;
import com.sky.controller.admin.DishController;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Employee;
import com.sky.handler.GlobalExceptionHandler;
import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.json.JacksonObjectMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.EmployeeMapper;
import com.sky.properties.JwtProperties;
import com.sky.service.impl.DishServiceImpl;
import com.sky.utils.JwtUtil;
import com.sky.vo.DishVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 菜品分页查询：GET /admin/dish/page。
 * <p>
 * 请求参数按前端真实形状发：dishStatus 的初始值是空字符串，status 会以 ?status= 的形式出现，
 * 而 name / categoryId 为空时前端直接不传（`input || undefined`）。
 * <p>
 * 这里显式装 MappingJackson2HttpMessageConverter(new JacksonObjectMapper())：
 * standaloneSetup 不会执行 WebMvcConfiguration.extendMessageConverters，
 * 不装的话 updateTime 会被序列化成 [2026,9,22,11,0] 这样的数组，与接口文档和列表页的展示都不符。
 */
class DishPageQueryTest {

    private static final String SECRET = "dish-page-test-signing-secret";
    private static final long EMP_ID = 7L;

    private DishMapper mapper;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    /** mapper 被调用那一刻 PageHelper 里的分页参数，用于验证 startPage 确实生效过。 */
    private Page<?> pageParamsSeenByMapper;

    @BeforeEach
    void setUp() {
        mapper = mock(DishMapper.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        when(employeeMapper.getById(EMP_ID)).thenReturn(Employee.builder().id(EMP_ID).status(1).build());

        DishServiceImpl service = new DishServiceImpl();
        ReflectionTestUtils.setField(service, "dishMapper", mapper);

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
                //用真实转换器：LocalDateTime 的格式、BigDecimal 出成 JSON number 都由它决定
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new JacksonObjectMapper()))
                .build();
    }

    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
        PageHelper.clearPage();
    }

    /** 列表页表格真正读取的字段：name、image、categoryName、price（数字）、status、updateTime。 */
    private DishVO dish(long id, String name, String categoryName) {
        return DishVO.builder()
                .id(id).name(name).categoryId(16L).price(new BigDecimal("38.00"))
                .image("/uploads/2026/09/23/a.png").description("描述")
                .status(1).updateTime(LocalDateTime.of(2026, 9, 22, 11, 0, 0))
                .categoryName(categoryName).build();
    }

    private ResultActions pageQuery(Map<String, String> params) throws Exception {
        var request = get("/admin/dish/page")
                .header("token", JwtUtil.createJWT(SECRET, 60000, Map.of("empId", EMP_ID)));
        params.forEach(request::param);
        return mvc.perform(request);
    }

    private ResultActions pageQuery() throws Exception {
        return pageQuery(Map.of("page", "1", "pageSize", "10"));
    }

    /** 响应头未带 charset，MockMvc 默认按 ISO-8859-1 解码，中文断言必须显式按 UTF-8 读取。 */
    private JsonNode responseBody(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * 按 PageHelper 在真实查询中的行为构造返回值：整体是 Page（List 的子类），
     * 同时携带 count 查询得到的 total。顺手记下查询那一刻 ThreadLocal 里的分页参数。
     */
    private void stubPageQuery(long total, DishVO... records) {
        when(mapper.pageQuery(any(DishPageQueryDTO.class))).thenAnswer(invocation -> {
            pageParamsSeenByMapper = PageHelper.getLocalPage();
            Page<DishVO> page = new Page<>(1, 10);
            page.setTotal(total);
            page.addAll(Arrays.asList(records));
            return page;
        });
    }

    @Test
    void returnsTotalAndRecordsWithTheColumnsTheListViewReads() throws Exception {
        stubPageQuery(23L, dish(1L, "草鱼2斤", "蜀味烤鱼"), dish(2L, "北冰洋", "酒水饮料"));

        JsonNode response = responseBody(pageQuery(Map.of("page", "1", "pageSize", "2"))
                .andExpect(status().isOk()));

        assertEquals(1, response.path("code").asInt());
        JsonNode data = response.path("data");
        assertEquals(23L, data.path("total").asLong());
        assertEquals(2, data.path("records").size());

        for (int i = 0; i < 2; i++) {
            JsonNode record = data.path("records").get(i);
            for (String field : new String[]{"id", "name", "categoryId", "price", "image",
                    "description", "status", "updateTime", "categoryName"}) {
                assertTrue(record.has(field), "响应缺少字段：" + field);
            }
        }

        JsonNode first = data.path("records").get(0);
        assertEquals("草鱼2斤", first.path("name").asText());
        //分类名称来自 left join category，列表页的"分类"一列直接显示它
        assertEquals("蜀味烤鱼", first.path("categoryName").asText());
        assertEquals(1, first.path("status").asInt());
    }

    @Test
    void priceIsAJsonNumberAndUpdateTimeIsAFormattedString() throws Exception {
        stubPageQuery(1L, dish(1L, "草鱼2斤", "蜀味烤鱼"));

        JsonNode record = responseBody(pageQuery()).path("data").path("records").get(0);

        //列表页写的是 (row.price).toFixed(2)。price 一旦被序列化成字符串，这里就会 TypeError。
        assertTrue(record.path("price").isNumber(), "price 必须是 JSON number，实际为 " + record.path("price"));
        assertEquals(38.00, record.path("price").asDouble(), 0.001);

        //接口文档约定 updateTime 是字符串，靠 JacksonObjectMapper 序列化（WebMvcConfiguration 注册的那个转换器）。
        assertTrue(record.path("updateTime").isTextual(), "updateTime 应为字符串：" + record.path("updateTime"));
        assertEquals("2026-09-22 11:00", record.path("updateTime").asText());
    }

    @Test
    void passesPagingAndFiltersToMapperAndClearsThePageHelperThreadLocal() throws Exception {
        stubPageQuery(0L);

        pageQuery(Map.of("page", "3", "pageSize", "2", "name", " 草鱼 ", "categoryId", "16", "status", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));

        ArgumentCaptor<DishPageQueryDTO> capture = ArgumentCaptor.forClass(DishPageQueryDTO.class);
        verify(mapper).pageQuery(capture.capture());
        DishPageQueryDTO dto = capture.getValue();
        assertEquals(3, dto.getPage());
        assertEquals(2, dto.getPageSize());
        assertEquals("草鱼", dto.getName(), "两端空白应在进入 SQL 之前去掉");
        assertEquals(16, dto.getCategoryId());
        assertEquals(1, dto.getStatus());

        //查询进行中分页参数必须在 ThreadLocal 里，否则 PageHelper 追不出 limit、也取不到 total
        assertNotNull(pageParamsSeenByMapper, "查询前没有调用 PageHelper.startPage");
        assertEquals(3, pageParamsSeenByMapper.getPageNum());
        assertEquals(2, pageParamsSeenByMapper.getPageSize());
        //分页参数存放在 ThreadLocal，未清理会污染后续复用该线程的查询。
        assertNull(PageHelper.getLocalPage());
    }

    @Test
    void blankStatusSentByTheFrontendIsBoundToNullAndFiltersNothingOut() throws Exception {
        stubPageQuery(0L);

        //前端 dishStatus 的初始值是 ''，因此首次进页面发出来的是 ?status=
        pageQuery(Map.of("page", "1", "pageSize", "10", "status", "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
        pageQuery(Map.of("page", "1", "pageSize", "10", "status", "0")).andExpect(jsonPath("$.code").value(1));
        pageQuery(Map.of("page", "1", "pageSize", "10", "status", "1")).andExpect(jsonPath("$.code").value(1));

        ArgumentCaptor<DishPageQueryDTO> capture = ArgumentCaptor.forClass(DishPageQueryDTO.class);
        verify(mapper, times(3)).pageQuery(capture.capture());
        //空字符串必须绑成 null：绑成 0 会让列表页默认只显示停售菜品，绑不进 Integer 则直接 400。
        assertNull(capture.getAllValues().get(0).getStatus(), "status= 空串应当等同不筛选");
        assertEquals(0, capture.getAllValues().get(1).getStatus());
        assertEquals(1, capture.getAllValues().get(2).getStatus());
    }

    @Test
    void absentOrBlankNameMeansNoFilterAndNameIsTrimmed() throws Exception {
        stubPageQuery(0L);

        pageQuery().andExpect(jsonPath("$.code").value(1));
        pageQuery(Map.of("page", "1", "pageSize", "10", "name", "")).andExpect(jsonPath("$.code").value(1));
        pageQuery(Map.of("page", "1", "pageSize", "10", "name", " \t "))
                .andExpect(jsonPath("$.code").value(1));
        pageQuery(Map.of("page", "1", "pageSize", "10", "name", " 草鱼 "))
                .andExpect(jsonPath("$.code").value(1));

        ArgumentCaptor<DishPageQueryDTO> capture = ArgumentCaptor.forClass(DishPageQueryDTO.class);
        verify(mapper, times(4)).pageQuery(capture.capture());
        assertNull(capture.getAllValues().get(0).getName());
        assertNull(capture.getAllValues().get(1).getName());
        assertNull(capture.getAllValues().get(2).getName());
        assertEquals("草鱼", capture.getAllValues().get(3).getName());
    }

    @Test
    void emptyResultStillReturnsTotalAndEmptyRecords() throws Exception {
        stubPageQuery(0L);

        JsonNode data = responseBody(pageQuery(Map.of("page", "1", "pageSize", "10", "name", "没有这道菜")))
                .path("data");

        assertEquals(0L, data.path("total").asLong());
        assertTrue(data.path("records").isArray());
        assertEquals(0, data.path("records").size());
    }

    @Test
    void rejectsMissingZeroOrNegativePagingParametersBeforeQuerying() throws Exception {
        Map<String, String> missingPage = Map.of("pageSize", "10");
        Map<String, String> missingPageSize = Map.of("page", "1");
        Map<String, String>[] invalid = new Map[]{
                Map.of(), missingPage, missingPageSize,
                Map.of("page", "0", "pageSize", "10"),
                Map.of("page", "1", "pageSize", "0"),
                Map.of("page", "-1", "pageSize", "10"),
                Map.of("page", "1", "pageSize", "-5")};

        for (Map<String, String> params : invalid) {
            JsonNode response = responseBody(pageQuery(params).andExpect(status().isOk()));
            assertEquals(0, response.path("code").asInt(), params + " 应被拒绝");
            assertEquals("页码和每页记录数必须为正整数", response.path("msg").asText());
            assertTrue(response.path("data").isNull());
        }
        verify(mapper, never()).pageQuery(any());
        assertNull(PageHelper.getLocalPage());
    }

    @Test
    void unauthenticatedOrInvalidTokenDoesNotQueryDatabase() throws Exception {
        mvc.perform(get("/admin/dish/page").param("page", "1").param("pageSize", "10"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/admin/dish/page").param("page", "1").param("pageSize", "10")
                        .header("token", "not-a-jwt"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(0));
        verify(mapper, never()).pageQuery(any());
        assertNull(BaseContext.getCurrentId());
    }
}
