# 项目速查：苍穹外卖（waimai）

> 更新日期：2026-09-23。依据当前工作区源码整理；与最新提交是否一致以 `git status --short` 为准。
> 初次整理为静态阅读；员工登录增量已补上令牌校验、数据库账号状态检查和自动化测试。各次增量见文末。

## 1. 先了解这几点

- 项目是外卖管理系统的初始骨架，后端名称为“苍穹外卖”；前端页面标题/manifest 仍有“瑞吉外卖”，是同一份管理端资源。
- 后端为 Java 17、Spring Boot 2.7.3、Maven 三模块工程，根 POM 在 `backend/sky-take-out/pom.xml`，不是仓库根目录。
- **目前业务实现只有员工管理、分类管理、菜品新增和文件上传：员工为登录、退出、分页查询、新增、编辑、启用/禁用和按 id 回显；分类为新增、分页查询、删除、修改、启用/禁用和按类型查询；菜品只有新增（`POST /admin/dish`，会同时写 `dish` 与 `dish_flavor` 两张表，见第 17 节），列表/修改/删除/启停都还没有；另有通用的文件上传（存本地磁盘，见第 16 节）。** 套餐、订单等虽然已有 DTO/Entity/VO 和前端页面，但没有对应后端 Controller/Service/Mapper。
- 前端是 Nginx 配套的已构建管理端，缺少独立 `src/`、`package.json` 和锁文件；不能直接在此执行 `npm install/build`。JS Source Map 内包含部分原始源码，可辅助定位契约。
- 服务默认端口：Nginx `80`、后端 `8080`、MySQL `3306`。浏览器的 `/api/...` 经 Nginx 转为后端 `/admin/...`。
- 仓库缺少建表/初始化 SQL、数据库迁移、CI 和容器部署配置；已有员工登录自动化测试。本机已确认存在 employee 表，但其他环境仍需自行准备数据库。

## 2. 目录与模块

```text
waimai/
├── agent.md                         # 本文：项目上下文与定位索引
├── .gitignore                       # 仓库根忽略规则：.DS_Store 与 nginx 运行时目录
├── .idea/                          # IntelliJ 配置，工程 XML 有意纳入 Git，噪声文件由 .idea/.gitignore 排除
├── backend/sky-take-out/
│   ├── pom.xml                     # 父 POM：模块、Java 版本、依赖版本管理
│   ├── .gitignore                  # 忽略 target 和 IDE 文件，测试源码可纳入 Git
│   ├── sky-common/                 # 公共基础代码，32 个 Java 文件
│   │   └── src/main/java/com/sky/
│   │       ├── constant/           # 消息、状态、JWT claim、初始密码、自动填充常量
│   │       ├── context/            # BaseContext：ThreadLocal 当前员工 ID
│   │       ├── enumeration/        # OperationType：INSERT / UPDATE
│   │       ├── exception/          # BaseException 及业务异常（含 FileUploadException）
│   │       ├── json/               # JacksonObjectMapper（目前已接入 MVC）
│   │       ├── properties/         # JWT、阿里 OSS、微信、本地上传目录配置绑定
│   │       ├── result/             # Result<T>、PageResult
│   │       └── utils/              # JWT、HTTP、OSS 上传、本地磁盘上传、微信支付/退款
│   ├── sky-pojo/                   # 数据模型，49 个 Java 文件
│   │   └── src/main/java/com/sky/
│   │       ├── dto/                # 21 个输入/查询模型
│   │       ├── entity/             # 11 个实体
│   │       └── vo/                 # 17 个响应/统计模型
│   └── sky-server/                 # 可运行服务，23 个主源码 Java 文件
│       ├── src/main/java/com/sky/
│       │   ├── SkyApplication.java
│       │   ├── annotation/AutoFill.java # 标记需要自动填充公共字段的 mapper 方法
│       │   ├── aspect/AutoFillAspect.java # 切面：写入创建/修改时间与操作人
│       │   ├── config/WebMvcConfiguration.java # 拦截器、消息转换器、静态资源（含 /uploads/**）
│       │   ├── config/UploadConfiguration.java # 解析上传目录并构造本地存储工具
│       │   ├── config/SpringfoxConfiguration.java # Actuator/Swagger 扫描兼容
│       │   ├── config/PasswordConfiguration.java # BCrypt PasswordEncoder
│       │   ├── controller/admin/EmployeeController.java
│       │   ├── controller/admin/CategoryController.java
│       │   ├── controller/admin/CommonController.java # 文件上传
│       │   ├── controller/admin/DishController.java # 菜品新增
│       │   ├── handler/GlobalExceptionHandler.java
│       │   ├── interceptor/JwtTokenAdminInterceptor.java
│       │   ├── mapper/             # EmployeeMapper、CategoryMapper、DishMapper、DishFlavorMapper、SetmealMapper
│       │   └── service/            # EmployeeService、CategoryService、DishService 及 impl
│       ├── src/test/java/com/sky/  # 16 个测试类，其中 6 个真实 MySQL 联调需显式启用
│       ├── uploads/                # 上传文件落盘目录（运行时数据，不入 Git，启动自动创建）
│       └── src/main/resources/
│           ├── application.yml
│           ├── application-dev.yml
│           └── mapper/             # EmployeeMapper.xml、CategoryMapper.xml、DishMapper.xml、DishFlavorMapper.xml
└── frontend/
    ├── nginx-mac.conf              # 当前机器的 macOS/Homebrew 部署配置
    └── nginx-1.20.2/
        ├── nginx.exe              # Windows 可执行程序，macOS 不使用它
        ├── conf/nginx.conf         # 原 Windows/相对路径配置
        ├── html/sky/               # 真正的管理端静态资源根目录
        │   ├── index.html
        │   ├── js/                 # app、vendors、dashboard、login、shopTable、404 及 .map
        │   ├── css/、img/、fonts/、media/
        │   └── manifest.json、service-worker.js、precache-manifest.*.js
        ├── docs/、contrib/          # Nginx 随附文档与第三方辅助文件
        └── logs/、temp/            # 日志/PID/临时文件，不是业务源码，已在根 .gitignore 中忽略
```

各后端模块下存在被忽略的 `target/` 构建产物，以 `src/` 为准，不以编译产物判断当前实现。

依赖方向：`sky-server → sky-common + sky-pojo`；common 与 pojo 没有相互项目依赖。

## 3. 技术栈与配置位置

| 内容 | 当前配置/位置 |
| --- | --- |
| Java / 框架 | 父 POM：Java 17、Spring Boot 2.7.3；仍使用 `javax.servlet` |
| 持久层 | MyBatis starter 2.2.0、MySQL 驱动、Druid 1.2.1；没有 JPA |
| SQL 映射 | `classpath:mapper/*.xml`；实体别名包 `com.sky.entity`；下划线转驼峰开启 |
| 分页 / 文档 | PageHelper 1.3.0、Knife4j 3.0.2（Swagger 2），分页已用于员工分页查询 |
| 模型/序列化 | Lombok 1.18.20、Jackson；sky-pojo 单独指定 jackson-databind 2.9.2 |
| 认证 | jjwt 0.9.1，HS256，管理员请求头 `token` |
| 文件存储 | 本地磁盘：`sky.upload.dir`（默认 `uploads`，相对 sky-server 模块目录解析）→ `sky-server/uploads/`，访问入口是 `/uploads/**` 静态资源映射。阿里 OSS 依赖仍在类路径上但未接入，见第 16 节；`LocalFileUtil` 另有带目录围栏的 `isLocalPath`/`exists`/`delete`，供新增菜品失败时补偿删图，见第 18 节 |
| 预置依赖 | Redis、Spring Cache、WebSocket、POI、OSS、微信支付；依赖存在不代表业务已接入。AspectJ（`aspectjweaver` + `aspectjrt`；Spring Boot 的 AOP 自动配置只要类路径上有 `org.aspectj.weaver.Advice` 就会开启，本项目**没有引入 `spring-boot-starter-aop`**）已用于公共字段自动填充，见第 15 节 |
| 监控 | sky-server 已加入 Actuator 依赖；未自定义监控配置；SpringfoxConfiguration 过滤文档扫描中的 PathPattern 路由，保留实际监控端点 |
| 前端 | Source Map 显示 Vue、TypeScript、Vue Router、Vuex、Element UI、Axios，另有图表等依赖 |

`application.yml`：后端端口 `8080`，默认 profile `dev`，允许循环引用；Druid JDBC URL 的数据库时区为 `Asia/Shanghai`，使用 UTF-8。日志为 mapper `debug`、service/controller `info`。另配置了上传相关项：`sky.upload.dir: uploads`、`spring.servlet.multipart.max-file-size/max-request-size: 10MB`、`server.tomcat.max-swallow-size: 10MB`（三者与 nginx 的 `client_max_body_size` 是一组，缺一处就会失败，见第 16 节）。

`application-dev.yml`：`sky.datasource.*` 定义 `localhost:3306/sky_take_out`、用户 `root` 和本地密码。密码和 JWT 签名密钥应在原配置中查阅，不在本文复制。

`sky.jwt.admin-ttl = 7200000`，即 2 小时。`JwtProperties` 也定义用户端配置字段，但目前 YAML 未配置用户端 JWT，服务端也没有用户端认证实现。

`AliOssProperties`、`WeChatProperties` 分别绑定 `sky.alioss`、`sky.wechat`；当前 YAML 没有这些配置。员工业务不调用它们。`UploadProperties` 绑定 `sky.upload`，YAML 里有配置，是唯一一个真正生效的 properties（见第 16 节）。Redis 暂无项目专用配置或业务调用。

## 4. 已实现接口与请求链路

以下路径是直接访问后端 `http://localhost:8080` 时的路径；经 Nginx 时把 `/admin` 换成 `/api`。

