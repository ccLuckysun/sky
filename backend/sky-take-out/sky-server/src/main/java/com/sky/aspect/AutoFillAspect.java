package com.sky.aspect;

import com.sky.annotation.AutoFill;
import com.sky.constant.AutoFillConstant;
import com.sky.context.BaseContext;
import com.sky.enumeration.OperationType;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

//自定义切面，实现公共字段自动填充处理逻辑
/**
 * 公共字段（创建时间、创建人、修改时间、修改人）统一在这里赋值，
 * 业务层不必再逐个 set，新增实体时也只要给 mapper 方法加 @AutoFill 即可。
 */
@Aspect
@Component
@Slf4j
public class AutoFillAspect {

    //切入点（哪些类的哪些方法）
    //切点表达式，既要在设定目录下，也要满足前面加上了AutoFill这个注解的方法，才会被拦截
    @Pointcut("execution(* com.sky.mapper.*.*(..)) && @annotation(com.sky.annotation.AutoFill)")
    public void autoFillPointCut()  {}

    //前置通知,在通知中给公共字段赋值

    @Before("autoFillPointCut()")
    public void autoFill(JoinPoint joinPoint) {
        //获取到当前拦截被拦截方法上的数据库操作类型
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        AutoFill autoFill = signature.getMethod().getAnnotation(AutoFill.class);
        if (autoFill == null) {
            //切点表达式已经限定了注解，走到这里说明方法签名和实际调用的不是同一个方法，
            //此时没有操作类型可依据，硬填反而会写坏数据。
            log.warn("公共字段自动填充跳过：{} 上找不到 @AutoFill", signature.toShortString());
            return;
        }
        OperationType operationType = autoFill.value();

        //mapper 的增改方法都把实体放在第一个参数位，后面可以再跟其他参数
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length == 0 || args[0] == null) {
            log.warn("公共字段自动填充跳过：{} 没有实体参数", signature.toShortString());
            return;
        }
        Object entity = args[0];

        LocalDateTime now = LocalDateTime.now();
        //操作人取自登录时写入 ThreadLocal 的员工id。没有登录上下文时保留实体上的原值、不写成 null：
        //动态 <set> 会跳过 null 列，于是 update_user 保持“谁也没改过”的原状，而不是被清空。
        Long currentId = BaseContext.getCurrentId();

        switch (operationType) {
            case INSERT:
                //新增时四个字段一起写入，创建时间与修改时间同一次操作的同一个时刻。
                fill(entity, AutoFillConstant.SET_CREATE_TIME, LocalDateTime.class, now);
                fill(entity, AutoFillConstant.SET_UPDATE_TIME, LocalDateTime.class, now);
                fillOperator(entity, currentId, true);
                break;
            case UPDATE:
                //修改只刷新修改时间与修改人，创建时间、创建人一旦落库就不再变动。
                fill(entity, AutoFillConstant.SET_UPDATE_TIME, LocalDateTime.class, now);
                fillOperator(entity, currentId, false);
                break;
            default:
                log.warn("公共字段自动填充跳过：{} 的操作类型 {} 未处理", signature.toShortString(), operationType);
                return;
        }

        log.info("公共字段自动填充：{} {}", signature.toShortString(), operationType);
    }

    /**
     * 写操作人字段。currentId 为 null 时保持实体原值不变，insert 还要连带补上创建人。
     */
    private static void fillOperator(Object entity, Long currentId, boolean insert) {
        if (currentId == null) {
            return;
        }
        if (insert) {
            fill(entity, AutoFillConstant.SET_CREATE_USER, Long.class, currentId);
        }
        fill(entity, AutoFillConstant.SET_UPDATE_USER, Long.class, currentId);
    }

    /**
     * 反射调用实体的 setter 写入公共字段。
     * 找不到 setter 说明注解加在了不含公共字段的对象上，属于编码错误，直接抛出而不是静默跳过——
     * 静默跳过只会让 create_time 之类的列在库里悄悄变成 null，等到发现时已经查不出是谁写的了。
     */
    private static void fill(Object entity, String setterName, Class<?> parameterType, Object value) {
        Method setter = ReflectionUtils.findMethod(entity.getClass(), setterName, parameterType);
        if (setter == null) {
            throw new IllegalStateException(entity.getClass().getName()
                    + " 缺少公共字段的 setter：" + setterName + "(" + parameterType.getSimpleName() + ")");
        }
        ReflectionUtils.invokeMethod(setter, entity, value);
    }
}
