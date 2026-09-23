package com.sky.service;

import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.result.PageResult;
import com.sky.vo.DishVO;

import java.util.List;

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
     * <p>
     * 还会清理图片：事务**提交成功之后**，把这批菜品引用过、且已经没有别的记录在用的本地上传图
     * 从磁盘上删掉（只处理 {@code /uploads/...}，阿里云 OSS 之类的绝对地址一律跳过）。
     * 方向与新增/修改相反——那边是"回滚则删图"，这边是"提交则删图"：回滚意味着菜品还在，
     * 它引用的图片就必须原样留着。细节与取舍见 {@code DishServiceImpl#registerImagesCleanup}。
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
     * 菜品起售、停售。
     * <p>
     * 与员工、分类的启停是同一件事，只是列名叫 status：只改这一列，
     * 修改时间与修改人由 {@code AutoFillAspect} 刷新，名称、价格、图片、口味一列都不动。
     * <p>
     * 不做"被套餐引用就不能停售"这类守卫，接口文档没有要求；也不删图片（见
     * {@code DishServiceImpl#deleteUploadedImage} 的取舍）。
     * @param status 菜品状态，1 起售、0 停售，其他值一律拒绝
     * @param id 菜品id
     */
    void startOrStop(Integer status, Long id);

    /**
     * 按条件查询菜品列表（不分页）。
     * <p>
     * 调用方是套餐页的选菜组件，它有两种请求形状：按分类取（{@code ?categoryId=xx}）与按关键字搜
     * （{@code ?name=xx}），所以两个条件都做成可选、可叠加。
     * <p>
     * **不过滤起售状态**：停售的菜品会照常返回。选菜组件本来就为它们渲染「停售」标签，而套餐启售时
     * 另有校验（{@code SETMEAL_ENABLE_FAILED}）兜底；这里过滤掉等于凭空多一条接口文档没写的限制。
     * @param categoryId 分类id，可选；为 null 表示不按分类筛
     * @param name 菜品名称，可选，模糊匹配；两端空白忽略，纯空白等同于不筛选
     * @return 菜品实体列表（不分页），没有命中时是空列表而不是 null
     */
    List<Dish> list(Long categoryId, String name);

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