| 方法 | 后端路径 | 输入与行为 | 认证 |
| --- | --- | --- | --- |
| POST | `/admin/employee/login` | JSON：`username`、`password`；返回 `id`、`userName`、`name`、`token` | 无 |
| GET | `/admin/employee/page` | Query：`page`、`pageSize` 必填且为正整数，`name` 可选（模糊匹配、两端空白忽略）；返回 `{total, records}` | `token` |
| POST | `/admin/employee` | JSON：`username`、`name`、`phone`、`sex`、`idNumber` 必填，id 可选但新增时忽略；新增员工 | `token` |
| PUT | `/admin/employee` | JSON：`id` 与上述五个字段均必填；改写这五列及审计列，不改 `status` 与口令 | `token` |
| GET | `/admin/employee/{id}` | 返回不含口令的员工详情，供编辑页回显 | `token` |
| POST | `/admin/employee/status/{status}` | 路径 `status` 只能为 1 启用 / 0 禁用，Query `id` 必填；只改状态与审计列 | `token` |
| POST | `/admin/employee/logout` | 直接返回成功，未撤销 JWT | `token` |
| POST | `/admin/category` | JSON：`name`、`type`、`sort`；新增分类，状态固定为禁用 0 | `token` |
| GET | `/admin/category/page` | Query：`page`、`pageSize` 必填，`name`、`type` 可选；返回 `{total, records}` | `token` |
| DELETE | `/admin/category` | Query：`id`；分类下关联了菜品或套餐时拒绝删除 | `token` |
| PUT | `/admin/category` | JSON：`id`、`type`、`name`、`sort`；改写这三列与修改审计列，不动 `status` | `token` |
| POST | `/admin/category/status/{status}` | 路径 `status`，Query `id`；启用/禁用分类 | `token` |
| GET | `/admin/category/list` | Query：`type` 可选；只返回 `status=1` 的分类 | `token` |
| POST | `/admin/dish` | JSON：`name`、`categoryId`、`price`、`image` 必填，`description`、`status`、`flavors[]` 可选；在**一个事务里**同时写 `dish` 与 `dish_flavor`，失败时还会把这次请求引用的本地图片一并删掉；`image` 是 `/uploads/...` 时要求文件真的在磁盘上。成功返回空 `data`，详见第 18 节 | `token` |
| POST | `/admin/common/upload` | `multipart/form-data`，字段名固定为 `file`；只接受 jpg/jpeg/png，落盘到 `sky-server/uploads/yyyy/MM/dd/<uuid>.<ext>`，返回该文件的相对访问路径 | `token` |

响应统一为 `Result<T>`：`{"code":1,"msg":null,"data":...}`；业务失败由 `GlobalExceptionHandler` 捕获 `BaseException` 并返回 `code=0` 和消息，未设置特殊 HTTP 状态。分页模型是 `{total, records}`，员工分页查询已使用。

**上传接口是这条惯例的唯一例外**：失败返回 4xx（400/413）而不是 200，理由见第 16 节 —— 前端 `el-upload` 只按 HTTP 状态码分流，返回 200 会让 `"null"` 落进 `image` 字段。改动前先读那一节。

登录流程：

1. `EmployeeController.login` → `EmployeeServiceImpl.login`。
2. `EmployeeMapper.getByUsername` 使用 `@Select` 查询 `employee` 表。
3. 校验账号存在、**BCrypt matches 验证密码**、状态非禁用。
4. Controller 调用 `JwtUtil.createJWT`，写入 `empId` claim 和过期时间，返回 `EmployeeLoginVO`。

受保护请求流程：

```text
浏览器 /api/employee + token
  → Nginx /admin/employee
  → JwtTokenAdminInterceptor：解析 JWT，写 BaseContext.currentId
  → EmployeeController → EmployeeServiceImpl → EmployeeMapper → MySQL
  → Result<T> JSON
```

`WebMvcConfiguration` 拦截 `/admin/**`，仅排除 `/admin/employee/login`；非 HandlerMethod 放行。缺失、无效、过期、缺少有效 empId 或过期时间的令牌返回 HTTP 401 和 Result.error；有效令牌还会通过 EmployeeMapper.getById 查询数据库，拒绝不存在或未启用的员工。数据库故障不伪装为认证失败。

文件上传是**上传**与**访问**两条独立链路，细节见第 16 节：

```text
上传：<el-upload> POST /api/common/upload（multipart，带 token）
       → Nginx /api/ → 后端 /admin/common/upload → 落盘 sky-server/uploads/yyyy/MM/dd/<uuid>.<ext>
访问：<img src="/uploads/2026/09/23/x.jpg">
       → Nginx location /uploads/（原样转发，不带 token）
       → 后端 /uploads/** 静态资源映射 → 磁盘上的同一个文件
```

两张链路对 nginx 与后端的**运行位置都不敏感**：库里存的是以 `/` 开头的相对路径，换域名、换端口、把整个项目目录搬走都不用改数据或配置。

新增员工由 Service 校验并拷贝 DTO（排除客户端 id），设置启用状态 `1`、默认密码 `123456` 的 BCrypt 哈希。四个审计字段（创建/修改时间、创建/修改人）不在这里设置，由 `AutoFillAspect` 在 mapper 调用前统一写入，见第 15 节。`EmployeeMapper.xml` 执行 INSERT，并通过 `useGeneratedKeys` 回填 ID；接口不返回该实体。

员工分页查询由 Service 校验 `page`、`pageSize` 为正整数（查询参数缺失时基本类型默认为 0，会生成非法 limit），姓名去空白后为空视为不筛选。`EmployeeMapper.pageQuery` 返回 PageHelper 的 `Page`，Service 用 `try/finally` 调用 `PageHelper.clearPage()`，避免查询未执行时分页参数残留在 ThreadLocal。分页 SQL 位于 `EmployeeMapper.xml`，只查询非密码列，排序为 `update_time desc, id desc` 以保证翻页顺序稳定；Service 另外把返回实体的 password 置空，双重保证口令哈希不外泄。

编辑、启用/禁用与按 id 回显都复用 `EmployeeMapper.xml` 里的动态 `update`（`<set>` 只写非 null 字段）：编辑只填五列，状态接口只填 `status`，两者因此都无法越权改到对方负责的列 —— 编辑改不了 `status` 与口令，状态接口也改不了姓名手机号。回显走 `EmployeeMapper.getDetailById`，列清单与 `pageQuery` 相同（不含 `password`）；没有复用拦截器的 `getById`，因为后者在每个受保护请求上都会执行，只需要 `id`、`status`。详见第 14 节。

新增菜品是全项目**第一处 `@Transactional`**：`DishServiceImpl.saveWithFlavor` 把校验、分类存在性检查和两次写库（`dish` 一条 + `dish_flavor` 批量）收在一个事务里，同成同败 —— 否则会留下没有口味的菜品，或挂着不存在菜品的孤儿口味。校验在写任何一张表之前全部做完，因此校验失败时不会留下半截数据。事务由 `SkyApplication` 上的 `@EnableTransactionManagement` 提供（第 6 节）。

**图片文件不在这套事务里，只能靠补偿**：本地磁盘没有两阶段提交，`@Transactional` 管不到 `Files.write`。做法是在事务回滚后删掉这次请求引用的本地上传图（第 18 节），它引出一个新约束：`image` 是 `/uploads/...` 时必须真存在于磁盘，否则拒绝 —— 否则"失败删图"会让客户端手里的路径失效，重提交就会写出一条挂着不存在图片的菜品。

当前实际访问的表为 `employee`、`category`、`dish` 和 `dish_flavor`。`employee` 的 SQL 涉及：`id`、`name`、`username`、`password`、`phone`、`sex`、`id_number`、`status`、`create_time`、`update_time`、`create_user`、`update_user`；`category` 涉及：`id`、`type`、`name`、`sort`、`status` 与同样四个审计列；`dish` 涉及：`id`、`name`、`category_id`、`price`、`image`、`description`、`status` 与四个审计列；`dish_flavor` 涉及：`id`、`dish_id`、`name`、`value`（**没有审计列**，所以它的 mapper 方法不能标 `@AutoFill`）。仓库没有 DDL，字段约束与索引需以实际数据库为准（本机实测：`employee`、`category` 的审计列都可为 NULL，`employee.username`、`category.name`、`dish.name` 各有唯一索引，`dish.name` 那条是**全库唯一**、不按分类区分；`dish.status` 虽有 `DEFAULT 1`，但 XML 是显式列清单，这个默认值不会生效）。

## 5. 预置领域模型（不等于业务已实现）

| 领域 | Entity / DTO / VO 定位 |
| --- | --- |
| 员工 | Employee；EmployeeDTO、EmployeeLoginDTO、EmployeePageQueryDTO、PasswordEditDTO；EmployeeLoginVO |
| 分类 | Category；CategoryDTO、CategoryPageQueryDTO；type 为 1 菜品分类、2 套餐分类 |
| 菜品 | Dish、DishFlavor；DishDTO、DishPageQueryDTO；DishVO、DishItemVO、DishOverViewVO |
| 套餐 | Setmeal、SetmealDish；SetmealDTO、SetmealPageQueryDTO；SetmealVO、SetmealOverViewVO |
| 用户/地址 | User（含微信 openid）、AddressBook；UserLoginDTO；UserLoginVO |
| 购物车 | ShoppingCart；ShoppingCartDTO（dishId、setmealId、dishFlavor） |
| 订单 | Orders、OrderDetail；OrdersDTO、OrdersPageQueryDTO、OrdersSubmitDTO、OrdersPaymentDTO、OrdersConfirmDTO、OrdersCancelDTO、OrdersRejectionDTO；OrderVO、OrderSubmitVO、OrderPaymentVO、OrderStatisticsVO、OrderOverViewVO |
| 统计 | DataOverViewQueryDTO、GoodsSalesDTO；BusinessDataVO、TurnoverReportVO、UserReportVO、OrderReportVO、SalesTop10ReportVO |

实体 ID 主要用 `Long`，金额主要用 `BigDecimal`，时间主要用 `LocalDateTime`。Entity 表达持久化数据，DTO 表达输入，VO 表达输出；大量使用 Lombok `@Data` / `@Builder`。

