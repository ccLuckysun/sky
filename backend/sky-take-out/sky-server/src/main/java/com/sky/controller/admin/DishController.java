package com.sky.controller.admin;

import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 菜品管理
 */
@RestController
@RequestMapping("/admin/dish")
@Api(tags = "菜品相关接口")
@Slf4j
public class DishController {

    @Autowired
    private DishService dishService;

    /**
     * 新增菜品
     * @param dishDTO
     * @return
     */
    @PostMapping
    @ApiOperation("新增菜品")
    public Result<String> save(@RequestBody DishDTO dishDTO) {
        log.info("新增菜品：{}", dishDTO);
        dishService.saveWithFlavor(dishDTO);
        //接口文档把 data 标为非必须，前端只判 code，因此不返回新菜品id
        return Result.success();
    }

    /**
     * 菜品分页查询
     *
     * @param dishPageQueryDTO 查询参数：name（可选，模糊匹配）、categoryId（可选）、status（可选）、
     *                         page 与 pageSize（必填）
     * @return 总记录数与当前页菜品，每条记录带分类名称 categoryName
     */
    @GetMapping("/page")
    @ApiOperation("菜品分页查询")
    public Result<PageResult> page(DishPageQueryDTO dishPageQueryDTO) {
        log.info("菜品分页查询：{}", dishPageQueryDTO);
        PageResult pageResult = dishService.pageQuery(dishPageQueryDTO);
        return Result.success(pageResult);
    }

    /**
     * 批量删除菜品
     *
     * @param ids 逗号分隔的菜品id，如 {@code 1,2,3}。单条删除走同一个参数，值是单个 id。
     * @return 成功时 data 与 msg 均为 null
     */
    @DeleteMapping
    @ApiOperation("批量删除菜品")
    public Result<String> delete(@RequestParam(required = false) String ids) {
        log.info("批量删除菜品：ids={}", ids);
        dishService.deleteByIds(ids);
        //接口文档把 data 标为非必须，前端只判 code，因此返回空的 Result（data、msg 均为 null）；
        //与 POST /admin/dish 的取舍一致。
        return Result.success();
    }

    /**
     * 修改菜品
     *
     * @param dishDTO 菜品信息，必须带 id。请求体来自编辑页的表单，因此会带上 GET /admin/dish/{id}
     *                回显时多出来的 {@code categoryName}，以及每个口味回传的 id / dishId ——
     *                DishDTO 里没有 categoryName 这个字段（靠 JacksonObjectMapper 关掉
     *                FAIL_ON_UNKNOWN_PROPERTIES 安静忽略，不能 400），口味上的 id / dishId 由
     *                Service 一律覆盖重写。
     * @return 成功时 data 与 msg 均为 null
     */
    @PutMapping
    @ApiOperation("修改菜品")
    public Result<String> update(@RequestBody DishDTO dishDTO) {
        log.info("修改菜品：{}", dishDTO);
        dishService.updateWithFlavor(dishDTO);
        //与 POST /admin/dish、DELETE /admin/dish 同一取舍：接口文档把 data 标为非必须，前端只判 code。
        return Result.success();
    }

    /**
     * 根据分类id或名称查询菜品列表（不分页）
     * <p>
     * 前端契约（从已构建 bundle 的 source map 核对）：调用方是套餐页的选菜组件
     * {@code setmeal/components/AddDish.vue}，它发两种形状——
     * {@code queryDishList({categoryId: id})}（初始化与点分类时）和
     * {@code queryDishList({name})}（关键字搜索，由 {@code @Watch('seachKey')} 触发）。
     * 两个条件因此都做成可选、可叠加；接口文档只写了 {@code categoryId}，只实现它会让搜索那一支
     * 因为缺参而 400。
     * <p>
     * **不过滤起售状态**：组件对 {@code status === 0} 的菜显式渲染「停售」标签，说明停售菜品本就
     * 应当出现在这个列表里；套餐启售时另有 {@code SETMEAL_ENABLE_FAILED} 兜底。
     * <p>
     * 返回的是 {@code Dish} 实体而不是 {@code DishVO}：接口文档的响应模型列了
     * {@code createTime}/{@code createUser}/{@code updateUser}，这三列只在实体上有。
     * <p>
     * {@code paths = "/list"} 不会抢走 {@code /{id}} 或 {@code /page}：Spring 的路径匹配里
     * 字面量优先于变量（与第 19、21 节同一条规矩）。
     *
     * @param categoryId 分类id，可选
     * @param name       菜品名称，可选，模糊匹配
     * @return 菜品列表，没有命中时 data 是空数组而不是 null
     */
    @GetMapping("/list")
    @ApiOperation("根据分类id查询菜品")
    public Result<List<Dish>> list(@RequestParam(required = false) Long categoryId,
                                   @RequestParam(required = false) String name) {
        log.info("根据分类id或名称查询菜品：categoryId={}, name={}", categoryId, name);
        return Result.success(dishService.list(categoryId, name));
    }

    /**
     * 菜品起售、停售
     * <p>
     * 前端契约（从已构建 bundle 的 source map 核对）：列表页那一列的按钮调
     * {@code dishStatusByStatus({id, status})}，拼成 {@code POST /dish/status/{status}?id=xx}，
     * status 是 <b>字符串</b>（{@code row.status ? '0' : '1'}）——绑到 Integer 上没问题。
     * 按钮的文案按当前状态取反（当前停售显示"启售"），所以到这里的 status 永远是切换后的目标值。
     * <p>
     * 页面工具栏只有「批量删除」和「+ 新建菜品」，没有批量启售/停售，所以 {@code id} 恒为单个菜品id。
     * 那个 {@code statusHandle} 里 {@code typeof row === 'string'} 的批量分支（逗号拼 id）在本页
     * 是死代码，本接口**不**接受逗号分隔的多个 id：绑不进 Long，会在进方法体之前以 400 失败。
     *
     * @param status 菜品状态，1为起售 0为停售
     * @param id     菜品id
     * @return 成功时 data 与 msg 均为 null
     */
    @PostMapping("/status/{status}")
    @ApiOperation("菜品起售停售")
    public Result<String> startOrStop(@PathVariable Integer status,
                                      @RequestParam(required = false) Long id) {
        log.info("菜品起售停售：status={}, id={}", status, id);
        dishService.startOrStop(status, id);
        //与新增、修改、删除同一取舍：接口文档把 data 标为非必须，前端只判 code。
        return Result.success();
    }

    /**
     * 根据id查询菜品（编辑页回显）
     * <p>
     * 注意 {@code @GetMapping("/{id}")} 不会抢走 {@code /page}：Spring 的路径匹配里字面量优先于变量，
     * {@code /admin/dish/page} 仍然进 page 方法。也不要给 {id} 加正则 —— Boot 2.7 默认的
     * PathPatternParser 不接受正则，写了会在启动时直接崩。
     *
     * @param id 菜品id
     * @return 菜品详情，flavors 一定非 null（没有口味时是空数组）
     */
    @GetMapping("/{id}")
    @ApiOperation("根据id查询菜品")
    public Result<DishVO> getById(@PathVariable Long id) {
        log.info("根据id查询菜品：{}", id);
        DishVO dishVO = dishService.getById(id);
        return Result.success(dishVO);
    }
}
