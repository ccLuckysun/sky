package com.sky;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.context.BaseContext;
import com.sky.controller.admin.DishController;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.entity.Employee;
import com.sky.handler.GlobalExceptionHandler;
import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.json.JacksonObjectMapper;
import com.sky.mapper.CategoryMapper;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.EmployeeMapper;
import com.sky.properties.JwtProperties;
import com.sky.service.impl.DishServiceImpl;
import com.sky.utils.JwtUtil;
import com.sky.utils.LocalFileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 新增菜品：POST /admin/dish。
 * <p>
 * 这里用带 JacksonObjectMapper 的转换器、并发送前端真实形状的请求体（price 是字符串、
 * flavors[].value 是 JSON 字符串、多一个 DTO 里没有的 code 字段），
 * 就是为了保证接口对前端实际发出来的东西是通的，而不是只对测试里手工拼的理想 JSON 通。
 */
class DishSaveTest {

    private static final String SECRET = "dish-save-test-signing-secret";
    private static final long OPERATOR_ID = 7L;
    /** 模拟数据库回填的主键 */
    private static final long NEW_DISH_ID = 1024L;
    private static final long CATEGORY_ID = 11L;
    /** 测试里统一使用的本地上传路径，对应 uploadRoot 下一个真实存在的文件 */
    private static final String IMAGE = "/uploads/2026/09/23/a.png";

    private DishMapper dishMapper;
    private DishFlavorMapper dishFlavorMapper;
    private CategoryMapper categoryMapper;
    private EmployeeMapper employeeMapper;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path uploadRoot;

    @BeforeEach
    void setUp() throws IOException {
        dishMapper = mock(DishMapper.class);
        dishFlavorMapper = mock(DishFlavorMapper.class);
        categoryMapper = mock(CategoryMapper.class);
        employeeMapper = mock(EmployeeMapper.class);

        //上传工具用真实的实现配临时目录，只 mock mapper：图片的存不存在、删没删、路径越没越界
        //都是被测逻辑本身，mock 掉就白测了
        LocalFileUtil localFileUtil = new LocalFileUtil(uploadRoot);
        createStoredFile(IMAGE);

        DishServiceImpl service = new DishServiceImpl();
        ReflectionTestUtils.setField(service, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(service, "dishFlavorMapper", dishFlavorMapper);
        ReflectionTestUtils.setField(service, "categoryMapper", categoryMapper);
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
                //用真实转换器：未知字段的容忍度、LocalDateTime 的格式都由它决定
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new JacksonObjectMapper()))
                .build();

