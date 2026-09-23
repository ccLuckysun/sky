package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.exception.BaseException;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.exception.UserNotLoginException;
import com.sky.mapper.CategoryMapper;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.result.PageResult;
import com.sky.service.DishService;
import com.sky.utils.LocalFileUtil;
import com.sky.vo.DishVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 菜品业务层
 */
@Service
@Slf4j
public class DishServiceImpl implements DishService {

    /** dish.name 与 dish_flavor.name 的列宽 */
    private static final int NAME_MAX_LENGTH = 32;
    /** dish.image、dish.description 与 dish_flavor.value 的列宽 */
    private static final int TEXT_MAX_LENGTH = 255;
    /** dish.price 是 decimal(10,2)，这是它放得下的最大值 */
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");
    /** dish.price 的小数位数 */
    private static final int PRICE_SCALE = 2;
    /** 合法的菜品id：只允许 ASCII 十进制数字，不接受 '+'、空白、小数点、分号等任何写法 */
    private static final Pattern DISH_ID_PATTERN = Pattern.compile("\\d+");

    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private DishFlavorMapper dishFlavorMapper;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private LocalFileUtil localFileUtil;

    /**
     * 新增菜品及其口味
     * <p>
     * dish 与 dish_flavor 在同一个事务里，任何失败一起回滚。图片文件不在这套机制内：本地磁盘没有两阶段提交，
     * 注解式事务管不到 Files.write，所以只能补偿——事务确实回滚之后，把这次请求引用的本地图片删掉
     * （见 {@link #deleteUploadedImage}），让磁盘回到"没有这条菜品、也没有它的图"的状态。
     * 上传本身是另一个请求，它失败时一行表都还没碰，不需要补偿。
     * @param dishDTO
     */
    @Transactional
    public void saveWithFlavor(DishDTO dishDTO) {
        //与 updateWithFlavor 对称地判一次空。HTTP 层送不进 null（空请求体在 @RequestBody 那一步
        //就被拦成 400 了），但直接调用本类的代码可以，别让它在 getImage() 上冒成 NPE ——
        //那会把一句本该是业务提示的错误变成 500。
        String image = dishDTO == null ? null : dishDTO.getImage();
        boolean deferred = TransactionSynchronizationManager.isSynchronizationActive();
        if (deferred) {
            registerImageCleanup(image);
        }
        try {
            save(dishDTO);
        } catch (RuntimeException | Error ex) {
            //没有活动事务时等不到回调（单测里直接 new 出本类，或将来有人删掉了 @Transactional），
            //就地删除，不让清理逻辑静默失效。
            if (!deferred) {
                deleteUploadedImage(image);
            }
            throw ex;
        }
    }