模型关系：Category 对应 Dish/Setmeal；Dish 对应 DishFlavor；Setmeal 通过 SetmealDish 关联 Dish；User 对应 AddressBook/ShoppingCart/Orders；Orders 对应 OrderDetail。这里是根据实体字段推导的关联，未验证数据库外键。

`Orders` 定义订单状态：1 待付款、2 待接单、3 已接单、4 派送中、5 已完成、6 已取消；支付状态另为 0 未支付、1 已支付、2 退款。部分 DTO 注释存在旧的状态描述，后续实现需统一契约，不能直接照抄注释。

统计 VO 的日期、数量、金额序列多用逗号分隔的字符串，而不是 JSON 数组。`OrderVO` 继承 `Orders`，增加 `orderDishes` 和 `orderDetailList`。

## 6. 公共代码使用情况与已知注意点

- `BaseContext` 用 `ThreadLocal<Long>` 保存当前员工 ID，拦截器在请求开始和 afterCompletion 中清理上下文，避免线程复用串号。
- 新增员工使用 BCrypt（cost=12）存储密码，登录使用 PasswordEncoder.matches 校验。已移除明文比较和 MD5 TODO；JWT 仅用于登录后的身份认证，不负责数据库密码存储。
- 登录和新增员工日志只记录用户名，拦截器不打印 JWT，新增接口不打印身份证、手机号等完整 DTO。
- 退出接口只返回成功，前端清理 Cookie；服务端没有 JWT 黑名单或会话失效逻辑。
- 新增员工在 Service 校验五个必填字段及数据库列长度，用户名唯一索引冲突转为“用户名已存在”。全局处理器将业务异常返回 code=0，将空请求体、JSON/字段类型解析失败返回 HTTP 400 + code=0；其他数据库故障不伪装成用户名重复。
- 审计字段（创建/修改时间、创建/修改人）已由 `AutoFillAspect` 统一填充，业务层不再手动 set；标注了 `@AutoFill` 的 mapper 方法见第 15 节。
- `SkyApplication` 启用注解事务管理。员工与分类的方法都没有显式 `@Transactional`（每个业务只写一张表）；菜品新增是唯一一处显式 `@Transactional`，因为它要同时写 `dish` 与 `dish_flavor`（第 17 节）。
- `JacksonObjectMapper` 定义日期 `yyyy-MM-dd`、日期时间 `yyyy-MM-dd HH:mm`、时间 `HH:mm:ss`，现已在 `WebMvcConfiguration.extendMessageConverters` 中注册并置于转换器首位。注册前 `LocalDateTime` 被序列化成 `[2026,9,22,14,59]` 数组，与接口文档要求的字符串不符；现在时间字段是 `"2026-09-22 14:59"` 形式的字符串。注意 `DEFAULT_DATE_TIME_FORMAT` 不含秒，需要秒级精度时要同时改这个常量。
- `JwtUtil` 负责生成/解析令牌；`HttpClientUtil` 提供 GET、表单 POST、JSON POST；`AliOssUtil` 提供 OSS 上传（未接入）；`LocalFileUtil` 提供本地磁盘上传（已接入，见第 16 节）；`WeChatPayUtil` 提供支付及退款。除 JWT 与 `LocalFileUtil` 外，当前业务没有调用这些工具。
- `WeChatPayUtil` 是组件，调用支付时才读取证书等配置；`AliOssUtil` 没有自动注册 Bean 的配置。接入前需完成配置与调用链。

## 7. 前端与 Nginx

管理端入口是 `frontend/nginx-1.20.2/html/sky/index.html`，不是上一级 Nginx 默认欢迎页。前端 Axios 构建后的 `baseURL` 为 `/api`，超时为 600000 ms；从登录状态取得 token 放入请求头，登录数据保存在 Cookie 中。

页面包括：登录、工作台、数据统计、订单、套餐、菜品、分类、员工及新增/编辑页面。当前 bundle/router 未显式设置 history 模式，按 Vue Router 默认 hash 路由工作；不要仅根据 nginx-mac.conf 的 history 注释判断路由模式。

| 浏览器路径 | macOS Nginx 目标 |
| --- | --- |
| `/` | 本地 `html/sky`，配置有 `try_files ... /index.html` 回退 |
| `/api/` | `http://127.0.0.1:8080/admin/` |
| `/uploads/` | `http://127.0.0.1:8080`（`proxy_pass` **不带 URI**，保留 `/uploads` 前缀；写成 `...:8080/` 会剥掉前缀导致全部 404） |
| `/user/` | `http://127.0.0.1:8080/user/`，后端尚未实现 |
| `/ws/` | `http://127.0.0.1:8080/ws/`，支持 Upgrade 转发，后端尚未实现 |

`nginx-mac.conf` 的 server 块设了 `client_max_body_size 10m`（默认只有 1m）。前端上传组件允许单张图片 2M，不改这一项的话 1~2M 的图会被 nginx 直接 413，请求根本到不了后端，multipart 限制和异常处理器都不会执行，排查方向会被整个带偏。

`nginx-mac.conf` 使用绝对路径、Homebrew `/opt/homebrew` 目录及 `user cc staff`，迁移机器时需要核对用户名、静态目录、mime.types、日志和 PID 路径。Windows 使用附带 `nginx.exe` 与 `conf/nginx.conf`，后者使用相对静态目录。

前端 WebSocket 地址硬编码为 `ws://localhost/ws/` 加随机标识；改域名、HTTPS 或前端端口时，单改 Nginx 不能保证该连接正确。

前端已有但后端尚缺的典型接口：

- 员工：`PUT /employee/editPassword`（改密）。登录、退出、`GET /employee/page`、`POST /employee`、`PUT /employee`、`GET /employee/{id}`、`POST /employee/status/{status}` 均已实现。
- 菜品：`GET /dish/page`（列表页，新增成功后 `$router.push('/dish')` 就会打它）、`POST /dish/status/{status}`、`DELETE /dish`、`PUT /dish`、`GET /dish/{id}`、`GET /dish/list`。`POST /dish`（新增）已实现，见第 17 节 —— 但列表页还没实现，所以新增成功跳回列表页仍然报 404。
- 套餐：`/setmeal/*`。
- 订单与统计：`/order/*`、`/workspace/*`、`/report/*`。
- 营业状态：`/shop/status`、`/shop/{status}`。（`/common/upload` 已实现，见第 16 节）

这些路径会加 `/api` 前缀并由 Nginx 转为 `/admin`。登录成功后页面仍可能出现接口失败，需先检查对应后端是否存在。

前端排查可解析 `js/*.js.map` 的 `sources` 和 `sourcesContent`，重点搜索 `src/api/employee.ts`、`src/utils/request.ts`、`src/store/modules/user.ts`、`src/router.ts` 和 `src/views/...`。Source Map 可辅助阅读，但不等于完整可重建工程；实际运行行为以 `.js` bundle 为准。登录页预填 `admin/123456`，这不能证明数据库中存在该账号。

## 8. 构建、启动与验证入口

前提：JDK 17、Maven、可访问的 MySQL，以及已准备的 `sky_take_out` 数据库/employee 表；macOS 静态部署另需本机 Nginx。数据库初始化材料需另行取得。

从仓库根目录构建并运行（已用 JDK 17 完成打包和临时启动联调）：

```sh
cd backend/sky-take-out
mvn clean package
java -jar sky-server/target/sky-server-1.0-SNAPSHOT.jar
```

也可用 IntelliJ 导入父 POM，启用 Lombok 注解处理，运行 `com.sky.SkyApplication`。本仓库没有 Maven Wrapper。

本机 macOS Nginx 配置提供的命令（依赖实际安装路径及权限，本次未执行）：

```sh
sudo nginx -c /Users/cc/Desktop/waimai/frontend/nginx-mac.conf -t
sudo nginx -c /Users/cc/Desktop/waimai/frontend/nginx-mac.conf
sudo nginx -c /Users/cc/Desktop/waimai/frontend/nginx-mac.conf -s reload
sudo nginx -c /Users/cc/Desktop/waimai/frontend/nginx-mac.conf -s stop
```

按需选择启动/重载/停止命令，不要整段连续执行。管理端访问 `http://localhost/`；Knife4j 入口为 `http://localhost:8080/doc.html`。若前端改用 8081 等端口，要同时检查上述硬编码 WebSocket 地址。

后续代码修改可先在父工程目录执行 `mvn test` / `mvn package`。默认 `mvn test` 执行 113 项、0 失败、7 项跳过（7 个真实 MySQL 联调类按默认配置跳过）；用 mock 的类为 `EmployeeLoginTest` 25 项（认证、新增校验、密码哈希）、`DishSaveTest` 21 项（新增菜品，含前端真实形状的请求体、失败后删图的补偿）、`EmployeeUpdateTest` 11 项、`AutoFillAspectTest` 9 项（公共字段填充切面本身）、`LocalFileUtilTest` 9 项（上传目录解析、路径拼接到存在性判断与删除）、`EmployeeStatusTest` 7 项、`EmployeePageQueryTest` 7 项、`GlobalExceptionHandlerTest` 7 项、`CommonUploadTest` 6 项（上传接口）、`EmployeeGetByIdTest` 4 项。显式启用的联调类为 `EmployeeDatabaseTest`、`EmployeePageQueryDatabaseTest`、`EmployeeStatusDatabaseTest`、`EmployeeUpdateDatabaseTest`、`CategoryDatabaseTest`、`DishDatabaseTest`（5 项）、`DishImageRollbackDatabaseTest`（4 项，**故意不加 `@Transactional`**，因为被验证的正是事务完成回调，见第 18 节）。前六个联调类的测试数据在事务结束后回滚，最后一个自己清库清盘。`SKY_DB_TESTS=true` 下共 130 项全部通过。测试源码未被 `.gitignore` 排除（针对测试的忽略规则已移除），但**"没被忽略"不等于"已提交"**：有若干测试类至今仍是未跟踪状态，见第 9 节末尾的提醒。

