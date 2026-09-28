package com.sky.controller.user;

import com.sky.result.Result;
import com.sky.service.ShopService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 店铺营业状态（用户端）。与管理端 {@code com.sky.controller.admin.ShopController} 共用
 * {@link ShopService}，读的是 Redis 里同一个 {@code SHOP_STATUS} 键，所以用户端看到的营业状态
 * 一定与后台设置的一致——这正是把读写抽到 service 里的原因。
 * <p>
 * 类名与管理端同名，必须显式指定 bean 名：Spring 默认取类的短名，两个 {@code ShopController}
 * 会都叫 {@code shopController}，启动时直接以 ConflictingBeanDefinitionException 失败。
 * <p>
 * 可见性：本仓库目前只有管理端的 jwt 拦截器（只拦 {@code /admin/**}），用户端拦截器尚未实现，
 * 所以这条路径现在**无令牌即可访问**。这与用户端前端的行为一致——首页/登录页在未登录时就要显示
 * "店铺已打烊"横幅。后续加用户端拦截器时要想清楚这一点：若按 {@code /user/**} 全拦，
 * 需要把本路径排除，否则未登录时读不到营业状态。
 */
@RestController("userShopController")
@RequestMapping("/user/shop")
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
        log.info("用户端获取店铺营业状态：{}", status);
        return Result.success(status);
    }
}
