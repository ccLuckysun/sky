package com.sky.service.impl;

import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.DishDTO;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.exception.BaseException;
import com.sky.exception.UserNotLoginException;
import com.sky.mapper.CategoryMapper;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.service.DishService;
import com.sky.utils.LocalFileUtil;
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
import java.util.List;

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

    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private DishFlavorMapper dishFlavorMapper;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private LocalFileUtil localFileUtil;

    /**
     * 新增菜品及其口味
     * <p>
     * dish 与 dish_flavor 在同一个事务里，任何失败一起回滚。图片文件不在这套机制内：本地磁盘没有两阶段提交，
     * @Transactional 管不到 Files.write，所以只能补偿——事务确实回滚之后，把这次请求引用的本地图片删掉
     * （见 {@link #deleteUploadedImage}），让磁盘回到"没有这条菜品、也没有它的图"的状态。
     * 上传本身是另一个请求，它失败时一行表都还没碰，不需要补偿。
     * @param dishDTO
     */
    @Transactional
    public void saveWithFlavor(DishDTO dishDTO) {
        String image = dishDTO.getImage();
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
     * 回滚之后清掉这次请求引用的本地图片。
     * <p>
     * 只处理本地上传路径：库里存量数据混着阿里云 OSS 的绝对地址，那些不是文件路径，不能拿去删。
     * 删之前还要确认没有别的菜品引用同一张图：回滚之后本次的行已经没了，此时还能查到引用，
     * 说明这张图是别人在用的（客户端可以提交一个已存在的 image，再因重名失败）。
     * <p>
     * 整个过程不抛异常：回滚常常正是因为数据库不可用，此刻再查库很可能又失败，
     * 那只是"清理没做成"，绝不能让它的异常盖掉真正的业务错误。
     */
    private void deleteUploadedImage(String image) {
        if (!localFileUtil.isLocalPath(image)) {
            return;
        }
        try {
            if (dishMapper.countByImage(image) > 0) {
                log.info("图片仍被其它菜品引用，回滚时不删除：{}", image);
                return;
            }
            localFileUtil.delete(image);
        } catch (Exception ex) {
            log.warn("回滚后删除上传图片失败，文件可能成为孤儿：{}", image, ex);
        }
    }

    /**
     * 新增菜品与口味的实现体，事务与图片补偿由 {@link #saveWithFlavor} 负责。
     */
    private void save(DishDTO dishDTO) {
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
     * 校验新增菜品的全部输入。列宽取自数据库实际结构（dish.name、dish_flavor.name 为 varchar(32)，
     * image/description/value 为 varchar(255)，price 为 decimal(10,2)），
     * 目的是把"数据库会报错"变成"接口给出一句能看懂的话"。
     */
    private void validate(DishDTO dishDTO) {
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

}
