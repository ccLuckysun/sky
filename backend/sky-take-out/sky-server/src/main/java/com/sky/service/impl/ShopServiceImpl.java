package com.sky.service.impl;

import com.sky.constant.MessageConstant;
import com.sky.constant.RedisConstant;
import com.sky.constant.StatusConstant;
import com.sky.exception.BaseException;
import com.sky.service.ShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 店铺营业状态，值存在 Redis 的 {@code SHOP_STATUS} 键上。
 * <p>
 * 用 {@link StringRedisTemplate} 而不是 Json 模板：值就是一个 1/0 的标量，
 * 用字符串模板落盘的就是明文的 {@code 1}，redis-cli 里直接可读，也不需要为它绕一层序列化。
 */
@Service
@Slf4j
public class ShopServiceImpl implements ShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Integer getStatus() {
        String value = stringRedisTemplate.opsForValue().get(RedisConstant.SHOP_STATUS);
        if (value == null) {
            //键不存在（首次部署，或 Redis 重启后没恢复数据）时有意义地退回"打烊"，而不是返回 null：
            //接口文档把 data 标成必须，前端按 1 === status 判断营业中，用户端下单前的判断也要拿它比较，
            //null 会在这两处变成 NPE 或误判。默认取打烊是安全侧——宁可少接单，也不要默认开着接单。
            log.info("Redis 中没有 {} 键，营业状态按打烊处理", RedisConstant.SHOP_STATUS);
            return StatusConstant.DISABLE;
        }

        //值只可能来自本类的 setStatus（写入前已校验），正常就是 "1"/"0"。真被 redis-cli 手工改坏时
        //选择报错而不是悄悄当作打烊：后者会让前端显示"打烊中"、运维却以为在营业。修正办法就是重新设置一次。
        try {
            Integer status = Integer.valueOf(value);
            validate(status);
            return status;
        } catch (NumberFormatException e) {
            throw new BaseException(MessageConstant.SHOP_STATUS_ERROR);
        }
    }

    @Override
    public void setStatus(Integer status) {
        //路径变量能传任意整数（以及非数字，后者进不了方法体，由 Spring 按 400 拒绝）。
        //不拦就会把 2、-1 这类值写进 Redis：前端按 1 判断会显示打烊，后端却认为在营业，两边说法不一致。
        validate(status);
        stringRedisTemplate.opsForValue().set(RedisConstant.SHOP_STATUS, String.valueOf(status));
        log.info("店铺营业状态已设置为 {}", status);
    }

    private static void validate(Integer status) {
        if (!StatusConstant.ENABLE.equals(status) && !StatusConstant.DISABLE.equals(status)) {
            throw new BaseException(MessageConstant.SHOP_STATUS_ERROR);
        }
    }
}
