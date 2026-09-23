package com.sky.service;

import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.result.PageResult;

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

}
