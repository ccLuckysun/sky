package com.sky.mapper;

import com.sky.entity.DishFlavor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DishFlavorMapper {

    /**
     * 批量新增菜品口味，SQL 位于 resources/mapper/DishFlavorMapper.xml。
     * <p>
     * 这里**不能**加 @AutoFill：dish_flavor 表没有审计列，而 AutoFillAspect 的切点会拦下
     * com.sky.mapper 包下所有标了注解的方法、把第一个参数当实体反射调用 setter，
     * 标在这条 List 参数的方法上只会让切面在运行期拿错对象。
     * @param flavors 调用方需保证每项的 dishId 已填好
     */
    void insertBatch(@Param("flavors") List<DishFlavor> flavors);

    /**
     * 按菜品id批量删除口味，SQL 位于 resources/mapper/DishFlavorMapper.xml。
     * <p>
     * 删除菜品时先清子表：dish_flavor 与 dish 之间没有外键，但只要两处都存在，
     * dish_id 悬空的口味行就是一条谁也看不见、也永远删不掉的垃圾数据。
     * <p>
     * 这里**不能**加 @AutoFill：dish_flavor 表没有审计列，而 AutoFillAspect 会把第一个参数
     * （这里是 List）当实体反射调用 setter，标上只会在运行期抛异常。
     * @param ids 菜品id集合，调用方需保证非空（空集合会拼出非法的 in ()）
     * @return 实际删除的行数（该菜品没有口味时是 0，业务层不校验这个值）
     */
    int deleteByDishIds(@Param("ids") List<Long> ids);

    /**
     * 根据菜品id查询口味，供编辑页回显使用。
     * <p>
     * 返回 {@code List} 而不是单个对象：一个菜品可以有零到多组口味，调用方必须能区分
     * "没有口味"（空列表，接口要返回 {@code []}）与"查不到"——这里统一按空列表处理，
     * 前端编辑页会直接对结果调 .map，返回 null 会把整页打废。
     * <p>
     * 这里**不能**加 @AutoFill：dish_flavor 表没有审计列，切面反射 setter 会直接抛异常。
     * @param dishId 菜品id
     * @return 该菜品的口味列表，没有口味时是空列表
     */
    @Select("select id, dish_id, name, value from dish_flavor where dish_id = #{dishId}")
    List<DishFlavor> getByDishId(Long dishId);

}
