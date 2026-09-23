package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.annotation.AutoFill;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.enumeration.OperationType;
import com.sky.vo.DishVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

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

}