只跑 sky-server 时用 `mvn -pl sky-server -am test`，**不要省掉 `-am`**：省掉后 sky-common 取自本地仓库里的旧构件，改过 sky-common（如新增 `FileUploadException`）就会出现一堆 `NoClassDefFoundError`，看起来像代码坏了，其实只是没重新构建依赖模块。

## 9. 后续快速恢复上下文

1. 先读本文，再运行 `git status --short`，以当前源码校正文档快照。
2. 查功能是否存在：看 `sky-server/src/main/java/com/sky/controller/`，不要以 POJO 或前端页面是否存在判断完成度。
3. 员工需求沿 `EmployeeController → EmployeeServiceImpl → EmployeeMapper → EmployeeMapper.xml` 阅读，再核对 sky-pojo 的输入/输出模型。菜品同理：`DishController → DishServiceImpl → DishMapper / DishFlavorMapper → 同名 XML`。
4. 认证问题查看 `WebMvcConfiguration`、`JwtTokenAdminInterceptor`、`JwtUtil`、`BaseContext` 与 `sky.jwt`。
5. 数据库问题查看两份 application YAML、Mapper SQL；不要编辑 `target/classes` 下的配置副本。
6. 页面/API 问题先区分 `80` 端口的 `/api` 与 `8080` 端口的 `/admin`，再查看 Nginx 配置和 Source Map。
7. 图片不显示按顺序查：库里 `image` 列是不是 `/uploads/...`（以 `/` 开头的相对路径）→ 启动日志里"文件上传目录"那一行的绝对路径下有没有这个文件 → 分别请求 `http://localhost:8080/uploads/...`（应为 200 + `image/png`）和 `http://localhost/uploads/...`。**注意前者的状态码骗不了人、后者会骗人**：nginx 没 reload 时 `/uploads/` 会落到 `location /` 的 `try_files` 回退，返回 **200 但 Content-Type 是 `text/html`（内容是 index.html）**，只看状态码会以为是好的。用 `curl -sI` 比 `%{http_code}`，或直接看 `content_type`。
8. 每次功能或结构变化后同步更新本文，尤其是已实现接口、运行步骤和未实现内容。

当前 Git 分支为 `master`。已有提交按时间依次为「苍穹外卖初始代码」→「员工管理页面所有功能完成」→「分类功能导入」→「公共字段自动代码填充」→「图片上传」，一次增量一个提交；具体哈希以 `git log` 为准，本文不复制。是否还有未提交改动一律以 `git status --short` 为准 —— 本文与代码同属一次提交，写字当下就有新的改动，在这里断言"工作区干净"只会立刻过期。

**「图片上传」那次提交漏了文件**：`FileUploadException`、`UploadProperties`、`LocalFileUtil`、`UploadConfiguration` 和 `CommonUploadTest`、`LocalFileUtilTest`、`AutoFillAspectTest`、`CategoryDatabaseTest` 至今仍是未跟踪状态（`git status` 里的 `??`）。后果不是"少几个文件"而是：从已提交内容全新克隆出来**编译不过** —— `CommonController` 引用了没入库的 `FileUploadException` 与 `LocalFileUtil`。下次提交前先 `git add -A` 把这几个补上。第 17、18 节的菜品新增与图片回滚改动同样尚未提交（后者还新增了未跟踪的 `DishImageRollbackDatabaseTest`）。

根 `.gitignore` 忽略 `.DS_Store` 与 nginx 的 `logs/`、`temp/`；`.idea/` 交给 `.idea/.gitignore` 处理，工程配置是有意保留在 Git 中的。


## 10. 员工登录令牌增量（2026-09-22）

- 登录查询：`select * from employee where username = #{username}`，使用 `@Param` 明确绑定用户名；空用户名/密码返回业务错误。该阶段曾沿用明文密码，后续已改为 BCrypt（见第 12 节）。
- 认证状态查询：`select id, status from employee where id = #{id}`；每个受保护请求重新检查账号状态，无需在数据库存储 token 或修改表结构。
- 新增员工仍使用现有 XML INSERT，`create_user` / `update_user` 来自已认证 JWT 的 empId。
- JWT 创建复制传入 claims，避免修改调用方集合；有效期仍为 2 小时，请求头仍为 `token`。退出仍是前端清理 token，未加入服务端撤销功能。
- 本机已只读检查 MySQL `employee` 表结构，与 Mapper 对应；没有执行数据库迁移或修改现有账号。
- JDK 17 下 Maven 测试、打包通过（8 项测试）。若 Maven 被 shell 配置切到 Java 25，会出现 Lombok 生成方法缺失，需指定 JDK 17，例如：

```sh
cd backend/sky-take-out
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home mvn -pl sky-server -am package
```

真实 MySQL 只读联调已通过：管理员登录返回 JWT、携带令牌访问退出接口并查询账号状态、无令牌/错误令牌返回 401、错误密码拒绝登录。当时临时进程已停止；当前运行服务已在密码修复时切换为新版本（见第 12 节）。


## 11. 新增员工接口完善（2026-09-22）

- `POST /admin/employee`，`Content-Type: application/json`，沿用管理员 `token` 请求头。
- `username`、`name`、`phone`、`sex`、`idNumber` 必填且不能是空白。按已核对的数据库列宽限制为 32、32、11、2、18 个字符；未额外强制手机号、身份证或性别取值格式。
- `id` 可选，新增时不使用客户端 id；由 employee 自增主键生成。密码默认 `123456`（数据库存 BCrypt 哈希）、status=1、创建/更新时间为同一时间、创建/修改人为当前登录员工。
- 数据库 INSERT 仍在 `mapper/EmployeeMapper.xml`，没有变更表结构。依赖现有 employee.username 唯一索引防止并发重复，捕获 `DuplicateKeyException` 返回 `{"code":0,"msg":"用户名已存在","data":null}`。
- 成功返回 `{"code":1,"msg":null,"data":null}`。必填/长度校验失败返回 code=0 和中文提示；请求体缺失、JSON 不合法或 id 类型错误返回 HTTP 400 + code=0。
- DTO 已增加 Swagger 必填字段说明，Controller 标注“新增员工”，返回类型为 `Result<Void>`。
- 验证：23 项接口测试 + 1 项真实 MySQL 事务联调全部通过，Maven 打包成功。事务联调验证 INSERT 后的字段、默认值、审计字段、新员工默认密码登录、重复用户名拒绝，结束后自动回滚测试行（MySQL 自增序号可能产生空号）。

默认 Maven 测试不连接数据库。显式启用本机联调（需配置正确的 MySQL 和启用的 admin 员工）：

```sh
cd backend/sky-take-out
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home SKY_DB_TESTS=true mvn -pl sky-server -am package
```


## 12. Mapper 与密码存储修复（2026-09-22）

- `EmployeeMapper.java` 原本就有新增接口 `void insert(Employee employee)`，实际 SQL 位于 XML 中 `namespace=com.sky.mapper.EmployeeMapper`、`id=insert` 的映射；不是绕开 Mapper 操作数据库。本次改为 `int insert(Employee employee)` 并检查写入行数为 1，同时保留自增主键回填。
- sky-server 引入由 Spring Boot BOM 管理的 `spring-security-crypto`，只使用密码工具，不引入整套 Spring Security 拦截链。`PasswordConfiguration` 提供 cost=12 的 BCryptPasswordEncoder。
- 新员工默认密码仍为 123456，但数据库保存 60 字符、带随机盐的 BCrypt 哈希，现有 varchar(64) 可容纳。登录通过 matches 校验；不允许以哈希字符串登录，也不再回退到明文校验。
- 一次性密码迁移 Service、Runner、Mapper 专用方法、XML SQL 和迁移测试均已删除。当前代码只保留日常 BCrypt 新增与登录校验，启动无需迁移参数。
- 本机已正式迁移 2 条原有员工密码，聚合复核为 2 条 BCrypt、2 个不同哈希；原始密码不变，管理员登录及 JWT 返回已验证。没有导出明文密码副本。
- 密码修复时 27 项测试通过；清理迁移代码后保留 25 项员工接口测试、7 项全局异常处理测试和 1 项真实 MySQL 新增/登录事务联调。上述 2 条正式密码迁移已提交，删除迁移代码不会还原数据库密码。
- 已停止旧 IntelliJ 运行进程，启动修复后的 jar 服务监听 8080。本次启动 PID=1600，PID 文件 `/tmp/waimai-password-server.pid`、日志 `/tmp/waimai-password-server.log`；进程号是本次快照，后续操作前重新确认。若改用 IDEA 启动，先停止该 jar 服务，避免端口冲突。

当前版本不再提供明文密码迁移命令；其他环境需准备 BCrypt 格式的员工密码。保留 `PasswordConfiguration.java` 和 `spring-security-crypto` 依赖，供正常新增、登录使用。BCrypt 使用方式参考 [Spring Security 官方密码存储说明](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)。

迁移代码清理验证：JDK 17 执行 `mvn -o -pl sky-server -am clean package` 成功，32 项测试通过、1 项数据库联调按默认配置跳过；已确认生成 JAR 不含迁移类和专用 SQL，保留 BCrypt 配置及员工 INSERT。此次清理未修改数据库，也未重启运行中的服务。


## 13. 员工分页查询（2026-09-22）

