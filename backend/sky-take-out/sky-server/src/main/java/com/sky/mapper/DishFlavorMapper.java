package com.sky.mapper;

import com.sky.entity.DishFlavor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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

}