        when(categoryMapper.countById(anyLong())).thenReturn(1);
        when(employeeMapper.getById(OPERATOR_ID))
                .thenReturn(Employee.builder().id(OPERATOR_ID).username("admin").status(1).build());
    }

    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
    }

    private String token() {
        return JwtUtil.createJWT(SECRET, 7200000, Map.of("empId", OPERATOR_ID));
    }

    /** 新增成功时让 mapper 回填主键，模拟 useGeneratedKeys。 */
    private void stubInsertReturnsGeneratedId() {
        when(dishMapper.insert(any(Dish.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Dish.class).setId(NEW_DISH_ID);
            return 1;
        });
    }

    /** 前端 addDish 实际会发上来的 body 形状。 */
    private String payload(String overrides) {
        String base = "{\"name\":\"测试菜品\",\"categoryId\":" + CATEGORY_ID + ",\"price\":\"12.50\","
                + "\"image\":\"" + IMAGE + "\",\"description\":\"描述\",\"status\":0,"
                + "\"flavors\":[{\"name\":\"甜味\",\"value\":\"[\\\"无糖\\\",\\\"多糖\\\"]\"},"
                + "{\"name\":\"温度\",\"value\":\"[\\\"常温\\\"]\"}],"
                //前端多带一个 DTO 里没有的字段，靠 FAIL_ON_UNKNOWN_PROPERTIES=false 兜住
                + "\"code\":\"SP001\"}";
        return overrides == null ? base : overrides;
    }

    /** 在临时上传目录里创建 url 对应的真实文件（新增接口会校验本地图片是否存在）。 */
    private void createStoredFile(String url) throws IOException {
        Path file = onDisk(url);
        Files.createDirectories(file.getParent());
        Files.write(file, "png".getBytes(StandardCharsets.UTF_8));
    }

    private Path onDisk(String url) {
        return uploadRoot.resolve(url.substring(LocalFileUtil.URL_PREFIX.length() + 1));
    }

    /**
     * 把测试用的图片重新放回临时目录再提交。
     * 一次失败的新增会把它引用的图删掉（这正是本次要做的补偿），所以同一个用例里连续提交多次时，
     * 后几次得先补一个文件，否则会因为"图片文件不存在"提前失败，验不到本来要验的规则。
     */
    private MvcResult imageBackInPlaceThenSave(String body) throws Exception {
        createStoredFile(IMAGE);
        return save(body);
    }

    private MvcResult save(String body) throws Exception {
        return mvc.perform(post("/admin/dish")
                        .header("token", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return this.json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Dish captureInsertedDish() {
        ArgumentCaptor<Dish> captor = ArgumentCaptor.forClass(Dish.class);
        verify(dishMapper).insert(captor.capture());
        return captor.getValue();
    }

    private void assertNothingWasWritten() {
        verify(dishMapper, never()).insert(any(Dish.class));
        verify(dishFlavorMapper, never()).insertBatch(any());
    }

    @Test
    void storesDishAndRewritesEveryFlavorToTheGeneratedId() throws Exception {
        stubInsertReturnsGeneratedId();

        JsonNode response = json(save(payload("{\"name\":\"测试菜品\",\"categoryId\":11,\"price\":\"12.50\","
                + "\"image\":\"/uploads/2026/09/23/a.png\",\"description\":\"描述\",\"status\":0,"
                + "\"id\":9999,\"code\":\"SP001\","
                + "\"flavors\":[{\"id\":777,\"dishId\":888,\"name\":\"甜味\",\"value\":\"[\\\"无糖\\\",\\\"多糖\\\"]\"},"
                + "{\"name\":\"温度\",\"value\":\"[\\\"常温\\\"]\"}]}")));

        assertEquals(1, response.path("code").asInt(), "带 code/dishId/id 字段的请求体应当被接受");

        Dish dish = captureInsertedDish();
        assertEquals("测试菜品", dish.getName());
        assertEquals(CATEGORY_ID, dish.getCategoryId());
        assertEquals(0, new BigDecimal("12.50").compareTo(dish.getPrice()), "price 从字符串反序列化而来");
        assertEquals("/uploads/2026/09/23/a.png", dish.getImage());
        assertEquals("描述", dish.getDescription());
        assertEquals(0, dish.getStatus());
        assertEquals(NEW_DISH_ID, dish.getId(), "客户端传的 id=9999 不能被采纳，主键只认数据库回填的值");

        //业务层不再写审计列，它们由 AutoFillAspect 依据 @AutoFill(INSERT) 填充
        assertNull(dish.getCreateTime());
        assertNull(dish.getUpdateTime());
        assertNull(dish.getCreateUser());
        assertNull(dish.getUpdateUser());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DishFlavor>> captor = ArgumentCaptor.forClass(List.class);
        verify(dishFlavorMapper).insertBatch(captor.capture());
        List<DishFlavor> flavors = captor.getValue();
        assertEquals(2, flavors.size());
        for (DishFlavor flavor : flavors) {
            assertEquals(NEW_DISH_ID, flavor.getDishId(), "口味必须挂到回填出来的新菜品id上");
            assertNull(flavor.getId(), "口味主键同样由数据库生成，客户端传的 id/dishId 一律不采纳");
        }
        assertEquals("甜味", flavors.get(0).getName());
        assertEquals("[\"无糖\",\"多糖\"]", flavors.get(0).getValue(), "value 是前端 JSON.stringify 出来的字符串，原样存");
        assertEquals("温度", flavors.get(1).getName());
    }

    @Test
    void defaultsToStoppedWhenStatusIsAbsent() throws Exception {
        stubInsertReturnsGeneratedId();

        //接口文档把 status 标为非必须。缺省必须显式写 0：写 null 会绕过 dish.status 的列默认值
        JsonNode response = json(save(payload("{\"name\":\"测试菜品\",\"categoryId\":11,\"price\":\"1.00\","
                + "\"image\":\"" + IMAGE + "\"}")));

        assertEquals(1, response.path("code").asInt());
        assertEquals(0, captureInsertedDish().getStatus());
        verify(dishFlavorMapper, never()).insertBatch(any());
    }

    @Test
    void failsWhenTheGeneratedKeyIsNotReturned() throws Exception {
        //useGeneratedKeys 没把主键回填回来时不能继续往下写口味：dish_id 非空，
        //硬写只会让数据库抛约束错误，不如在这里明确失败（XML 与 keyProperty 写错时就是这个症状）。
        when(dishMapper.insert(any(Dish.class))).thenReturn(1);

        JsonNode response = json(save(payload(null)));

        assertEquals(0, response.path("code").asInt());
        assertEquals("新增菜品失败", response.path("msg").asText());
        verify(dishFlavorMapper, never()).insertBatch(any());
    }

    @Test
    void keepsExplicitlyOnSaleStatus() throws Exception {
        stubInsertReturnsGeneratedId();

        assertEquals(1, json(save(payload("{\"name\":\"测试菜品\",\"categoryId\":11,\"price\":\"1.00\","
                + "\"image\":\"" + IMAGE + "\",\"status\":1}"))).path("code").asInt());
        assertEquals(1, captureInsertedDish().getStatus());
    }

    @Test
    void rejectsBlankName() throws Exception {
        JsonNode response = json(save(payload("{\"name\":\"  \",\"categoryId\":11,\"price\":\"1.00\","
                + "\"image\":\"" + IMAGE + "\"}")));

        assertEquals(0, response.path("code").asInt());
        assertEquals("菜品名称不能为空", response.path("msg").asText());
        assertNothingWasWritten();
    }

    @Test
    void rejectsNameLongerThanTheColumnWidth() throws Exception {
        String tooLong = "菜".repeat(33);
        JsonNode response = json(save(payload("{\"name\":\"" + tooLong + "\",\"categoryId\":11,\"price\":\"1.00\","
                + "\"image\":\"" + IMAGE + "\"}")));

        assertEquals(0, response.path("code").asInt());
        assertEquals("菜品名称不能超过32个字符", response.path("msg").asText());
        assertNothingWasWritten();
    }

    @Test
    void rejectsMissingCategory() throws Exception {
        JsonNode response = json(save(payload("{\"name\":\"测试菜品\",\"price\":\"1.00\","
                + "\"image\":\"" + IMAGE + "\"}")));

        assertEquals(0, response.path("code").asInt());
        assertEquals("菜品分类不能为空", response.path("msg").asText());
        assertNothingWasWritten();
    }

    @Test
    void rejectsCategoryThatDoesNotExist() throws Exception {
        when(categoryMapper.countById(anyLong())).thenReturn(0);

        //两表之间没有外键，不查就会写出一条在任何分类列表里都查不到的菜品
        JsonNode response = json(save(payload(null)));

        assertEquals(0, response.path("code").asInt());
        assertEquals("分类不存在", response.path("msg").asText());
        assertNothingWasWritten();
    }

    @Test
    void rejectsNonPositivePrice() throws Exception {
        JsonNode response = json(save(payload("{\"name\":\"测试菜品\",\"categoryId\":11,\"price\":\"0\","
                + "\"image\":\"" + IMAGE + "\"}")));

        assertEquals(0, response.path("code").asInt());
        assertEquals("菜品价格必须大于0", response.path("msg").asText());
        assertNothingWasWritten();
    }

    @Test
    void rejectsPriceWithMoreThanTwoDecimals() throws Exception {
        JsonNode response = json(save(payload("{\"name\":\"测试菜品\",\"categoryId\":11,\"price\":\"1.234\","
                + "\"image\":\"" + IMAGE + "\"}")));

        assertEquals(0, response.path("code").asInt());
        assertEquals("菜品价格最多保留两位小数", response.path("msg").asText());
        assertNothingWasWritten();
    }

    @Test
    void acceptsTrailingZerosInPrice() throws Exception {
        stubInsertReturnsGeneratedId();

        //12.500 在 decimal(10,2) 里就是 12.50，不该按"三位小数"拒绝
        assertEquals(1, json(save(payload("{\"name\":\"测试菜品\",\"categoryId\":11,\"price\":\"12.500\","
                + "\"image\":\"" + IMAGE + "\"}"))).path("code").asInt());
        assertEquals(0, new BigDecimal("12.5").compareTo(captureInsertedDish().getPrice()));
    }

    @Test
    void rejectsMissingImageAndIllegalStatus() throws Exception {
        JsonNode noImage = json(save(payload("{\"name\":\"测试菜品\",\"categoryId\":11,\"price\":\"1.00\"}")));
        assertEquals(0, noImage.path("code").asInt());
        assertEquals("菜品图片不能为空", noImage.path("msg").asText());

        JsonNode badStatus = json(save(payload("{\"name\":\"测试菜品\",\"categoryId\":11,\"price\":\"1.00\","
                + "\"image\":\"" + IMAGE + "\",\"status\":2}")));
        assertEquals(0, badStatus.path("code").asInt());
        assertEquals("菜品状态值不合法，只能为1(起售)或0(停售)", badStatus.path("msg").asText());

        assertNothingWasWritten();
    }

    @Test
    void validatesFlavorsBeforeWritingTheDish() throws Exception {
        //口味不合法时，菜品也不该落库：校验必须在 insert 之前全部跑完
        //这是前端"加了口味却没选名字"时真实发出来的 body（addFlavore 推入的是 {name:'', value:[]}，
        //表单 rules 里并没有覆盖 flavors）。前端会让你这么提交，接口这里必须挡住。
        JsonNode blankFlavorName = json(imageBackInPlaceThenSave("{\"name\":\"测试菜品\",\"categoryId\":11,\"price\":\"1.00\","
                + "\"image\":\"" + IMAGE + "\",\"flavors\":[{\"name\":\"\",\"value\":\"[]\"}]}"));
        assertEquals(0, blankFlavorName.path("code").asInt());
        assertEquals("口味名称不能为空", blankFlavorName.path("msg").asText());

        JsonNode missingValue = json(imageBackInPlaceThenSave("{\"name\":\"测试菜品\",\"categoryId\":11,\"price\":\"1.00\","
                + "\"image\":\"" + IMAGE + "\",\"flavors\":[{\"name\":\"温度\"}]}"));
        assertEquals(0, missingValue.path("code").asInt());
        assertEquals("口味值不能为空", missingValue.path("msg").asText());

        JsonNode emptyFlavor = json(imageBackInPlaceThenSave("{\"name\":\"测试菜品\",\"categoryId\":11,\"price\":\"1.00\","
                + "\"image\":\"" + IMAGE + "\",\"flavors\":[null]}"));
        assertEquals(0, emptyFlavor.path("code").asInt());
        assertEquals("口味数据不能为空", emptyFlavor.path("msg").asText(), "JSON 里的 null 元素不能变成 500");

        assertNothingWasWritten();
    }

    @Test
    void reportsDuplicateDishNameAsBusinessError() throws Exception {
        when(dishMapper.insert(any(Dish.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry '测试菜品' for key 'dish.idx_dish_name'"));

        JsonNode response = json(save(payload(null)));

        assertEquals(0, response.path("code").asInt());
        assertEquals("菜品名称已存在", response.path("msg").asText());
        //菜品没进库，口味就不该写：否则会留下挂着不存在菜品的孤行
        verify(dishFlavorMapper, never()).insertBatch(any());
    }

    @Test
    void keepsTheUploadedImageWhenTheDishIsSaved() throws Exception {
        stubInsertReturnsGeneratedId();

        assertEquals(1, json(save(payload(null))).path("code").asInt());

        //提交成功绝不能删图：删了的话刚建出来的菜品立刻挂着一张不存在的图
        assertTrue(Files.exists(onDisk(IMAGE)), "新增成功时不能删除图片：" + onDisk(IMAGE));
        verify(dishMapper, never()).countByImage(anyString());
    }

    @Test
    void deletesTheUploadedImageWhenTheDishAddFails() throws Exception {
        when(dishMapper.insert(any(Dish.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry '测试菜品' for key 'dish.idx_dish_name'"));

        assertEquals(0, json(save(payload(null))).path("code").asInt());

        //两个接口是两个请求，上传的图不在数据库事务里，所以新增失败要补偿删除，
        //否则磁盘上留下一张永远没人用的图
        assertFalse(Files.exists(onDisk(IMAGE)), "新增失败后本次引用的图片必须一并回滚：" + onDisk(IMAGE));
    }

    @Test
    void deletesTheUploadedImageWhenValidationFails() throws Exception {
        //校验也走同一条补偿路径：图已经传上来了，菜品却没建，它就是孤儿
        json(save(payload("{\"name\":\"  \",\"categoryId\":11,\"price\":\"1.00\",\"image\":\"" + IMAGE + "\"}")));

        assertNothingWasWritten();
        assertFalse(Files.exists(onDisk(IMAGE)), "校验失败后本次引用的图片同样要删掉：" + onDisk(IMAGE));
    }

    @Test
    void keepsAnImageThatAnotherDishStillReferences() throws Exception {
        //客户端可以提交一个别的菜品已经在用的 image 路径（只判字符串，判不出是不是自己传的），
        //这次又因为重名回滚；若照删不误，那道菜品的图片就凭空消失了
        when(dishMapper.countByImage(IMAGE)).thenReturn(1);
        when(dishMapper.insert(any(Dish.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry '测试菜品' for key 'dish.idx_dish_name'"));

        assertEquals(0, json(save(payload(null))).path("code").asInt());

        assertTrue(Files.exists(onDisk(IMAGE)), "还被别的菜品引用着的图片不能删：" + onDisk(IMAGE));
    }

    @Test
    void rejectsALocalImageThatIsNotOnDisk() throws Exception {
        String missing = "/uploads/2026/09/23/never-uploaded.png";

        JsonNode response = json(save(payload("{\"name\":\"测试菜品\",\"categoryId\":11,\"price\":\"1.00\","
                + "\"image\":\"" + missing + "\"}")));

        //"新增失败会把图删掉"意味着客户端手里的路径可能已经失效。这条校验挡住的就是
        //"拿一个已被删掉的路径重提交"，否则会写出一条挂着不存在图片的菜品——比孤儿文件更难发现
        assertEquals(0, response.path("code").asInt());
        assertEquals("图片文件不存在，请重新上传", response.path("msg").asText());
        assertNothingWasWritten();
    }

    @Test
    void acceptsLegacyOssUrlAndNeverTreatsItAsAFile() throws Exception {
        //库里存量数据是阿里云 OSS 的绝对地址。它既不能因为"本地找不到"被拒，
        //也不能在回滚时被当成文件路径去删
        String oss = "https://sky-itcast.oss-cn-hangzhou.aliyuncs.com/legacy.png";
        when(dishMapper.insert(any(Dish.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry '测试菜品' for key 'dish.idx_dish_name'"));

        JsonNode response = json(save(payload("{\"name\":\"测试菜品\",\"categoryId\":11,\"price\":\"1.00\","
                + "\"image\":\"" + oss + "\"}")));

        //走到"菜品名称已存在"说明图片校验放行了；不用再单独断言它没被拒绝
        assertEquals(0, response.path("code").asInt());
        assertEquals("菜品名称已存在", response.path("msg").asText());
        verify(dishMapper, never()).countByImage(anyString());
    }

    @Test
    void requiresAuthentication() throws Exception {
        MvcResult anonymous = mvc.perform(post("/admin/dish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(null).getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertNothingWasWritten();
        assertTrue(anonymous.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("用户未登录"));
    }
}