- `GET /admin/employee/page`，Query 参数：`page`、`pageSize` 必填且必须 ≥1，`name` 可选。返回 `Result<PageResult>`，即 `data` 为 `{total, records}`；`total` 是筛选后的总记录数，与页码无关。沿用管理员 `token` 请求头。
- 改动文件：`EmployeeController.page`、`EmployeeService.pageQuery`、`EmployeeServiceImpl.pageQuery`、`EmployeeMapper.pageQuery`、`EmployeeMapper.xml` 的 `pageQuery`、`MessageConstant.PAGE_PARAM_ERROR`。
- 分页用 PageHelper：Service 先 `PageHelper.startPage(page, pageSize)`，Mapper 声明返回 `Page<Employee>`，再取 `getTotal()` / `getResult()`。Service 用 `try/finally` 调 `PageHelper.clearPage()`——查询未真正执行时 PageHelper 不会自行清理 ThreadLocal，会污染复用的线程。
- `page`、`pageSize` 是 `int`，查询参数缺失时默认为 0，`PageHelper.startPage(0, ...)` 会算出负的起始行、生成非法 limit。因此 Service 在调用前校验 `page < 1 || pageSize < 1`，返回 `{"code":0,"msg":"页码和每页记录数必须为正整数","data":null}` 而不是 SQL 错误。未对 `pageSize` 设上限，接口文档未约定。
- 姓名用 `like concat('%', #{name}, '%')` 包含匹配；Service 先 `trim`，空白视为不筛选，避免 `like '%  %'` 查不到数据。未转义 `%`、`_` 通配符，姓名中的这两个字符会被当作通配符。
- 排序为 `update_time desc, id desc`。接口文档未规定顺序，这里显式排序是为了翻页稳定：无 `order by` 时 MySQL 不保证行序，可能重复或漏行；追加 `id` 是为了 `update_time` 相同时仍有确定顺序。
- **响应不包含口令哈希**：SQL 的列清单没有 `password`，Service 另外把返回实体的 password 置空兜底。接口文档的响应示例把 `password` 列为必须字段（由实体自动生成），实际返回的是 `"password": null`——前端员工列表只使用 `name`、`username`、`phone`、`status`、`updateTime`、`id`，不使用该字段。若确需返回哈希，删除 Mapper XML 的列清单限制和 Service 中的置空逻辑即可。
- 本次同时把 `JacksonObjectMapper` 注册进 `WebMvcConfiguration.extendMessageConverters`。此前时间字段被序列化成 `[2026,9,22,14,59]` 数组，与接口文档要求的字符串不符，前端“最后操作时间”列会显示成数组内容。
- 测试：`EmployeePageQueryTest` 7 项（mock，覆盖参数校验、姓名筛选与去空白、PageHelper 上下文清理、口令不外泄、鉴权）；`EmployeePageQueryDatabaseTest` 3 项（真实 MySQL，覆盖列清单、like 条件、排序与同值兜底、PageHelper 的 limit/count、越界页、时间字符串格式），测试数据在事务结束后回滚。默认 41 项通过、2 项跳过；`SKY_DB_TESTS=true` 下 43 项全部通过。
- 联调先在 8081 端口用新 JAR 验证（未干扰当时 8080 上的旧进程），随后按用户确认把 8080 重启为新 JAR。当前运行：PID=4172，PID 文件 `/tmp/waimai-page-server.pid`、日志 `/tmp/waimai-page-server.log`（进程号是本次快照，后续操作前重新确认；改用 IDEA 启动前先停掉它以免端口冲突）。
- 经 Nginx 完整链路已验证：`POST http://localhost/api/employee/login` 取到令牌；`GET http://localhost/api/employee/page?page=1&pageSize=2` 返回 `total=3` 与 2 条记录、时间为 `"2026-09-22 14:59"` 字符串、`password` 为 null；无令牌 401；缺参数与 `pageSize=0` 返回业务错误；响应中无 `$2a$` 哈希；`http://localhost/` 返回 200。
- 已知未处理项：`pageSize` 无上限；姓名中的 `%`、`_` 未转义，会被当作 SQL 通配符；查询参数类型不合法（如 `?page=abc`）走 Spring 默认错误响应，不是本项目的 `Result` 结构。


## 14. 员工编辑、启用/禁用与详情回显（2026-09-22）

三个接口补齐了员工管理页面的剩余功能，共用一个动态更新 SQL。

- `POST /admin/employee/status/{status}?id=xx`：启用/禁用。`status` 只能为 1/0，其他值返回 `{"code":0,"msg":"状态值不合法，只能为1(启用)或0(禁用)"}` 且不落库；`id` 缺失、非正数或员工不存在分别返回 `员工id不能为空` / `账号不存在`。只改 `status`、`update_time`、`update_user`（操作人取令牌中的 empId，不是被改的那个人）。**不校验影响行数**：MySQL 在状态与原值相同时返回 0，重复点击“启用”不应报错。前端调用形状为 `POST /employee/status/{status}` + `params:{id}`，与实现一致。
- `PUT /admin/employee`：编辑。Body 为 `id`、`username`、`name`、`phone`、`sex`、`idNumber`，五个字段沿用新增接口的必填与列宽校验（32/32/11/2/18）。`status`、`password` 在传给 Mapper 的实体里保持 `null`，动态 `<set>` 因此不会写这两列 —— 编辑接口在结构上无法改账号状态或口令。用户名撞唯一索引转 `用户名已存在`；提交内容与库中一致时 MySQL 返回 0 行，同样不当作失败。
- `GET /admin/employee/{id}`：编辑页回显。新增 `EmployeeMapper.getDetailById`（列清单同 `pageQuery`，不含 `password`），Service 再把 password 置空兜底；不存在返回 `账号不存在`。**没有复用拦截器的 `getById`** —— 后者在每个受保护请求上都会执行，只该查 `id`、`status`。注意 `@GetMapping("/{id}")` 不能抢走 `/page`、`/logout` 这类固定路径，`EmployeeGetByIdTest` 有专门用例守卫这一点。
- `EmployeeMapper.xml` 新增动态 `update`：`<set>` 只写非 null 字段，供编辑与状态两个接口共用；参数里带 `password` 分支是为将来改密接口预留，当前两个调用方都不会传值。
- 前端契约（从 `js/*.js` 与 Source Map 核对，`src/api/employee.ts`）：编辑页 `init()` 先调 `GET /employee/{id}` 取 `data.data` 填充表单，`code !== 1` 时弹错误提示；表单性别 radio 的 label 是“男/女”，提交前统一转换成 `"1"`/`"0"`，所以库中 `sex` 始终是 0/1，接口按原样返回即可。
- 测试：`EmployeeStatusTest` 7 项、`EmployeeUpdateTest` 11 项、`EmployeeGetByIdTest` 4 项用 mock 覆盖参数绑定、取值校验与鉴权；`EmployeeStatusDatabaseTest` 2 项、`EmployeeUpdateDatabaseTest` 5 项走真实 MySQL，验证只改预期列、口令哈希与 `status` 不受影响、原生样式的“回显→原样提交”往返不产生变化、撞名失败后两行都完好，测试数据事务回滚。默认 65 项通过、4 项联调跳过；`SKY_DB_TESTS=true` 下 72 项全部通过。
- 仓库整理（同一次提交）：新增根 `.gitignore` 忽略 `.DS_Store` 与 `frontend/nginx-1.20.2/` 的 `logs/`、`temp/`；`logs/access.log`、`error.log`、`nginx.pid` 移出跟踪 —— 两个 nginx 配置都不使用这三个文件（`nginx-mac.conf` 指向 `/opt/homebrew/var/log/nginx/`，自带的 `conf/nginx.conf` 又把相关指令注释掉了），且 `nginx.pid` 里是别的机器留下的旧 PID。两个配置类 `PasswordConfiguration`、`SpringfoxConfiguration` 与全部测试类此前未纳入 Git，一并补上：缺前者会让新克隆启动即 `NoSuchBeanDefinitionException`。
- 已知未处理项：**禁用当前登录的管理员会立刻锁死自己** —— 拦截器对每个受保护请求复查账号状态，禁用后再无法通过接口改回，只能直接改库。接口文档未要求拦截，故未加限制，需要时可加“不得禁用自己”的校验。


## 15. 公共字段自动填充（AutoFill，2026-09-22）

四个审计列不再由业务层逐个 `set`，统一由切面写入。

- 切点：`execution(* com.sky.mapper.*.*(..)) && @annotation(com.sky.annotation.AutoFill)` —— 只有落在 `com.sky.mapper` 包下、又标了注解的方法才被拦截。`OperationType`（INSERT/UPDATE）由注解携带，切面读它决定填哪些字段。
- 当前生效的方法：`EmployeeMapper.insert`(INSERT)、`EmployeeMapper.update`(UPDATE)、`CategoryMapper.insert`(INSERT)、`CategoryMapper.update`(UPDATE)、`DishMapper.insert`(INSERT)。`SetmealMapper` 现在只有 count 查询，没有需要填充的方法；以后写套餐的增改，加注解即可复用。`AutoFillConstant` 里那四个 setter 名就是反射调用的目标。
- **注解只能标在"第一个参数是带审计列的实体"的方法上**。`DishFlavorMapper.insertBatch` 收的是 `List<DishFlavor>`，标上去会让切面反射 `ArrayList` 找 `setCreateTime` 而抛 `IllegalStateException`（见下一条的"注解加错对象"），而且 `dish_flavor` 表压根没有审计列 —— 所以它**不加**注解，这是有意为之，不是漏了。
- **通知必须是 `@Before`**：MyBatis 在方法体内部才读取参数对象，前置通知改的是同一个实例，所以改完立刻生效。用 `@Around` 再手动 `proceed()` 也能实现，但没必要。
- INSERT 填四个字段，且 `create_time` 与 `update_time` 取同一个 `now`；UPDATE 只刷 `update_time`、`update_user`，创建时间与创建人一旦落库不再变动。
- 操作人取自 `BaseContext`（登录拦截器写入）。**缺少登录上下文时保留实体上的原值，不写成 null** —— 动态 `<set>` 会跳过 null 列，这恰好等于“没人改过 `update_user`”；写 null 反而会把上一个操作人抹掉。`CategoryDatabaseTest` 在真实 MySQL 上专门验证了这条。
- 拿不到实体参数（方法无参或第一个参数为 null）时打 WARN 后跳过，不抛异常打断请求；而实体缺少 `setCreateTime` 这类 setter 时直接抛 `IllegalStateException` —— 注解加错对象属于编码错误，静默跳过只会让 `create_time` 在库里悄悄变成 null，事后无从追查。
- 业务层原有的 `UserNotLoginException` 检查保留：它挡在 mapper 调用之前，把“未登录”变成明确的业务错误，而不是写出一条没有创建人的记录。真正落库的值由切面在 mapper 调用那一刻从同一个 ThreadLocal 取。
- `CategoryMapper.insert` 的 `@Insert` SQL 直接引用四个公共字段、没有 `<if>` 兜底，必须依赖切面；`EmployeeMapper.insert` 与两个 `update` 的 XML 同样引用这些列。
- 切面是否真的织入 mapper 代理，是 mock 测试证明不了的（mock 掉 mapper 就绕过了整个代理）：`AutoFillAspectTest` 9 项只覆盖切面逻辑本身，端到端由 `EmployeeDatabaseTest`（新增）、`EmployeeStatusDatabaseTest` 与 `EmployeeUpdateDatabaseTest`（修改）、`CategoryDatabaseTest` 4 项（分类新增、修改、启用禁用，以及无登录上下文时不清空修改人）保证 —— 这些用例断言的是库里的真实列值，而业务层已经不再写这些列。
- 改动文件：实现 `aspect/AutoFillAspect`、给两个 Mapper 的四个方法加注解、新增 `AutoFillAspectTest` 与 `CategoryDatabaseTest`；`EmployeeServiceImpl` 与 `CategoryServiceImpl` 删除共 12 行手动填充（`LocalDateTime`、`BaseContext` 的 import 随之移除）；`EmployeeLoginTest`、`EmployeeUpdateTest`、`EmployeeStatusTest` 里“业务层填写审计字段”的断言改为断言业务层**不碰**这些字段。
- 已知未处理项：分类的启用/禁用（`CategoryServiceImpl.startOrStop`）没有像员工那样校验 `status` 只能是 0/1，传其他值会直接写进 `status` 列；接口文档未要求，需要时可补校验。


