package com.sky.constant;

/**
 * Redis 键常量。
 * <p>
 * 键名集中放这里，不散在各个 controller 里：键名是跨模块的协议（后台端写、用户端读），
 * 写错一个字符不会编译报错也不会运行报错，只会静默读到 null。
 */
public class RedisConstant {

    /**
     * 店铺营业状态：1 营业，0 打烊（取值与 {@link StatusConstant} 一致）。
     */
    public static final String SHOP_STATUS = "SHOP_STATUS";

}
