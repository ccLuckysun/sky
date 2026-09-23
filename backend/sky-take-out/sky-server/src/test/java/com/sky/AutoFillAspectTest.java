package com.sky;

import com.sky.aspect.AutoFillAspect;
import com.sky.context.BaseContext;
import com.sky.dto.EmployeePageQueryDTO;
import com.sky.entity.Category;
import com.sky.entity.Employee;
import com.sky.mapper.CategoryMapper;
import com.sky.mapper.EmployeeMapper;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 公共字段自动填充切面。这里直接用 mock 的连接点驱动 {@link AutoFillAspect#autoFill}，
 * 不启动 Spring：切面只依赖“注解 + 第一个参数 + BaseContext”三样东西，单独测比走一遍 HTTP 更快也更准。
 * 切面是否真的被织入 mapper 代理、字段是否真的落库，由 CategoryDatabaseTest / EmployeeDatabaseTest 验证。
 */
class AutoFillAspectTest {
    private static final long OPERATOR_ID = 7L;

    private final AutoFillAspect aspect = new AutoFillAspect();

    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
    }

    /** 取真实的 mapper 方法做签名：操作类型正是从它上面的 @AutoFill 读出来的。 */
    private MethodSignature signature(Class<?> mapper, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(mapper.getMethod(methodName, parameterTypes));
        when(signature.toShortString()).thenReturn(mapper.getSimpleName() + "." + methodName);
        return signature;
    }

    private JoinPoint joinPoint(MethodSignature signature, Object... args) {
        JoinPoint joinPoint = mock(JoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(args);
        return joinPoint;
    }

    @Test
    void insertFillsAllFourPublicFields() throws Exception {
        BaseContext.setCurrentId(OPERATOR_ID);
        Employee employee = new Employee();

        aspect.autoFill(joinPoint(signature(EmployeeMapper.class, "insert", Employee.class), employee));

        assertNotNull(employee.getCreateTime(), "新增必须写入创建时间");
        assertEquals(employee.getCreateTime(), employee.getUpdateTime(), "新增时创建与修改时间是同一时刻");
        assertEquals(OPERATOR_ID, employee.getCreateUser(), "创建人应为登录人");
        assertEquals(OPERATOR_ID, employee.getUpdateUser(), "修改人应为登录人");
    }

    @Test
    void updateRefreshesOnlyModificationFields() throws Exception {
        BaseContext.setCurrentId(OPERATOR_ID);
        LocalDateTime createdAt = LocalDateTime.of(2000, 1, 1, 0, 0);
        Employee employee = Employee.builder()
                .id(42L).status(0).createTime(createdAt).createUser(1L).build();

        aspect.autoFill(joinPoint(signature(EmployeeMapper.class, "update", Employee.class), employee));

        assertEquals(createdAt, employee.getCreateTime(), "修改不得改动创建时间");
        assertEquals(1L, employee.getCreateUser(), "修改不得改动创建人");
        assertEquals(OPERATOR_ID, employee.getUpdateUser());
        assertNotNull(employee.getUpdateTime());
    }

    @Test
    void updateWithoutLoginContextKeepsTheExistingOperator() throws Exception {
        //没有登录上下文时不能把操作人写成 null：动态 <set> 会跳过 null 列，
        //保留原值才等于“没人改过 update_user”，写成 null 则会把上一个操作人抹掉。
        Employee employee = Employee.builder().id(42L).status(0).updateUser(3L).build();

        aspect.autoFill(joinPoint(signature(EmployeeMapper.class, "update", Employee.class), employee));

        assertEquals(3L, employee.getUpdateUser(), "缺登录上下文时不得覆盖已有的修改人");
        assertNotNull(employee.getUpdateTime(), "时间字段不依赖登录上下文，照常填充");
    }

    @Test
    void insertWithoutLoginContextStillFillsTimes() throws Exception {
        Employee employee = new Employee();

        aspect.autoFill(joinPoint(signature(EmployeeMapper.class, "insert", Employee.class), employee));

        assertNotNull(employee.getCreateTime());
        assertNotNull(employee.getUpdateTime());
        //创建人留空由业务层负责兜底：EmployeeServiceImpl.save 在调用 mapper 之前就要求已登录。
        assertNull(employee.getCreateUser());
        assertNull(employee.getUpdateUser());
    }

    @Test
    void fillsCategoryThroughTheSameReflection() throws Exception {
        BaseContext.setCurrentId(OPERATOR_ID);
        Category category = new Category();

        aspect.autoFill(joinPoint(signature(CategoryMapper.class, "insert", Category.class), category));

        assertEquals(OPERATOR_ID, category.getCreateUser());
        assertEquals(OPERATOR_ID, category.getUpdateUser());
        assertEquals(category.getCreateTime(), category.getUpdateTime());
        assertNotNull(category.getCreateTime());
    }

    @Test
    void methodWithoutTheAnnotationIsLeftAlone() throws Exception {
        BaseContext.setCurrentId(OPERATOR_ID);
        Employee employee = new Employee();

        //切点表达式已经限定了 @AutoFill，这里直接调用是为了覆盖兜底分支：拿不到操作类型就不该猜着填。
        aspect.autoFill(joinPoint(signature(EmployeeMapper.class, "getById", Long.class), employee));

        assertNull(employee.getCreateTime());
        assertNull(employee.getUpdateTime());
        assertNull(employee.getCreateUser());
        assertNull(employee.getUpdateUser());
    }

    @Test
    void missingEntityArgumentIsSkippedInsteadOfFailingTheRequest() throws Exception {
        BaseContext.setCurrentId(OPERATOR_ID);
        MethodSignature signature = signature(EmployeeMapper.class, "insert", Employee.class);

        //mapper 方法没有实体参数时无处可填，不能抛异常把整个请求打断。
        assertDoesNotThrow(() -> aspect.autoFill(joinPoint(signature)));
        //第一个参数为 null 同理。
        assertDoesNotThrow(() -> aspect.autoFill(joinPoint(signature, (Object) null)));
    }

    @Test
    void entityWithoutPublicFieldSettersFailsLoudly() throws Exception {
        BaseContext.setCurrentId(OPERATOR_ID);

        //注解加到了不含公共字段的对象上属于编码错误，必须立刻暴露，
        //静默跳过只会让 create_time 在库里悄悄变成 null，事后无从追查。
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                aspect.autoFill(joinPoint(signature(EmployeeMapper.class, "insert", Employee.class),
                        new EmployeePageQueryDTO())));
        assertTrue(ex.getMessage().contains("setCreateTime"), ex.getMessage());
    }

    @Test
    void doesNotTouchTheLoginContext() throws Exception {
        BaseContext.setCurrentId(OPERATOR_ID);

        aspect.autoFill(joinPoint(signature(EmployeeMapper.class, "insert", Employee.class), new Employee()));

        //切面只读取登录上下文；写入和清理分别是登录拦截器与请求拦截器的职责。
        assertEquals(OPERATOR_ID, BaseContext.getCurrentId());
    }
}
