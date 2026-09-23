package com.sky.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SetmealMapper {

    /**
     * 根据分类id查询套餐的数量
     * @param id
     * @return
     */
    @Select("select count(id) from setmeal where category_id = #{categoryId}")
    Integer countByCategoryId(Long id);

    /**
     * 统计这批菜品被多少个套餐引用。SQL 位于 resources/mapper/SetmealMapper.xml
     * （与上面那条注解式 SQL 同处一个 namespace，只要 statement id 不重复就可以共存）。
     * <p>
     * 用于批量删除菜品前的守卫：菜品与套餐之间没有外键，被套餐引用的菜品删掉之后，
     * 套餐详情里会留下一行指向不存在菜品的记录。
     * <p>
     * 查的是 setmeal_dish 而不是 setmeal：关联关系记在中间表里，套餐本身没有菜品id列。
     * <p>
     * 这里**不能**加 @AutoFill：只读的 count 没有审计字段可填，标上会让切面拿 List 去反射调 setter 而抛异常。
     * @param ids 待删除的菜品id，调用方需保证非空（空集合会拼出非法的 in ()）
     * @return 引用这些菜品的套餐菜品关系条数（同一个菜品被多个套餐引用时会计多条），count 不会返回 null
     */
    Integer countByDishIds(@Param("ids") List<Long> ids);

}
