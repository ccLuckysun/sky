package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.PasswordConstant;
import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.EmployeeDTO;
import com.sky.dto.EmployeeLoginDTO;
import com.sky.dto.EmployeePageQueryDTO;
import com.sky.entity.Employee;
import com.sky.exception.AccountLockedException;
import com.sky.exception.AccountNotFoundException;
import com.sky.exception.PasswordErrorException;
import com.sky.exception.LoginFailedException;
import com.sky.exception.BaseException;
import com.sky.exception.UserNotLoginException;
import com.sky.mapper.EmployeeMapper;
import com.sky.result.PageResult;
import com.sky.service.EmployeeService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.util.StringUtils;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class EmployeeServiceImpl implements EmployeeService {

    @Autowired
    private EmployeeMapper employeeMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * 员工登录
     *
     * @param employeeLoginDTO
     * @return
     */
    public Employee login(EmployeeLoginDTO employeeLoginDTO) {
        if (employeeLoginDTO == null
                || !StringUtils.hasText(employeeLoginDTO.getUsername())
                || !StringUtils.hasText(employeeLoginDTO.getPassword())) {
            throw new LoginFailedException(MessageConstant.LOGIN_INPUT_EMPTY);
        }

        String username = employeeLoginDTO.getUsername();
        String password = employeeLoginDTO.getPassword();

        //1、根据用户名查询数据库中的数据
        Employee employee = employeeMapper.getByUsername(username);

        //2、处理各种异常情况（用户名不存在、密码不对、账号被锁定）
        if (employee == null) {
            //账号不存在
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }

        //BCrypt 自带随机盐，必须使用 matches 校验，不能重新哈希后比较字符串。
        if (!passwordEncoder.matches(password, employee.getPassword())) {
            //密码错误
            throw new PasswordErrorException(MessageConstant.PASSWORD_ERROR);
        }

        if (!StatusConstant.ENABLE.equals(employee.getStatus())) {
            //账号被锁定
            throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
        }

        //3、返回实体对象
        return employee;
    }

    /**
     * 新增员工
     *
     * @param employeeDTO
     */
    public void save(EmployeeDTO employeeDTO) {
        if (employeeDTO == null) {
            throw new BaseException("员工信息不能为空");
        }
        validateField(employeeDTO.getUsername(), "用户名", 32);
        validateField(employeeDTO.getName(), "姓名", 32);
        validateField(employeeDTO.getPhone(), "手机号", 11);
        validateField(employeeDTO.getSex(), "性别", 2);
        validateField(employeeDTO.getIdNumber(), "身份证", 18);

        Long currentEmployeeId = BaseContext.getCurrentId();
        if (currentEmployeeId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }
        Employee employee = new Employee();

        //1、对象属性拷贝
        //新增主键由数据库生成，不接受客户端指定 id。
        BeanUtils.copyProperties(employeeDTO, employee, "id");

        //2、设置账号状态，默认正常状态 1表示正常 0表示锁定
        employee.setStatus(StatusConstant.ENABLE);

        //3、默认密码只保存带随机盐的 BCrypt 哈希。
        employee.setPassword(passwordEncoder.encode(PasswordConstant.DEFAULT_PASSWORD));

        //4、设置当前记录的创建时间和修改时间
        LocalDateTime now = LocalDateTime.now();
        employee.setCreateTime(now);
        employee.setUpdateTime(now);

        //5、设置当前记录创建人id和修改人id
        employee.setCreateUser(currentEmployeeId);
        employee.setUpdateUser(currentEmployeeId);

        try {
            if (employeeMapper.insert(employee) != 1) {
                throw new BaseException("新增员工失败");
            }
        } catch (DuplicateKeyException ex) {
            //employee.username 的唯一索引同时保证并发新增时不产生重复账号。
            throw new BaseException(MessageConstant.USERNAME_ALREADY_EXISTS);
        }
    }

    /**
     * 员工分页查询
     *
     * @param employeePageQueryDTO
     * @return
     */
    public PageResult pageQuery(EmployeePageQueryDTO employeePageQueryDTO) {
        if (employeePageQueryDTO == null) {
            throw new BaseException(MessageConstant.PAGE_PARAM_ERROR);
        }
        int page = employeePageQueryDTO.getPage();
        int pageSize = employeePageQueryDTO.getPageSize();
        // page、pageSize 是基本类型，查询参数缺失时默认为 0，会生成非法的 limit，必须先拦截。
        if (page < 1 || pageSize < 1) {
            throw new BaseException(MessageConstant.PAGE_PARAM_ERROR);
        }

        //姓名空白等同于不筛选，否则 like '%%' 的语义会被两端空格改变，查不到任何数据。
        if (employeePageQueryDTO.getName() != null) {
            String name = employeePageQueryDTO.getName().trim();
            employeePageQueryDTO.setName(name.isEmpty() ? null : name);
        }

        try {
            PageHelper.startPage(page, pageSize);
            Page<Employee> result = employeeMapper.pageQuery(employeePageQueryDTO);
            List<Employee> records = result.getResult();
            for (Employee employee : records) {
                //口令哈希只用于登录校验，不能出现在接口响应里；SQL 已不查询该列，这里再兜底清理一次。
                employee.setPassword(null);
            }
            return new PageResult(result.getTotal(), records);
        } finally {
            //分页参数存放在 ThreadLocal，查询未真正执行时不会被 PageHelper 清掉，必须显式清理以免污染复用的线程。
            PageHelper.clearPage();
        }
    }

    /**
     * 启用、禁用员工账号
     *
     * @param status 1 启用，0 禁用
     * @param id     员工id
     */
    public void startOrStop(Integer status, Long id) {
        if (id == null || id <= 0) {
            throw new BaseException(MessageConstant.EMPLOYEE_ID_EMPTY);
        }
        //状态只能是 0/1，否则 update 会把任意值写进 status 列，让账号处于既非启用也非禁用的状态。
        if (!StatusConstant.ENABLE.equals(status) && !StatusConstant.DISABLE.equals(status)) {
            throw new BaseException(MessageConstant.STATUS_ERROR);
        }
        if (employeeMapper.getById(id) == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }

        Long currentEmployeeId = BaseContext.getCurrentId();
        if (currentEmployeeId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }

        Employee employee = Employee.builder()
                .id(id)
                .status(status)
                .updateTime(LocalDateTime.now())
                .updateUser(currentEmployeeId)
                .build();

        //不校验影响行数：MySQL 在状态与原值相同时会返回 0，重复点击“启用”不应被当成失败。
        employeeMapper.update(employee);
    }

    /**
     * 编辑员工信息
     *
     * @param employeeDTO 必须包含 id
     */
    public void update(EmployeeDTO employeeDTO) {
        if (employeeDTO == null || employeeDTO.getId() == null || employeeDTO.getId() <= 0) {
            throw new BaseException(MessageConstant.EMPLOYEE_ID_EMPTY);
        }
        //接口文档把五个字段都标为必填，与新增员工用同一套校验，避免编辑时绕过长度限制。
        validateField(employeeDTO.getUsername(), "用户名", 32);
        validateField(employeeDTO.getName(), "姓名", 32);
        validateField(employeeDTO.getPhone(), "手机号", 11);
        validateField(employeeDTO.getSex(), "性别", 2);
        validateField(employeeDTO.getIdNumber(), "身份证", 18);

        if (employeeMapper.getById(employeeDTO.getId()) == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }

        Long currentEmployeeId = BaseContext.getCurrentId();
        if (currentEmployeeId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }

        Employee employee = new Employee();
        //只拷贝 DTO 暴露的字段，status、password 保持 null，动态 <set> 便不会写到这两列，
        //编辑接口因此无法改变账号启用状态或口令。
        BeanUtils.copyProperties(employeeDTO, employee);
        employee.setUpdateTime(LocalDateTime.now());
        employee.setUpdateUser(currentEmployeeId);

        try {
            //不校验影响行数：提交的内容与库中完全一致时 MySQL 返回 0，未做修改不应报错。
            employeeMapper.update(employee);
        } catch (DuplicateKeyException ex) {
            //username 的唯一索引保证并发编辑时不会出现重复账号。
            throw new BaseException(MessageConstant.USERNAME_ALREADY_EXISTS);
        }
    }

    /**
     * 根据id查询员工信息，供编辑页面回显
     *
     * @param id 员工id
     * @return 不含口令的员工信息
     */
    public Employee getById(Long id) {
        if (id == null || id <= 0) {
            throw new BaseException(MessageConstant.EMPLOYEE_ID_EMPTY);
        }
        Employee employee = employeeMapper.getDetailById(id);
        if (employee == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }
        //SQL 已不查询 password 列，这里再兜底清理一次，避免日后改用其他查询时把哈希带出去。
        employee.setPassword(null);
        return employee;
    }

    private void validateField(String value, String label, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new BaseException(label + "不能为空");
        }
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new BaseException(label + "不能超过" + maxLength + "个字符");
        }
    }

}