## 16. 文件上传（本地磁盘存储，2026-09-23）

`POST /admin/common/upload` 按接口文档实现，但存储由教程的阿里云 OSS 换成**本地磁盘 + 相对路径**：库与页面里存的是 `/uploads/...` 这种以 `/` 开头的路径，项目整体搬到哪都不用改配置或数据。

- 请求：`Content-Type: multipart/form-data`，Body 字段名必须正好是 `file`（前端 `el-upload` 没覆写 `name`，用默认的 `'file'`）；沿用管理员 `token` 请求头。响应 `Result<String>`，`data` 形如 `/uploads/2026/09/23/<uuid>.png`。
- 落盘位置：`sky-server/uploads/yyyy/MM/dd/<uuid>.<扩展名>`。存储名重新生成为 UUID，**客户端文件名从不参与路径构造**，所以 `../../evil.jpg` 这类名字会被正常接受并重命名 —— 这里不存在"路径穿越"需要另外做 `..` 字符串过滤。UUID 命名同时充当缓存失效策略（同名覆盖不再发生，换图必然换 URL）。
- 目录放在**模块根目录、与 `target/` 平级**，绝不能放 `target/` 里：`mvn clean` 会把用户上传的图片一起清掉。
- 目录锚点规则（`UploadConfiguration.localFileUtil()`）：
  - `application.yml` 里 `sky.upload.dir: uploads` 是相对路径，刻意不出现绝对路径。
  - 解析：由 `SkyApplication` 的 CodeSource 位置**向上取两级**得到模块目录（IDEA 与 `mvn spring-boot:run` 下是 `sky-server/target/classes/`，打成可执行 jar 后是 `sky-server/target/xxx.jar`，两种情况祖父目录都是模块目录），再用"该目录下存在 `pom.xml`"**校验**；校验不过或探测异常则回退 `System.getProperty("user.dir")` 并打 WARN。配成绝对路径则原样使用（不做校验）。
  - 用固定两级而不是"向上遍历找 `pom.xml`"：无界遍历在"家目录下碰巧有 `pom.xml`"时会静默把上传目录建到别处，比启动失败难查得多。启动日志会打「解析后的绝对路径 + 配置值 + 基准目录」，查"文件到底存哪了"看这一行。
  - 打包成 Boot 可执行 jar 后类加载地址是嵌套形式 `jar:file:/...!/BOOT-INF/classes!/`，**不能**用 `Paths.get(URI)` 直接打开（抛 `FileSystemNotFoundException`，表现为应用起不来），代码里退化到字符串截取第一个 `!` 之前的 jar 路径。
  - 启动时 `Files.createDirectories` 建好目录：静态资源映射指向不存在的目录不会报错，只会让所有图片 404。
- 访问链路：`/uploads/**` 由 `WebMvcConfiguration.addUploadResourceHandler` 映射到该目录。**该类继承 `WebMvcConfigurationSupport`，Boot 的默认静态资源配置已整体退避**，`spring.web.resources.*` / `spring.mvc.static-path-pattern` 配了既不生效也不报错，换目录只能改 `sky.upload.dir` 或那段代码。
- `/uploads/**` **必须留在 JWT 拦截器范围外**（拦截器只注册在 `/admin/**`）：`<img src>` 发出的请求带不上 `token`，把它加进拦截器会让全站图片立刻全裂。该端点匿名可读，所以另挂了一个 `PathResourceResolver` 子类做目录围栏 —— 解析结果 `normalize()` 后必须仍在上传目录内，不依赖框架自身的路径规范化行为。
- **返回值是持久化契约**：`/uploads` 这个前缀定了就不能再改。它以 `/` 开头、由浏览器按当前站点（`http://localhost`）解析，所以库里存的值与域名、端口、项目绝对路径全部解耦。若将来必须改前缀，要同时迁移 `dish.image`、`setmeal.image`、`employee.image` 等列中的既有值。
- **失败返回 4xx，而不是项目惯例的「HTTP 200 + `code:0`」**，这是有意偏离，也是本文最容易被后人"修回去"的一处：前端 `el-upload` 的 XHR 只按状态码分流（`if (status < 200 || status >= 300) return onError(...)`），2xx 时它**不检查 `code`**、无条件执行 `imageUrl = "".concat(t.data)`。若沿用 200 + `{code:0,data:null}`，字符串 `"null"` 会被存进表单的 `image` 字段，而表单校验 `image: {required: true}` 对非空字符串是通过的，最终 `"null"` 落库：页面破图且控制台没有任何报错。返回 4xx 才会走 `handleError` 弹"图片上传失败"、表单值不变。
  - `FileUploadException` → 400：空文件、扩展名不在白名单、写盘失败
  - `MaxUploadSizeExceededException` → 413 + 「上传文件过大，请压缩后重试」（该异常在 handler 匹配之前抛出，但 `doDispatch` 的 try 覆盖它，`@RestControllerAdvice` 能捕获）
  - `MissingServletRequestPartException`（缺 `file` part）→ 400 + 「上传文件不能为空」；不接会落到 Spring 默认的 400 空 body，不是 `Result` 结构
- 体量限制是三处配合，少改一处都会以看不懂的形式失败：

  | 位置 | 值 | 不改会怎样 |
  | --- | --- | --- |
  | nginx `client_max_body_size`（server 块） | `10m`（默认 1m） | 1~2M 的图被 nginx 直接 413，请求到不了后端，后端配置和异常处理器都不执行，排查方向被整个带偏 |
  | `spring.servlet.multipart.max-file-size` / `max-request-size` | `10MB` | Boot 默认 1M，而前端组件允许 2M，必然失败 |
  | `server.tomcat.max-swallow-size` | `10MB` | 超限时 Tomcat 不读完 body 直接断连，客户端只看到 `ERR_CONNECTION_RESET`、拿不到那个 JSON。**不能写 `-1`**：Boot 的 `DataSize` 不接受负数，应用起不来 |

- 只按扩展名白名单（`jpg`/`jpeg`/`png`，比较前转小写 —— macOS 上 `.JPG` 很常见）过滤，**不嗅探文件内容**。上传目录是运行时数据，**不入 Git**（`backend/sky-take-out/.gitignore` 的 `sky-server/uploads/`），启动时自动创建，因此不需要 `.gitkeep`。
- 改动文件：新增 `sky-common` 的 `exception/FileUploadException`、`properties/UploadProperties`、`utils/LocalFileUtil` 与 `sky-server` 的 `config/UploadConfiguration`，填充已有的 `controller/admin/CommonController` 空壳；修改 `MessageConstant`（补三条提示）、`WebMvcConfiguration`、`GlobalExceptionHandler`、`application.yml`、`frontend/nginx-mac.conf`、`backend/sky-take-out/.gitignore`；新增测试 `CommonUploadTest` 6 项、`LocalFileUtilTest` 6 项（都不连数据库，默认 `mvn test` 就会跑）。测试总数由 75 项变为 87 项，`SKY_DB_TESTS=true` 下 97 项。
- 测试重点：成功用例断言返回路径匹配 `^/uploads/\d{4}/\d{2}/\d{2}/<uuid>\.(jpg|jpeg|png)$`，且磁盘上确实存在该文件、字节与上传一致（不要断言 `$.msg` 不存在 —— 它存在且为 null）；`../../evil.jpg` 断言的是"**文件名不影响落盘位置**"而不是"路径穿越被拒绝"，后者会诱导实现去做多余且脆弱的 `..` 字符串检查；空文件与 `evil.sh` 断言 `is4xxClientError()`，方法名写明理由，专门守住上面那条"防 `"null"` 落库"的回归。`LocalFileUtilTest` 覆盖锚点校验与回退、绝对路径直通、`fileLocation` 尾部斜杠（`toUri()` 只在目录此刻已存在时才补 `/`，静态资源位置缺了它会出现"冷启动 404、重启一次自愈"）、URL 用 `/` 拼接（仓库自带 `nginx.exe`，Windows 是明确部署场景，用 `File.separator` 会拼出反斜杠导致 404）。
- **改完 `nginx-mac.conf` 必须 `-s reload`**，否则新加的 `location /uploads/` 不生效。而且这个失败很隐蔽：请求会落到 `location /` 的 `try_files` 回退，返回 **200 但 Content-Type 是 `text/html`**（body 是 `index.html`），只看状态码像是成功的，浏览器里则是图片全裂。
- 联调实测（后端已全部验证过；**nginx 段尚未验证**，见下条）：`POST http://localhost/api/common/upload` 返回 `{"code":1,"msg":null,"data":"/uploads/2026/09/23/<uuid>.png"}`；`GET http://127.0.0.1:8080/uploads/...` → 200 + `image/png`；无 token → 401；`.sh` → 400；空文件 → 400 + 「上传文件不能为空」；12MB → 413 且响应体是那个 JSON；缺 `file` part → 400；穿越探针 `/uploads/../../../etc/passwd` 与 `/uploads/%2e%2e%2f...` → 400，`/uploads//etc/passwd` → 404；`/uploads/` 目录本身 → 404。
- **nginx 段待办**：`location /uploads/` 已经写进 `nginx-mac.conf`，但本机 nginx master 的启动时间早于该改动，**还没 reload**，所以此刻 `http://localhost/uploads/...` 实际返回的是 `index.html`（200 + `text/html`，见上面那条隐蔽失败）。执行下面这条后，才可以说是全链路打通：

  ```sh
  sudo nginx -c /Users/cc/Desktop/waimai/frontend/nginx-mac.conf -t && \
  sudo nginx -c /Users/cc/Desktop/waimai/frontend/nginx-mac.conf -s reload
  curl -sI http://localhost/uploads/2026/09/23/b40b9548-acc7-4dd2-ab0d-ba2b2920b6c4.png | head -3
  ```

