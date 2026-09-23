package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.annotation.AutoFill;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.enumeration.OperationType;
import com.sky.vo.DishVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DishMapper {

    /**
     * 根据分类id查询菜品数量
     * @param categoryId
     * @return
     */
    @Select("select count(id) from dish where category_id = #{categoryId}")
    Integer countByCategoryId(Long categoryId);

    /**
     * 新增菜品。SQL 位于 resources/mapper/DishMapper.xml，主键由 useGeneratedKeys 回填到 dish.id。
     * <p>
     * 这条 SQL 直接引用了四个公共字段，没有 <if> 兜底，所以必须由 AutoFillAspect 依据 @AutoFill(INSERT) 先填好，
     * 否则写进库的就是 null。注解不能删。
     * @param dish
     * @return 影响行数
     */
    @AutoFill(OperationType.INSERT)
    int insert(Dish dish);

    /**
     * 统计有多少菜品在引用这张图片。
     * <p>
     * 用于"新增菜品失败回滚后删图"前的在用判断：库里还有行引用它就不能删。
     * 回滚之后本次请求写的那一行已经不存在了，此时仍能查到引用，说明这张图是别的菜品在用的。
     * <p>
     * 只查 dish：setmeal.image 没有写接口，employee.image 在 mapper 里根本不出现，
     * 目前都不可能有本地上传路径。将来这两处开始写图时必须一起扩展这个判断。
     * @param image 图片路径，与 dish.image 按字符串全等比较
     * @return 引用该图片的菜品数量（count 不会返回 null）
     */
    @Select("select count(id) from dish where image = #{image}")
    int countByImage(String image);

    /**
     * 分页查询菜品，SQL 位于 resources/mapper/DishMapper.xml 的 pageQuery 映射。
     * 调用前必须先用 PageHelper.startPage 设置分页参数，返回的 Page 才能读到总记录数。
     * <p>
     * 这条方法上不能加 @AutoFill：AutoFillAspect 的切点是 com.sky.mapper 包里所有标了该注解的方法，
     * 且会把第一个参数当实体反射调用其 setter。这里的参数是查询条件 DTO，标上注解会在切面里抛
     * IllegalStateException（找不到对应的公共字段 setter），把一次普通查询变成 500。
     * @param dishPageQueryDTO 查询参数（name 模糊、categoryId、status 均为可选）
     * @return 携带 total 的分页结果，每条记录带分类名称
     */
    Page<DishVO> pageQuery(DishPageQueryDTO dishPageQueryDTO);

    /**
     * 统计这批菜品里有多少条在起售。SQL 位于 resources/mapper/DishMapper.xml。
     * <p>
     * 用于批量删除前的守卫：起售中的菜品不允许删除。用 count 而不是把菜品查出来，
     * 是因为只需要知道"有没有"；id 不存在的行自然不计入，所以"删一个已经没了的菜品"不会因此被拒。
     * <p>
     * 这里**不能**加 @AutoFill：AutoFillAspect 的切点会拦下 com.sky.mapper 包下所有标了注解的方法，
     * 并把第一个参数当实体反射调用审计字段的 setter，标在只读方法上会在运行期抛 IllegalStateException。
     * @param ids 待删除的菜品id，调用方需保证非空（空集合会拼出非法的 in ()）
     * @return 其中 status = 1（StatusConstant.ENABLE）的条数，count 不会返回 null
     */
    Integer countOnSaleByIds(@Param("ids") List<Long> ids);

    /**
     * 按id批量删除菜品。SQL 位于 resources/mapper/DishMapper.xml。
     * <p>
     * 调用方（DishServiceImpl#deleteByIds）必须先删 dish_flavor 再调这里：两张表没有外键，
     * 反过来先删主表会留下指向不存在菜品的口味行。
     * <p>
     * 同样**不能**加 @AutoFill：删除没有审计字段可填，标上只会让切面拿 List 去反射调 setter 而抛异常。
     * @param ids 待删除的菜品id，调用方需保证非空
     * @return 实际删除的行数（id 不存在的行不计入，业务层不校验这个值）
     */
    int deleteByIds(@Param("ids") List<Long> ids);

}
