package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.annotation.AutoFill;
import com.sky.dto.EmployeePageQueryDTO;
import com.sky.entity.Employee;
import com.sky.enumeration.OperationType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EmployeeMapper {

    /**
     * 根据用户名查询员工
     * @param username
     * @return
     */
    @Select("select * from employee where username = #{username}")
    Employee getByUsername(@Param("username") String username);

    /** 根据令牌中的员工 ID 检查账号是否仍然存在、启用。 */
    @Select("select id, status from employee where id = #{id}")
    Employee getById(@Param("id") Long id);

    /**
     * 按主键查询员工详情，供编辑页面回显。
     * password 列不参与查询，口令哈希不会进入内存和响应；需要只判断“存在且启用”时用 getById。
     *
     * @param id 员工id
     * @return 不含口令的员工信息，不存在时为 null
     */
    @Select("select id, username, name, phone, sex, id_number, status,"
            + " create_time, update_time, create_user, update_user from employee where id = #{id}")
    Employee getDetailById(@Param("id") Long id);

    /**
     * 新增员工，SQL 位于 resources/mapper/EmployeeMapper.xml 的 insert 映射。
     * 返回写入行数，数据库自增主键回填至 employee.id。
     * 创建/修改时间与创建/修改人由 AutoFillAspect 依据 @AutoFill(INSERT) 自动填充，调用方不必设置。
     * @param employee
     */

    //插入员工的sql写在了XML映射文件里面
    @AutoFill(OperationType.INSERT)
    int insert(Employee employee);

    /**
     * 分页查询员工，SQL 位于 resources/mapper/EmployeeMapper.xml 的 pageQuery 映射。
     * 调用前必须先用 PageHelper.startPage 设置分页参数，返回的 Page 才能读到总记录数。
     *
     * @param employeePageQueryDTO 员工姓名（可选）与分页参数
     * @return 携带 total 的分页结果
     */
    Page<Employee> pageQuery(EmployeePageQueryDTO employeePageQueryDTO);

    /**
     * 按主键更新员工，SQL 位于 resources/mapper/EmployeeMapper.xml 的 update 映射。
     * 只更新实体中非 null 的字段，因此调用方可以只传 id + 要改的列，
     * 不会把 password 等未赋值的属性写成 null。
     * 修改时间与修改人由 AutoFillAspect 依据 @AutoFill(UPDATE) 自动填充，调用方不必设置。
     *
     * @param employee 必须包含 id
     * @return 受影响行数
     */
    @AutoFill(OperationType.UPDATE)
    int update(Employee employee);

}