- 当前运行态（本次快照）：后端 jar 以 PID 11638 运行，PID 文件 `/tmp/waimai-upload-server.pid`、日志 `/tmp/waimai-upload-server.log`。进程号是快照，后续操作前重新确认；改用 IDEA 启动前先停掉这个 jar，否则 8080 端口冲突。
- 已知未处理项：
  - `/uploads/**` **匿名可读**，猜到 UUID 就能取到图片。这是有意取舍（`<img src>` 带不上 token），UUID 不可枚举，但不要把它当成访问控制。
  - 库里 `dish.image` 仍有本次改造之前留下的 24 条阿里云 OSS 绝对 URL，本次**没有迁移**（外链还能正常显示）。写清理逻辑时注意不要用"删除所有非 `/uploads` 前缀的文件"这类判断，那会漏掉它们；断网或 OSS 侧清理后这些图会失效。
  - 上传文件**没有清理、配额、审计日志**，也不在任何备份范围内（它不在 Git 里）。第 18 节补上了唯一一处自动删除：新增菜品失败回滚时删掉这张没被用上的图；除此之外的孤儿文件（上传完就放弃表单、进程被杀）依旧只增不减。
  - 只按扩展名过滤，伪装成 `.png` 的非图片会被接受并原样存盘。
  - 单独把 jar 拷到别处运行（不在 Maven 模块目录下）时锚点校验不过，目录会落在启动时的工作目录并按 `user.dir` 解析，想固定就显式配绝对路径。


## 17. 新增菜品（POST /admin/dish，2026-09-23）

菜品管理页「新增菜品」表单提交的接口，也是本项目**第一处 `@Transactional`**：它要同时写 `dish` 与 `dish_flavor` 两张表。

- 请求：`Content-Type: application/json`，Body 为 `DishDTO`（`name`、`categoryId`、`price`、`image` 必填，`description`、`status`、`flavors[]` 可选）；沿用管理员 `token` 请求头。成功返回 `{"code":1,"msg":null,"data":null}` —— 接口文档把 `data` 标为非必须、前端只判 `code`，所以不回传新菜品 id。
- 前端契约里的四个坑（逐条从 `js/shopTable.*.js.map` 里 `src/views/dish/addDishtype.vue` 的原始源码核对过，测试就按这个形状发请求）：
  - `price` 发的是**字符串**（`"6.00"`）而不是数字；Jackson 能直接反序列化成 `BigDecimal`，服务端别按 `double` 处理金额。
  - 新增时 `status` 被前端**硬编码为 0（停售）**：`params.status = this.actionType === 'add' ? 0 : (...)`，页面上没有这个输入项。
  - `flavors[].value` 是 `JSON.stringify(obj.value)` 出来的**字符串**（`'["无糖","多糖"]'`）：接口定义就是字符串，服务端不解析、不校验 JSON 合法性，原样存进 `varchar(255)`。
  - 请求体里多带一个 `DishDTO` 里没有的 `code`（商品码）字段（表单没有对应 `prop`，值恒为空串）。它能被接受是因为 `JacksonObjectMapper` 关掉了 `FAIL_ON_UNKNOWN_PROPERTIES`；**mock 测试必须显式把这个转换器装进 `MockMvc`**（`standaloneSetup` 不会执行 `WebMvcConfiguration.extendMessageConverters`，默认 `ObjectMapper` 会直接 400）。
- 校验在写任何一张表之前一次做完（含每一个口味）：名称必填 ≤32、`categoryId` 必填且 >0、价格 >0 且小数位 ≤2 且 ≤99999999.99、图片必填 ≤255、描述可选 ≤255、`status` 只能是 0/1、口味名称必填 ≤32、口味值必填 ≤255。
  - 顺序是有意的：校验 → 查分类存在 → 查登录上下文 → 才 insert。任何一条不合法时，库里都不会多出半条菜品。
  - 价格的"小数位 ≤2"用 `stripTrailingZeros()` 判，所以 `12.500` 这种等值写法放行（`decimal(10,2)` 存进去就是 `12.50`），只有真正超过两位小数的才拒。
  - 价格上限取的是列上限（`decimal(10,2)` = 8 位整数），而前端表单自己限到 6 位整数（`/^([1-9]\d{0,5}|0)(\.\d{1,2})?$/`）。**有意不跟前端对齐**：接口文档没约定上限，拒掉数据库装得下的值是凭空的限制。
  - 界面确实能造出"口味名为空"的请求：`addFlavore()` 推入的是 `{name:'', value:[]}`，而表单 `rules` 只覆盖 `name/categoryId/image/price/code`，根本没校验 flavors。这里选择**拒绝**（「口味名称不能为空」）而不是静默丢掉这一行 —— 存进去只会在用户端多出一个没有名字的口味组。
- `categoryId` 会真去 `category` 表查一次（`CategoryMapper.countById`）：两表没有外键，不查就会写出一条在任何分类列表里都查不到的菜品。查不到返回「分类不存在」。
- 写库顺序与主键回填：`DishMapper.insert`（XML，`useGeneratedKeys` 回填 `Dish.id`）→ 用回填的 id 覆盖每个口味的 `dishId`、并把客户端传来的口味 `id` 一律置 null → `DishFlavorMapper.insertBatch` 用 `<foreach>` 一条 SQL 批量插入。拿不到回填主键时直接报错，不让数据库去抛 `dish_id` 非空约束。
- `DishFlavorMapper.insertBatch` 单参数必须写 `@Param("flavors")`，XML 里也写 `collection="flavors"`：不加注解时 MyBatis 只暴露 `collection`/`list` 两个键，`<foreach collection="flavors">` 会在**第一个真正带口味的请求**上抛 `BindingException`，而这条路径在校验先行的设计里直到联调才会走到。
- 唯一索引与重名：`dish.name` 上是**全库唯一索引** `idx_dish_name`（不按分类区分），撞了返回「菜品名称已存在」。这里必须显式 `catch (DuplicateKeyException)`：`GlobalExceptionHandler` 里那个 `SQLIntegrityConstraintViolationException` 分支会把索引冲突渲染成 ``'麻婆豆腐'已存在``，直接带着索引里的双引号。并发新增由同一个唯一索引兜住。
- `status` 缺省时显式写 0，而不是留 null 让列默认值兜底：`dish.status` 的 `DEFAULT 1` **只在 INSERT 完全不写这一列时生效**，而这里的 XML 是显式列清单，写 null 就是真 null。null 的后果是不一致：列表页按 `status` 是否为 0 二分显示启售/停售，会把 null 显示成启售，而按 `status = 1` 过滤的查询又查不到它。
- 改动文件：`MessageConstant`（补 `DISH_NAME_ALREADY_EXISTS`、`CATEGORY_NOT_FOUND`、`DISH_STATUS_ERROR`）、`CategoryMapper`（`countById`）、`DishMapper`（`insert` + `@AutoFill(INSERT)`）；新增 `mapper/DishFlavorMapper`、`mapper/DishMapper.xml`、`mapper/DishFlavorMapper.xml`、`service/DishService`、`service/impl/DishServiceImpl`；**填充已有的** `controller/admin/DishController` 空壳（提交到索引里的版本是 `public class DishController {}`，一个注解都没有 —— 忘记补 `@RestController`/`@RequestMapping` 会让整个接口 404，而这从文件内容上完全看不出来）。
- 测试：`DishSaveTest` 15 项（mock，默认 `mvn test` 就跑：成功路径、主键不回填时的行为、无口味不调 `insertBatch`、逐项校验失败且断言**没有落任何库**、`status` 透传与缺省、重名、无 token 401）；`DishDatabaseTest` 5 项（真实 MySQL：四个审计列确实由切面填、回填主键落到 `dish_flavor.dish_id`、`value` 与传入的 JSON 字符串一致）。测试总数由 87 项变为 103 项、6 项跳过；`SKY_DB_TESTS=true` 下 117 项全部通过。
- 联调实测（**后端与经 nginx 两段都验证过**）：`POST /admin/dish` 返回 `{"code":1,...}`，库中 `dish` 一行（`price` 12.50、`status` 0、`create_user`/`update_user` = 当前管理员、`create_time` = `update_time`）+ `dish_flavor` 两行且 `dish_id` 正确；六条负例（重名、分类不存在、价格为 0、缺图片、口味名为空、`status=2`）全部返回 `code=0` + 对应中文提示，无 token 返回 401，且**没有任何一条留下半截数据**；`http://localhost/api/dish` 同样返回 `code=1`（`/api` 这条代理本来就通，与第 16 节那个待 reload 的 `/uploads/` 是两回事）。联调插入的行已删除，基线恢复 24 菜品 / 24 口味。
- 已知未处理项：
  - **只有新增**。`GET /dish/page`（列表页）以及 `POST /dish/status/{status}`、`DELETE /dish`、`PUT /dish`、`GET /dish/{id}`、`GET /dish/list` 都还没有，所以新增成功跳回列表页仍然报 404 —— 数据确实进库了，但页面看起来是坏的。验证数据只能查库或走接口。
  - 分类的 `type` 没校验：`categoryId` 指向一个套餐分类（`type=2`）也会写成功。接口文档没要求，前端下拉框只给 `type=1`，所以只能由直接调接口造出来；它会在列表页显示成挂着套餐分类名的菜品。
  - 同一菜品里的**重名口味不拒**（`dish_flavor` 没有唯一索引），口味的 `value` 也不校验是不是合法 JSON。
  - 4 字节字符（emoji）会以 `Incorrect string value` 500 收场：`dish.name` 等列是 `utf8mb3`，长度校验通过也存不下去。项目全局现状（`employee` 表同样如此），不在这里单独处理。
  - 库里 24 条 `dish_flavor` 有 **11 条是孤儿**：`dish_id` 指向 2~10 这些不存在的菜品（现存菜品 id 从 46 起），另有 14 条菜品没有任何口味。这是本次改造**之前**就存在的数据（本次没有任何删除菜品的代码路径），按 `dish_id` 查口味的正常逻辑看不到它们，但写"统计口味数量"这类脚本时要留意。


