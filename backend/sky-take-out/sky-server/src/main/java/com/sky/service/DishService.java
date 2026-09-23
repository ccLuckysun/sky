package com.sky.service;

import com.sky.dto.DishDTO;

public interface DishService {

    /**
     * 新增菜品及其口味。
     * <p>
     * 方法名带 WithFlavor 是为了点明这个接口会同时写 dish 与 dish_flavor 两张表，
     * 与只写单表的 CategoryService#save 区分开。
     * @param dishDTO 菜品信息，flavors 可为空
     */
    void saveWithFlavor(DishDTO dishDTO);

}
