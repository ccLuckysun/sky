package com.sky.controller.admin;

import com.sky.result.Result;
import com.sky.service.ShopService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 店铺营业状态（管理端，存在 Redis，1 营业 0 打烊）。
 * <p>
 * 前端契约（从已构建 bundle 核对，不是照抄接口文档）：axios 的 baseURL 是 /api，nginx 把
 * {@code location /api/} 代理到后端的 {@code /admin/}，所以页面里写的 {@code /shop/status}、
 * {@code /shop/{status}} 落到这两个接口上。状态栏按 {@code 1 === status} 显示"营业中"，
 * 其余值（含 null）一律显示"打烊中"；设置弹窗是 label 为 1/0 的两个 el-radio。
 * <p>
 * 设置状态时前端是 {@code request.put('/shop/' + status, status)}：状态值同时出现在路径和
 * JSON body 里（接口文档之所以标 {@code Content-Type: application/json} 就是这个原因）。
 * 本接口只读路径变量，不解析 body —— 给它加 {@code @RequestBody} 反而会让路径与 body 不一致的
 * 请求产生歧义。
 * <p>
 * 类名与用户端 {@code com.sky.controller.user.ShopController} 同名，必须显式指定 bean 名：
 * Spring 默认取类的短名，两个 {@code ShopController} 会都叫 {@code shopController}，
 * 启动时直接以 ConflictingBeanDefinitionException 失败。
 */
@RestController("adminShopController")
@RequestMapping("/admin/shop")
@Api(tags = "店铺相关接口")
@Slf4j
public class ShopController {

    @Autowired
    private ShopService shopService;

    /**
     * 获取营业状态
     *
     * @return data 为 1（营业）或 0（打烊）；键不存在时按打烊处理，不会返回 null
     */
    @GetMapping("/status")
    @ApiOperation("获取营业状态")
    public Result<Integer> getStatus() {
        Integer status = shopService.getStatus();
        log.info("获取店铺营业状态：{}", status);
        return Result.success(status);
    }

    /**
     * 设置营业状态
     *
     * @param status 1 营业，0 打烊；其它值由 service 拒绝（code=0 + 提示），不会写进 Redis
     * @return 成功时 data 与 msg 均为 null
     */
    @PutMapping("/{status}")
    @ApiOperation("设置营业状态")
    public Result<String> setStatus(@PathVariable Integer status) {
        log.info("设置店铺营业状态：{}", status);
        shopService.setStatus(status);
        //与菜品起售停售同一取舍：接口文档把 data 标为非必须，前端只判 code。
        return Result.success();
    }
}