## 18. 新增菜品的事务一致性：图片文件的补偿回滚（2026-09-23）

需求是「菜品新增接口的两张表写入与图片上传要同步，失败一起回滚，保证一致性」。两张表在第 17 节已经是一个事务；这一节补的是**图片文件**。

先说清一件不能含糊的事：**MySQL 的事务包不住磁盘写文件**。本地文件系统没有两阶段提交，`@Transactional` 管不到 `Files.write`，事务管理器也没有"回滚时顺手撤一个文件操作"的能力。所以文件这一侧只有两条路：**补偿**（回滚后删掉）或**对账**（事后扫孤儿）。本次做的是补偿，因为对账需要定时任务，而本仓库连 `@EnableScheduling` 都没有。

还有一条接口事实决定了只能这么做：前端是**已经构建好的 bundle**（仓库里没有 `src/`、没有 `package.json`，不能重新构建），它先 `POST /admin/common/upload` 拿到路径、再 `POST /admin/dish` 提交表单，**是两个独立请求**。后端无法把两步合成一个可回滚的单元，也不该为此改接口契约。上传自己失败时一行表都没碰，不需要补偿；要补的只有"图已经在磁盘上、菜品却没进库"这一段。

- **回滚删图**：`DishServiceImpl.saveWithFlavor` 在动任何表之前注册 `TransactionSynchronizationManager.registerSynchronization(...)`，只在 `afterCompletion(STATUS_ROLLED_BACK)` 时删。为什么要等回调而不是在 `catch` 里删：**只有事务确实回滚了才删，提交成功时绝不能删**（否则每新增一道菜就把自己的图删掉）。注册点放在校验之前，所以校验失败、分类不存在、重名、口味写入失败、数据库断开——所有失败路径都被覆盖。`TransactionSynchronization` 在 Spring 5.3 里不是废弃类型（废弃的是 `TransactionSynchronizationAdapter`），只覆写需要的那个默认方法即可。
- **没有活动事务时的退化**：`isSynchronizationActive()` 为 false 时（单测直接 `new` 出 service，或将来有人删掉 `@Transactional`）等不到回调，代码在 `catch` 里就地删。这条分支不是可有可无的兜底：它正是 `DishSaveTest` 唯一能覆盖到的删除路径（那一层没有 Spring 事务），另一条则由第 8 节那个联调类覆盖。
- **删之前先查"还在不在用"**：`dishMapper.countByImage(image) > 0` 就不删。回滚之后本次请求写的那一行已经没了，此时还能查到引用，说明这张图是别的菜品在用的——客户端完全可以提交一个已存在的 `image` 路径、再因为重名失败。宁可少删一张孤儿图，也不能删掉别人正在用的图。
- **只处理 `/uploads/...`**：`LocalFileUtil.isLocalPath` 之外一律直接返回。库里 24 条存量值是阿里云 OSS 绝对 URL（第 16 节），它们不是文件路径，不能拿去删。`countByImage` 目前只查 `dish`：`setmeal.image` 没有写接口、`employee.image` 在 mapper 里根本不出现；**将来这两处开始写本地上传图时，这个在用判断必须一起扩展**，否则会误删被套餐引用的图。
- **新约束：`image` 是本地路径时必须真存在**（`DISH_IMAGE_NOT_FOUND` =「图片文件不存在，请重新上传」，走 `BaseException` → HTTP 200 + `code:0`，与其它业务提示一致）。这条不是顺手加的校验，而是"失败即删图"能成立的前提：重名这类失败会让图被删掉，而表单里还留着那个路径，用户改个名字重提交就会写出一条**挂在已被删掉的图上的菜品**——比孤儿文件更糟，而且没人会发现。有了这条，那种不一致状态在任何时刻都不会存在。**代价要说明白：重名失败后重提交需要重新选一次图**（前端 `el-upload` 不会自动重传）。
- **删图这道围栏与读图同一套判据**：`LocalFileUtil.storedFile` 把 `/uploads/...` 还原成绝对路径时 `normalize()` 之后必须仍在上传根目录内，越界（`/uploads/../x`、`/uploads//etc/passwd`）拒绝并记 WARN。入参来自客户端提交的 JSON，跟 `<img src>` 那侧一样按不可信输入对待。删除方法**永不抛异常**，删不掉只记 WARN——它会在事务完成回调里被调用，那里抛出去会盖掉真正的业务错误。
- 改动文件：`sky-common` 的 `LocalFileUtil`（补 `isLocalPath`/`exists`/`delete` 与私有 `storedFile`）、`MessageConstant`（补 `DISH_IMAGE_NOT_FOUND`）；`sky-server` 的 `DishServiceImpl`（注册回调、`deleteUploadedImage`、图片存在性校验、把原实现体抽成私有 `save`）、`DishMapper`（`countByImage`）。`@Transactional` 保持默认（只对 `RuntimeException`/`Error` 回滚），不加 `rollbackFor`：本项目所有失败路径抛的都是 `RuntimeException`。
- 测试：`DishSaveTest` 由 15 项增到 21 项（成功不删图、失败删图、校验失败也删图、被别的菜品引用时不删、本地路径不存在被拒、OSS 绝对地址既不被拒也不被当文件删）；`LocalFileUtilTest` 由 6 项增到 9 项（存在/删除跟着磁盘走、非本地路径一律忽略、越界路径拒绝**并断言根目录外的文件没被删掉**）；新增 `DishImageRollbackDatabaseTest` 4 项（真实 MySQL：回滚后图消失且**同一次成功新增的图不受牵连**、提交时图还在、被引用的图不删、不存在的本地路径被拒）。测试总数由 103 项变为 113 项（7 项跳过）、`SKY_DB_TESTS=true` 下由 117 项变为 130 项。
  - 那个新联调类**故意不加 `@Transactional`**：被验证的行为本身就是"事务结束时发生什么"，套一层测试事务只会把回调推迟到测试方法结束之后，断言必然落空。所以它真实提交，并在 `@AfterEach` 里按名字删掉自己造的行与文件（`DishDatabaseTest` 也补了同样的文件清理，因为它现在必须真上传图片才能通过校验）。
- 联调实测（**后端与经 nginx 两段都验证过**）：上传 P1 → 同名新增成功，P1 留在磁盘；上传 P2 → 用同一个菜名再提交 → `{"code":0,"msg":"菜品名称已存在"}`，**P2 已从磁盘消失、P1 未受牵连**（日志里能看到 `删除上传文件：/uploads/...` 那一行，且回调里没有异常）；提交磁盘上不存在的 `/uploads/...` → 「图片文件不存在，请重新上传」；`http://localhost/api/dish` 走同一套逻辑。联调插入的行与文件都已删除，基线恢复 24 菜品 / 24 口味，上传目录里只剩 `.DS_Store`。
- 已知未处理项（**这条链路到此为止，剩下的窗口是有意不补的**）：
  - **进程在"文件已落盘"和"数据库提交"之间被杀掉**（kill -9、断电）：没有任何进程内回调能执行，磁盘上留下孤儿文件。
  - **表单被放弃**：图传了、没提交就关页面，或者换了一张图。上传与新增是两个请求，后端无从知道这张图会不会被用；前端换图时不删旧图，那是前端的事。
  - **回滚时数据库不可用**：`countByImage` 这时多半也会失败，被 catch 成一条 WARN，图就不删了（宁可留孤儿，也不能因为清理失败把原始错误顶掉）。
  - **"是不是本次请求上传的"判断不了**：只能判断"库里有没有行引用它"。上传接口要管理员 token，能走到这一步的是自己人；但若客户端引用的是别人刚上传、还挂在别人表单里的路径，回滚会把它删掉。要根治只能给上传做一张元数据表（把"谁传的、有没有被用掉"记进库，让文件生命周期真正跟着事务走），本次不做。
  - **前端的重提交体验变差**：如上，重名失败后同一张图已被删，必须重新选图才能再提交。这是"不留孤儿文件"换来的，接口给的是明确提示而不是静默落库。
