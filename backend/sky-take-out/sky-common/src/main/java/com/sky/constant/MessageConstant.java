package com.sky.constant;

/**
 * 信息提示常量类
 */
public class MessageConstant {

    public static final String LOGIN_INPUT_EMPTY = "用户名和密码不能为空";
    public static final String USERNAME_ALREADY_EXISTS = "用户名已存在";
    public static final String ALREADY_EXISTS = "已存在";
    public static final String PAGE_PARAM_ERROR = "页码和每页记录数必须为正整数";
    public static final String EMPLOYEE_ID_EMPTY = "员工id不能为空";
    public static final String DISH_ID_EMPTY = "菜品id不能为空";
    public static final String DISH_ID_FORMAT_ERROR = "菜品id格式错误";
    public static final String STATUS_ERROR = "状态值不合法，只能为1(启用)或0(禁用)";

    public static final String PASSWORD_ERROR = "密码错误";
    public static final String ACCOUNT_NOT_FOUND = "账号不存在";
    public static final String CATEGORY_NOT_FOUND = "分类不存在";
    public static final String DISH_NAME_ALREADY_EXISTS = "菜品名称已存在";
    public static final String DISH_NOT_FOUND = "菜品不存在";
    public static final String DISH_STATUS_ERROR = "菜品状态值不合法，只能为1(起售)或0(停售)";
    public static final String DISH_IMAGE_NOT_FOUND = "图片文件不存在，请重新上传";
    public static final String ACCOUNT_LOCKED = "账号被锁定";
    public static final String UNKNOWN_ERROR = "未知错误";
    public static final String USER_NOT_LOGIN = "用户未登录";
    public static final String CATEGORY_BE_RELATED_BY_SETMEAL = "当前分类关联了套餐,不能删除";
    public static final String CATEGORY_BE_RELATED_BY_DISH = "当前分类关联了菜品,不能删除";
    public static final String SHOPPING_CART_IS_NULL = "购物车数据为空，不能下单";
    public static final String ADDRESS_BOOK_IS_NULL = "用户地址为空，不能下单";
    public static final String LOGIN_FAILED = "登录失败";
    public static final String UPLOAD_FAILED = "文件上传失败";
    public static final String UPLOAD_FILE_EMPTY = "上传文件不能为空";
    public static final String UPLOAD_TYPE_NOT_ALLOWED = "仅支持 jpg、jpeg、png 格式的图片";
    public static final String UPLOAD_SIZE_EXCEEDED = "上传文件过大，请压缩后重试";
    public static final String SETMEAL_ENABLE_FAILED = "套餐内包含未启售菜品，无法启售";
    public static final String PASSWORD_EDIT_FAILED = "密码修改失败";
    public static final String DISH_ON_SALE = "起售中的菜品不能删除";
    public static final String SETMEAL_ON_SALE = "起售中的套餐不能删除";
    public static final String DISH_BE_RELATED_BY_SETMEAL = "当前菜品关联了套餐,不能删除";
    public static final String ORDER_STATUS_ERROR = "订单状态错误";
    public static final String ORDER_NOT_FOUND = "订单不存在";

}
