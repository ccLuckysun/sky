package com.sky.service;

import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.result.PageResult;
import com.sky.vo.DishVO;

public interface DishService {

    /**
     * 新增菜品及其口味。
     * <p>
     * 方法名带 WithFlavor 是为了点明这个接口会同时写 dish 与 dish_flavor 两张表，
     * 与只写单表的 CategoryService#save 区分开。
     * @param dishDTO 菜品信息，flavors 可为空
     */
    void saveWithFlavor(DishDTO dishDTO);

    /**
     * 菜品分页查询。
     * <p>
     * 每条记录带分类名称：菜品列表要展示"分类"一列，而 dish 表里只有 category_id。
     * @param dishPageQueryDTO 查询参数：name（模糊）、categoryId、status 均可选，page/pageSize 必须为正整数
     * @return 总记录数与当前页数据
     */
    PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO);

    /**
     * 批量删除菜品及其口味。
     * <p>
     * 入参是前端 {@code DELETE /admin/dish?ids=1,2,3} 原样传来的字符串（单条删除也走同一个参数，
     * 值是单个 id），解析与校验都在本方法里做，Controller 不做参数绑定之外的判断。
     * <p>
     * 两条守卫按固定顺序执行：起售中的菜品不能删除、被套餐引用的菜品不能删除，
     * 同时命中时报的是前者。id 不存在的部分视为无操作，不报错。
     * @param ids 逗号分隔的菜品id；为空或含任何非法 id 时整体拒绝，不会删掉其中合法的那些
     */
    void deleteByIds(String ids);

    /**
     * 修改菜品及其口味。
     * <p>
     * 口味是**整组替换**：先按菜品id删掉全部旧口味，再把请求里的口味重新插一遍。
     * 逐条比对新旧口味再去增删改要复杂得多，而前端的编辑页本来就总是把当前口味列表整份回传，
     * 整组替换正好对上它的语义。（此时 flavors 为 null 或空列表都表示"清空口味"。）
     * <p>
     * 与新增一样，dish 与 dish_flavor 在同一个事务里；图片文件的补偿见
     * {@code DishServiceImpl#updateWithFlavor}。
     * @param dishDTO 菜品信息，必须带 id；flavors 可为空
     */
    void updateWithFlavor(DishDTO dishDTO);

    /**
     * 根据id查询菜品，供编辑页回显。
     * <p>
     * flavors 一定是数组：没有口味的菜品返回空列表而不是 null，否则前端编辑页会对
     * {@code data.flavors} 直接调 .map 而抛 TypeError，整页打不开。
     * categoryName 不填（编辑页不读它，不为它多写一条 join）。
     * @param id 菜品id
     * @return 菜品详情，不存在时抛业务异常
     */
    DishVO getById(Long id);

}
