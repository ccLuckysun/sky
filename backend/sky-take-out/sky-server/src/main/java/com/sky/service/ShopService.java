package com.sky.service;

/**
 * 店铺营业状态。
 * <p>
 * 状态只有一份，存在 Redis 里（键见 {@link com.sky.constant.RedisConstant#SHOP_STATUS}），
 * 不走数据库：它是个随时会被改的开关，放在这里读写最省事。
 * <p>
 * 单独抽成 service 而不是直接写在后台端 controller 里，是因为它有两个调用方：
 * 后台端读它显示"营业中/打烊中"并修改它，用户端下单前也要读它判断是否接了单。
 */
public interface ShopService {

    /**
     * 获取当前营业状态。
     *
     * @return 1 营业，0 打烊；Redis 里没有这个键时返回 0（打烊），不返回 null
     */
    Integer getStatus();

    /**
     * 设置营业状态。
     *
     * @param status 1 营业，0 打烊
     */
    void setStatus(Integer status);
}