    /**
     * 注册"事务回滚后删图"的回调。
     * <p>
     * 用 afterCompletion(STATUS_ROLLED_BACK) 而不是在 catch 里删：只有事务确实回滚了才删，
     * 提交成功时绝不能删，否则每新增一道菜就把自己的图删掉。注册点在动任何表之前，
     * 这样校验失败、分类不存在、重名、口味写入失败、数据库断开等所有失败路径都被覆盖。
     */
    private void registerImageCleanup(String image) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    deleteUploadedImage(image);
                }
            }
        });
    }

    /**
     * 清掉一张不再被引用的本地上传图片。
     * <p>
     * 两个调用场景：新增/修改**回滚**之后（第 18、21 节），以及批量删除**提交**之后（第 24 节）。
     * 只处理本地上传路径：库里存量数据混着阿里云 OSS 的绝对地址，那些不是文件路径，不能拿去删。
     * 删之前还要确认没有别的记录引用同一张图——两个场景下本次请求的行都已经不在库里了
     * （回滚时没写进去、删除时刚删掉），此时还能查到引用，说明这张图是别人在用的
     * （客户端完全可以提交一个已存在的 image 路径）。
     * <p>
     * 整个过程不抛异常：它会在事务完成回调里被调用，那里抛出去要么盖掉真正的业务错误
     * （回滚场景），要么让一次已经提交成功的删除变成 500（提交场景）。删不掉最多留下一个孤儿文件。
     * <p>
     * <b>try 必须包住整个方法体，连第一句 {@code isLocalPath} 也不能留在外面。</b>这句"不抛异常"的保证
     * 不是靠调用方兜的：Spring 的 {@code invokeAfterCompletion} 会捕获回调里的 Throwable 只记日志，
     * 但 {@code invokeAfterCommit} <b>不捕获</b>——守卫漏在 try 外，删除路径上一旦它抛（未注入的协作者、
     * 将来的实现改动），异常会冒出 {@code commit()}，把一次已经提交成功的删除变成 500；
     * 退化分支里则会顶掉原始的业务异常；循环也会中断，后面所有图片都不再清理。
     * 当前 {@code isLocalPath} 只是两次字符串判断、{@code image} 又永不为 null，所以够不到，
     * 但把守卫挪进来才算配得上这句话。
     */
    private void deleteUploadedImage(String image) {
        try {
            if (!localFileUtil.isLocalPath(image)) {
                return;
            }
            if (isImageInUse(image)) {
                log.info("图片仍被其它记录引用，不删除：{}", image);
                return;
            }
            localFileUtil.delete(image);
        } catch (Exception ex) {
            log.warn("删除上传图片失败，文件可能成为孤儿：{}", image, ex);
        }
    }

    /**
     * 这张图还有没有人在用。
     * <p>
     * 只查 {@code dish} 与 {@code setmeal}：这两处存的是"某个菜品/套餐当前使用的图"，
     * 是被同一条业务链路管起来的引用。
     * <p>
     * <b>没有查 {@code order_detail} 与 {@code shopping_cart}</b>——它们也有 image 列（已核过表结构），
     * 但存的是下单、加购那一刻**拷下来的快照**，语义完全不同：删掉文件会让历史订单里的图片全部失效。
     * 这两个模块目前没有任何读写代码（表是空的），所以现在纳进来只是空转；等它们开始写图时
     * **必须一并扩展这个判断**，否则会删掉历史订单还在用的图片。详见第 24 节。
     * <p>
     * 另外：{@code employee} 表其实**没有** image 列，前几节里"employee.image"的提法是错的。
     */
    private boolean isImageInUse(String image) {
        return dishMapper.countByImage(image) > 0 || setmealMapper.countByImage(image) > 0;
    }

    /**
     * 逐张清理，顺序无关，单张的失败不影响后面的。
     */
    private void deleteUploadedImages(List<String> images) {
        if (images == null) {
            return;
        }
        for (String image : images) {
            deleteUploadedImage(image);
        }
    }

    /**
     * 注册"事务提交后删图"的回调。
     * <p>
     * 与新增/修改那条挂在 {@code afterCompletion(STATUS_ROLLED_BACK)} 上的回调**方向相反**，
     * 而且必须是相反：回滚意味着这些菜品还在库里，它们引用的图片就必须原样留着；
     * 只有事务确实提交了，那些图片才真的没人用了。挂错的后果很直观——挂到回滚上，
     * 一次失败的删除会把好好在用的图片删掉。
     * <p>
     * 这也说明本地磁盘不可能和数据库真正"同成同败"：文件没有两阶段提交，只能事后补偿，
     * 补偿的方向由操作本身决定（新增是"回滚则删"，删除是"提交则删"）。
     */
    private void registerImagesCleanup(List<String> images) {
        if (images == null || images.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteUploadedImages(images);
            }
        });
    }

    /**
     * 删库与删图的配合。
     * <p>
     * 先注册回调再动表：没有活动事务时（单测里直接 new 出本类，或将来有人删掉了 @Transactional）
     * 等不到回调，就地清理，不让清理逻辑静默失效——与 {@link #saveWithFlavor} 的退化分支同一手法，
     * 区别只是这里删文件发生在删表之**后**（删表失败时不该动图片）。
     */
    private void deleteRowsAndImages(List<Long> idList, List<String> images) {
        boolean deferred = TransactionSynchronizationManager.isSynchronizationActive();
        if (deferred) {
            registerImagesCleanup(images);
        }

        //不校验影响行数：id 不存在的行不参与删除，MySQL 返回 0 行，这不是错误。
        //前端重复点击删除、或者菜品已经被别人删掉，都应当安静地成功。
        dishFlavorMapper.deleteByDishIds(idList);
        dishMapper.deleteByIds(idList);

        if (!deferred) {
            deleteUploadedImages(images);
        }
    }

    /**
     * 新增菜品与口味的实现体，事务与图片补偿由 {@link #saveWithFlavor} 负责。
     */
    private void save(DishDTO dishDTO) {
        //名称两端空白先去掉再校验：校验的应该是真正要入库的那个值。
        trimNames(dishDTO);
        //先把整个 DTO（包括每一项口味）校验完再动表：写库之后再发现口味不合法，
        //库里已经躺了一条没有口味的菜品，虽然事务会回滚，但不如一开始就不写。
        validate(dishDTO);

        //菜品与分类之间没有外键约束，不显式校验就会写出一条挂着不存在分类的菜品，
        //它在任何按分类筛选的列表里都查不到。
        if (categoryMapper.countById(dishDTO.getCategoryId()) == 0) {
            throw new BaseException(MessageConstant.CATEGORY_NOT_FOUND);
        }

        //先拦掉未登录的请求，避免写出一条没有创建人的记录（AutoFillAspect 在缺上下文时不会补操作人）。
        if (BaseContext.getCurrentId() == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }

        Dish dish = new Dish();
        //新增主键由数据库生成，不接受客户端指定 id。flavors 不是 dish 的属性，不会被拷过去。
        BeanUtils.copyProperties(dishDTO, dish, "id");
        //接口文档把 status 标为非必须，前端新增时固定传 0（停售）。缺省同样按停售入库，不能留 null：
        //列上的默认值只在"不写这一列"时生效，显式写 null 会绕过它，让菜品处于既非起售也非停售的状态。
        dish.setStatus(dishDTO.getStatus() == null ? StatusConstant.DISABLE : dishDTO.getStatus());
        //创建/修改时间与创建/修改人由 AutoFillAspect 依据 @AutoFill(INSERT) 填充，业务层不再逐个设置

        try {
            if (dishMapper.insert(dish) != 1) {
                throw new BaseException("新增菜品失败");
            }
        } catch (DuplicateKeyException ex) {
            //dish.name 的唯一索引同时保证并发新增时不会出现重名菜品。
            throw new BaseException(MessageConstant.DISH_NAME_ALREADY_EXISTS);
        }

        //主键由 useGeneratedKeys 回填。拿不到就写不了口味（dish_id 非空），
        //与其让数据库抛约束错误，不如在这里明确失败。
        Long dishId = dish.getId();
        if (dishId == null) {
            throw new BaseException("新增菜品失败");
        }

        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && !flavors.isEmpty()) {
            for (DishFlavor flavor : flavors) {
                //口味主键同样由数据库生成；dishId 必须用回填的菜品id覆盖，客户端传的 id/dishId 一律不采纳。
                flavor.setId(null);
                flavor.setDishId(dishId);
            }
            dishFlavorMapper.insertBatch(flavors);
        }
    }

    /**
     * 修改菜品及其口味。
     * <p>
     * 事务与图片补偿的骨架与 {@link #saveWithFlavor} 逐字一致，理由也完全相同：dish 与 dish_flavor
     * 要同成同败，而磁盘上的图片根本不在这套事务里，只能靠"确实回滚之后删掉"来补偿。
     * 区别只有一处：这里校验的是"菜品必须已存在"，而新增不需要。
     * @param dishDTO 菜品信息，必须带 id
     */
    @Transactional
    public void updateWithFlavor(DishDTO dishDTO) {
        String image = dishDTO == null ? null : dishDTO.getImage();
        boolean deferred = TransactionSynchronizationManager.isSynchronizationActive();
        if (deferred) {
            //注册点必须在动任何表之前：校验失败、菜品不存在、分类不存在、重名、口味写入失败——
            //所有失败路径都要被这个回调覆盖。
            registerImageCleanup(image);
        }
        try {
            update(dishDTO);
        } catch (RuntimeException | Error ex) {
            //没有活动事务时等不到回调（单测里直接 new 出本类，或将来有人删掉了 @Transactional），
            //就地删除，不让清理逻辑静默失效。
            if (!deferred) {
                deleteUploadedImage(image);
            }
            throw ex;
        }
    }

    /**
     * 修改菜品与口味的实现体，事务与图片补偿由 {@link #updateWithFlavor} 负责。
     * <p>
     * 顺序是固定的，前面任何一步失败都不能已经动过表：
     * 校验 → id 校验（含菜品是否存在）→ 分类存在 → 登录上下文 → 改 dish → 整组替换口味。
     */
    private void update(DishDTO dishDTO) {
        if (dishDTO == null) {
            throw new BaseException(MessageConstant.DISH_ID_EMPTY);
        }
        //名称两端空白先去掉再校验，理由见 trimNames
        trimNames(dishDTO);
        //与新增共用同一套校验（名称/分类/价格/图片存在性/描述/状态/口味列表），不重复实现一遍。
        validate(dishDTO);

        Long id = dishDTO.getId();
        if (id == null) {
            throw new BaseException(MessageConstant.DISH_ID_EMPTY);
        }
        //主键从 1 开始，0 与负数永远不可能是库里存在的行；顺带挡掉"改一个不存在的菜品"。
        if (id <= 0 || dishMapper.getById(id) == null) {
            throw new BaseException(MessageConstant.DISH_NOT_FOUND);
        }

        //菜品与分类之间没有外键约束，不显式校验就会把菜品改到一个不存在的分类下，
        //它随后在任何按分类筛选的列表里都查不到。
        if (categoryMapper.countById(dishDTO.getCategoryId()) == 0) {
            throw new BaseException(MessageConstant.CATEGORY_NOT_FOUND);
        }

        //先拦掉未登录的请求，避免写出没有修改人的记录（AutoFillAspect 在缺上下文时不会补操作人）。
        if (BaseContext.getCurrentId() == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }

        Dish dish = new Dish();
        //这里**不能**像 save 那样忽略 "id"：更新靠它定位行。
        //Dish 没有 flavors 属性，口味列表不会被拷过去；Dish 也没有 categoryName，前端回传的
        //多余字段进不来（它在 JSON 反序列化阶段就已经被 DishDTO 丢掉了）。
        BeanUtils.copyProperties(dishDTO, dish);
        //修改时间与修改人由 AutoFillAspect 依据 @AutoFill(UPDATE) 填充，业务层不自己设置；
        //createTime / createUser 更不该出现在实体里——update 的列清单里根本没有这两列（见 DishMapper.xml）。

        try {
            //不校验影响行数：把某行改回它自己当前的值时 MySQL 返回 0 行，这不是失败。
            dishMapper.update(dish);
        } catch (DuplicateKeyException ex) {
            //必须显式 catch：全局处理器里那条 SQLIntegrityConstraintViolationException 分支
            //会把索引冲突渲染成带引号的 '名字'已存在，提示与新增接口对不上。
            throw new BaseException(MessageConstant.DISH_NAME_ALREADY_EXISTS);
        }

        //口味整组替换：前端编辑页总是把当前口味列表整份回传，"清掉再插一遍"正好对上它的语义，
        //比逐条比对新旧口味再去增删改简单得多，也不会留下半新半旧的口味。
        //复用删除菜品那条批量方法（deleteByDishIds 收的是 id 列表），不为单个 id 另开一条 SQL。
        dishFlavorMapper.deleteByDishIds(List.of(id));

        List<DishFlavor> flavors = dishDTO.getFlavors();
        //flavors 缺省（null）与传 [] 等价：DishDTO 的字段初始值就是空列表，
        //JSON 里不带该字段时拿到的也是空列表，两者都表示"这道菜没有口味"。
        if (flavors != null && !flavors.isEmpty()) {
            for (DishFlavor flavor : flavors) {
                if (flavor == null) {
                    //正常走不到这里：validate 已经拦过 null 元素，留着只是不让它变成 NPE。
                    throw new BaseException("口味数据不能为空");
                }
                //口味主键一律由数据库生成，dishId 一律用本次修改的菜品id覆盖：
                //前端回显时拿到什么就回传什么，客户端传的 id/dishId 一律不采纳。
                flavor.setId(null);
                flavor.setDishId(id);
            }
            dishFlavorMapper.insertBatch(flavors);
        }
    }

    /**
     * 菜品起售、停售。
     * <p>
     * 复用与修改接口同一条动态 update：实体上只带 id 与 status，动态 {@code <set>} 因此只会写
     * status 一列（外加由 AutoFillAspect 依据 {@code @AutoFill(UPDATE)} 刷新的 update_time、
     * update_user），名称、价格、图片、分类、口味一列都不会被碰。
     * <p>
     * 取值校验与 {@code EmployeeServiceImpl#startOrStop} 同一套：id 必须为正、status 只能是 0/1、
     * 菜品必须存在，任一不满足都给中文提示，而不是落库一条状态诡异的记录或冒成 500。
     */
    public void startOrStop(Integer status, Long id) {
        if (id == null || id <= 0) {
            throw new BaseException(MessageConstant.DISH_ID_EMPTY);
        }
        //status 来自路径变量，直接调接口可以传任何整数。不拦就会把 2、-1 这类值写进 status 列，
        //菜品随后处于既非起售也非停售的状态：列表页按 status 是否为 0 二分显示，会把它显示成"启售"，
        //而按 status = 1 过滤的查询又查不到它。
        if (!StatusConstant.ENABLE.equals(status) && !StatusConstant.DISABLE.equals(status)) {
            throw new BaseException(MessageConstant.DISH_STATUS_ERROR);
        }
        //菜品与分类一样没有外键兜底，但这里有：查不到就给一句明确的提示，而不是安静地改 0 行。
        if (dishMapper.getById(id) == null) {
            throw new BaseException(MessageConstant.DISH_NOT_FOUND);
        }

        //先拦掉未登录的请求，避免写出没有修改人的记录（AutoFillAspect 在缺上下文时不会补操作人）。
        if (BaseContext.getCurrentId() == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }

        Dish dish = Dish.builder()
                .id(id)
                .status(status)
                .build();

        //不校验影响行数：把状态改成它当前的值时 MySQL 返回 0 行，重复点击"起售"不应被当成失败
        //（与员工启停、菜品修改同一约定）。前端那个按钮本来就按当前状态取反，连着点两次只是空转。
        dishMapper.update(dish);
    }

    /**
     * 按条件查询菜品列表（不分页）。
     * <p>
     * 实体上只填 categoryId 与 name 两列，其余字段保持 null，动态 {@code <where>} 因此不会
     * 按 status 之类的列筛选 —— 本接口有意不过滤起售状态（理由见 {@link DishService#list}）。
     * <p>
     * categoryId 原样下传而不是"非法就置空"：置空会把 {@code ?categoryId=0} 从"查不到任何菜品"
     * 变成"返回全部菜品"，那是两种完全不同的语义。
     * @param categoryId 分类id，可选
     * @param name 菜品名称，可选，模糊匹配
     * @return 菜品实体列表，空结果就是空列表
     */
    public List<Dish> list(Long categoryId, String name) {
        Dish dish = new Dish();
        dish.setCategoryId(categoryId);

        //名称两端空白不参与匹配：' 鱼 ' 去空白后才是用户想搜的"鱼"；纯空白等同于不筛选，
        //否则 like '% %' 会把语义悄悄变成"名称里含空格"，搜不到任何数据。
        if (name != null) {
            String trimmed = name.trim();
            dish.setName(trimmed.isEmpty() ? null : trimmed);
        }

        List<Dish> dishes = dishMapper.list(dish);
        //MyBatis 的 select 返回集合时不会给 null，这里兜一下只是不让调用方拿到意外值：
        //前端会直接读 data.length，null 会让整个选菜弹窗抛 TypeError（与 flavors 那条同一类硬约束）。
        return dishes == null ? new ArrayList<>() : dishes;
    }

    /**
     * 根据id查询菜品，供编辑页回显。
     * <p>
     * 用 {@code new DishVO()} 而不是 {@code DishVO.builder()} 构造：{@code @Builder} 不会应用字段初始值，
     * builder 出来的 flavors 是 null，而前端编辑页拿到的 data.flavors 会被直接调 .map ——
     * 没有口味的菜必须给出空数组，null 会让整页抛 TypeError 打不开。
     * <p>
     * 不 join category：编辑页不读 categoryName，分类名由分类下拉框自己取。
     * @param id 菜品id
     * @return 菜品详情，flavors 一定不是 null
     */
    public DishVO getById(Long id) {
        Dish dish = id == null ? null : dishMapper.getById(id);
        if (dish == null) {
            throw new BaseException(MessageConstant.DISH_NOT_FOUND);
        }

        DishVO dishVO = new DishVO();
        BeanUtils.copyProperties(dish, dishVO);

        //查不到口味时兜成空列表而不是留着 null：这是编辑页能否打开的硬前提。
        List<DishFlavor> flavors = dishFlavorMapper.getByDishId(id);
        dishVO.setFlavors(flavors == null ? new ArrayList<>() : flavors);
        return dishVO;
    }

    /**
     * 菜名与口味名入库前去掉两端空白。
     * <p>
     * 必须做，而且要在**校验之前**做。原因在唯一索引的排序规则上：{@code dish.name} 是
     * {@code utf8mb3_bin}（PAD SPACE），**只忽略尾随空格、区分前导空格**。所以 {@code " 鱼"} 与
     * {@code "鱼"} 是两行不同的记录，能同时存在，在管理端列表里显示成一模一样的菜；而查询路径是
     * trim 过的，搜"鱼"两条都命中。库里没有任何东西会把它们规范化回来，{@code idx_dish_name}
     * 这个唯一索引也就永远拦不住这种"影子菜品"。去掉两端空白之后，重复的菜名才真的撞得上索引。
     * <p>
     * 放在校验之前还有个好处：长度校验看到的就是真正要入库的那个值。
     * 纯空白的名字去掉后是空串，照旧由 validate 拒绝——这里不兜这件事。
     * <p>
     * 用 {@link String#trim()} 而不是 {@code strip()}：要与查询路径（{@link #pageQuery}、
     * {@link #list}）用同一个口径，两边不一致比"少去掉几个 Unicode 空格"更糟。
     * 不是每个名称都必须存在（口味名在 validate 里才检查），所以 null 原样返回。
     */
    private void trimNames(DishDTO dishDTO) {
        if (dishDTO == null) {
            return;
        }
        dishDTO.setName(trim(dishDTO.getName()));

        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors == null) {
            return;
        }
        for (DishFlavor flavor : flavors) {
            if (flavor != null) {
                flavor.setName(trim(flavor.getName()));
            }
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    /**
     * 校验新增菜品的全部输入。列宽取自数据库实际结构（dish.name、dish_flavor.name 为 varchar(32)，
     * image/description/value 为 varchar(255)，price 为 decimal(10,2)），
     * 目的是把"数据库会报错"变成"接口给出一句能看懂的话"。
     */
    private void validate(DishDTO dishDTO) {
        //调用方（save / update）都会先传下来一个可能为 null 的 DTO，第一句取字段就会 NPE，
        //所以空判断放在最前面：它是"校验"，本身就该能处理空输入。
        if (dishDTO == null) {
            throw new BaseException("菜品信息不能为空");
        }

        validateRequiredText(dishDTO.getName(), "菜品名称", NAME_MAX_LENGTH);

        if (dishDTO.getCategoryId() == null || dishDTO.getCategoryId() <= 0) {
            throw new BaseException("菜品分类不能为空");
        }

        validatePrice(dishDTO.getPrice());

        //图片是接口文档里的必填项，两种形态都合法：库里存量数据是阿里云 OSS 的绝对地址，
        //本地磁盘上传返回的是 /uploads/... 相对路径，因此不校验路径前缀。
        validateRequiredText(dishDTO.getImage(), "菜品图片", TEXT_MAX_LENGTH);
        //但本地路径必须确认文件真的在：新增失败时接口会把这次请求引用的图删掉（见 deleteUploadedImage），
        //客户端若拿着一个已经被删掉的路径重提交，只有这一条能拦住"写出一条挂着不存在图片的菜品"。
        //OSS 绝对地址不查：它不是本地的文件，查不了也不该因此被拒。
        if (localFileUtil.isLocalPath(dishDTO.getImage()) && !localFileUtil.exists(dishDTO.getImage())) {
            throw new BaseException(MessageConstant.DISH_IMAGE_NOT_FOUND);
        }
        validateOptionalText(dishDTO.getDescription(), "菜品描述", TEXT_MAX_LENGTH);

        Integer status = dishDTO.getStatus();
        if (status != null && !StatusConstant.ENABLE.equals(status) && !StatusConstant.DISABLE.equals(status)) {
            throw new BaseException(MessageConstant.DISH_STATUS_ERROR);
        }

        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors == null) {
            return;
        }
        for (DishFlavor flavor : flavors) {
            if (flavor == null) {
                //JSON 里写成 [null] 时元素就是 null，不拦会在下面拿 NPE 当 500 返回。
                throw new BaseException("口味数据不能为空");
            }
            validateRequiredText(flavor.getName(), "口味名称", NAME_MAX_LENGTH);
            //value 存的是前端 JSON.stringify 出来的字符串（如 ["无糖","多糖"]），这里只校验必填与长度，
            //不解析其内容：接口把它定义为字符串，格式由客户端负责。
            validateRequiredText(flavor.getValue(), "口味值", TEXT_MAX_LENGTH);
        }
    }

    private void validatePrice(BigDecimal price) {
        if (price == null) {
            throw new BaseException("菜品价格不能为空");
        }
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BaseException("菜品价格必须大于0");
        }
        //用 stripTrailingZeros 再比位数：12.500 与 12.5 在数据库里是同一个值，不该被判为三位小数。
        if (price.stripTrailingZeros().scale() > PRICE_SCALE) {
            throw new BaseException("菜品价格最多保留两位小数");
        }
        if (price.compareTo(MAX_PRICE) > 0) {
            throw new BaseException("菜品价格超出范围，最大" + MAX_PRICE);
        }
    }

    private void validateRequiredText(String value, String label, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new BaseException(label + "不能为空");
        }
        validateLength(value, label, maxLength);
    }

    private void validateOptionalText(String value, String label, int maxLength) {
        if (value == null) {
            return;
        }
        validateLength(value, label, maxLength);
    }

    private void validateLength(String value, String label, int maxLength) {
        //按码点数（而不是 String.length）计数，与 MySQL 按字符计算 varchar 长度的口径一致。
        //但要说清这里能保证到什么程度：dish.name 等列是 utf8mb3，4 字节字符（如 emoji）它根本存不下，
        //长度再短也会在写库时抛 Incorrect string value，变成 500 而不是这里的提示。
        //这是项目全局现状（employee 等表同样如此），不在这里单独处理。
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new BaseException(label + "不能超过" + maxLength + "个字符");
        }
    }

    /**
     * 菜品分页查询
     * <p>
     * 列表页的"分类"一列显示的是分类名称，SQL 里 left join category 取到 category_name 填进
     * {@link DishVO#getCategoryName()}。菜品与分类之间没有外键，join 必须是 left：分类被删掉或
     * category_id 悬空的菜品要照常出现在列表里，不能因为关联不上分类就静默消失。
     * @param dishPageQueryDTO 查询参数
     * @return 总记录数与当前页菜品
     */
    public PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO) {
        if (dishPageQueryDTO == null) {
            throw new BaseException(MessageConstant.PAGE_PARAM_ERROR);
        }
        int page = dishPageQueryDTO.getPage();
        int pageSize = dishPageQueryDTO.getPageSize();
        // page、pageSize 是基本类型，查询参数缺失时默认为 0，会生成非法的 limit，必须先拦截。
        if (page < 1 || pageSize < 1) {
            throw new BaseException(MessageConstant.PAGE_PARAM_ERROR);
        }

        //名称两端空白不参与匹配：' 鱼 ' 去空白后才是用户想搜的"鱼"；纯空白等同于不筛选，
        //否则 like '% %' 会把语义悄悄变成"名称里含空格"，查不到任何数据。
        if (dishPageQueryDTO.getName() != null) {
            String name = dishPageQueryDTO.getName().trim();
            dishPageQueryDTO.setName(name.isEmpty() ? null : name);
        }

        try {
            PageHelper.startPage(page, pageSize);
            Page<DishVO> result = dishMapper.pageQuery(dishPageQueryDTO);
            // total 来自 PageHelper 的 count 查询，与当前页取了几条无关。
            return new PageResult(result.getTotal(), result.getResult());
        } finally {
            //分页参数存放在 ThreadLocal，查询未真正执行时不会被 PageHelper 清掉，必须显式清理以免污染复用的线程。
            PageHelper.clearPage();
        }
    }

    /**
     * 批量删除菜品及其口味，并清理不再被引用的图片文件。
     * <p>
     * 顺序是固定的，前面任何一步失败都不能已经动过表：
     * 解析参数 → 守卫一（起售不能删）→ 守卫二（被套餐引用不能删）→ **记下这批菜品引用的图片**
     * → 注册提交后回调 → 删 dish_flavor → 删 dish。
     * 解析阶段就把整串 id 校验完才允许碰数据库，所以"ids 里混了一个非法值"是整体拒绝，
     * 不存在"删掉一半再报错"。两条守卫的顺序保证同时命中时报的是"起售中的菜品不能删除"。
     * <p>
     * 删子表再删主表：dish_flavor 与 dish 之间没有外键，反过来先删主表会留下指向不存在菜品的口味行。
     * <p>
     * 图片文件不在事务里（本地磁盘没有两阶段提交），只能事后补偿：回调挂在 **afterCommit** 上，
     * 事务确实提交之后才去删那些图片，删之前再确认没有别的记录在引用它们。
     * 回滚时什么都不做——菜品还在，图就必须还在。细节见 {@link #registerImagesCleanup}。
     * <p>
     * 不校验影响行数：id 不存在时 MySQL 返回 0，重复点击删除应当幂等。
     * @param ids 前端原样传来的逗号分隔菜品id
     */
    @Transactional
    public void deleteByIds(String ids) {
        List<Long> idList = parseIds(ids);

        //守卫一：起售中的菜品不能删。count(*) 不会返回 null，null 判断只是让"统计不到"等同"没有命中"。
        Integer onSale = dishMapper.countOnSaleByIds(idList);
        if (onSale != null && onSale > 0) {
            throw new DeletionNotAllowedException(MessageConstant.DISH_ON_SALE);
        }

        //守卫二：被套餐引用的菜品不能删。它排在起售判断之后，两者同时命中时报的是上面那条消息。
        Integer relatedBySetmeal = setmealMapper.countByDishIds(idList);
        if (relatedBySetmeal != null && relatedBySetmeal > 0) {
            throw new DeletionNotAllowedException(MessageConstant.DISH_BE_RELATED_BY_SETMEAL);
        }

        //必须在删除之前查：行删掉之后就再也查不出这些菜品引用过哪些图片了。
        //放在两条守卫之后，被拒的请求不会白查一次。
        List<String> images = dishMapper.listImagesByIds(idList);

        deleteRowsAndImages(idList, images);
    }

    /**
     * 解析前端传来的逗号分隔菜品id。
     * <p>
     * 逐个 token 校验，任一不合法就整体拒绝：解析完成之前一次 mapper 都不会被调用，
     * 所以库里不会出现"删了一部分"的中间状态。
     * <p>
     * 用 LinkedHashSet 去重并保留出现顺序：前端批量删除时可能重复勾选同一个菜品，
     * 重复的 id 下发给 SQL 没有意义，还会让 in 列表无谓地变长。
     */
    private List<Long> parseIds(String ids) {
        if (ids == null || ids.trim().isEmpty()) {
            throw new BaseException(MessageConstant.DISH_ID_EMPTY);
        }

        // split 必须带 -1 这个 limit：默认的 split(",") 会丢掉末尾的空串，
        // "1,2," 会被切成 ["1","2"] 而当成合法输入，"1,,2" 中间那个空串倒是能留下。
        // 两种情况都必须是"格式错误"，所以这里显式要求保留末尾空串。
        String[] tokens = ids.split(",", -1);
        Set<Long> idSet = new LinkedHashSet<>(tokens.length);
        for (String token : tokens) {
            String value = token.trim();
            //空白 token（"1,,2" 里的空串、"1,2," 末尾那个）与非十进制数字
            //（"abc"、"1.5"、"1;2"、"-1"）都在这里被拒。
            if (!DISH_ID_PATTERN.matcher(value).matches()) {
                throw new BaseException(MessageConstant.DISH_ID_FORMAT_ERROR);
            }
            long id;
            try {
                id = Long.parseLong(value);
            } catch (NumberFormatException ex) {
                //超出 long 范围（如 99999999999999999999）：不能让它冒成 500。
                throw new BaseException(MessageConstant.DISH_ID_FORMAT_ERROR);
            }
            if (id <= 0) {
                //主键从 1 开始，0 与负数永远是非法id（且库里的 24 道菜 id 从 46 起）。
                throw new BaseException(MessageConstant.DISH_ID_FORMAT_ERROR);
            }
            idSet.add(id);
        }
        //LinkedHashSet 的迭代顺序就是插入顺序，转成 List 后下发给 mapper。
        return new ArrayList<>(idSet);
    }

}
