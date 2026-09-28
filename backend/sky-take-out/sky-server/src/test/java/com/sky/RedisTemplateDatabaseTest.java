package com.sky;

import com.sky.entity.DishFlavor;
import com.sky.vo.DishVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Redis 联调：把五类基本数据（字符串 / 哈希表 / 列表 / 集合 / 有序集合）加上过期时间
 * 各走一遍，用的是 {@link com.sky.config.RedisConfiguration} 配出来的模板和本机 Redis
 * （连接信息见 application-dev.yml 的 sky.redis.*，库号 0）。
 * <p>
 * 与其它 {@code *DatabaseTest} 同一个开关（{@code SKY_DB_TESTS=true}）显式启用，默认跳过：
 * 它连的是本机真实 Redis，而 Redis 不在 CI 或别人机器的默认环境里。
 * <p>
 * 与 MySQL 联调类有一处关键差别：**不能用 {@code @Transactional} 兜底**。Spring 的事务只管
 * JDBC，Redis 的写入不受它管辖，事务回滚带不走键 —— 所以本类的清理全靠 {@link #deleteTestKeys()}
 * 主动删键。这也意味着测试中途 JVM 被杀会留下垃圾键，所以键统一挂 {@code sky:test:} 前缀，
 * 手工清理时按这个前缀扫即可。
 */
@EnabledIfEnvironmentVariable(named = "SKY_DB_TESTS", matches = "true")
@SpringBootTest
class RedisTemplateDatabaseTest {

    private static final String KEY_PREFIX = "sky:test:";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @AfterEach
    void deleteTestKeys() {
        // 测试用的 Redis 键空间很小，且这个前缀只属于本类，KEYS 可以接受；
        // 生产代码里要遍历键请用 SCAN，KEYS 会阻塞整个实例。
        Set<String> keys = stringRedisTemplate.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    /** 字符串：读写、自增、SETNX、追加、删除。 */
    @Test
    void stringOperations() {
        String key = KEY_PREFIX + "string";
        ValueOperations<String, String> ops = stringRedisTemplate.opsForValue();

        ops.set(key, "1");
        assertEquals("1", ops.get(key));

        // 自增是原子的，营业状态那类开关交给它比"读出来加一再写回去"安全
        assertEquals(2L, ops.increment(key));
        assertEquals("2", ops.get(key));
        assertEquals(7L, ops.increment(key, 5));

        // SETNX：键已存在则写入失败，且不改动原值
        assertFalse(ops.setIfAbsent(key, "9"));
        assertEquals("7", ops.get(key));

        ops.append(key, "3");
        assertEquals("73", ops.get(key));

        assertTrue(stringRedisTemplate.delete(key));
        assertEquals(Boolean.FALSE, stringRedisTemplate.hasKey(key));
    }

    /**
     * Json 模板写出去的是**明文 JSON**（不是 JDK 序列化的二进制），所以另一个模板和 redis-cli
     * 都读得到同一个键 —— 这是"值序列化器配对了"的直接证据。
     * <p>
     * 但"明文"不等于"字节相同"：JSON 里数字是裸的，字符串是带引号的。所以同一个键用两个模板读，
     * 字符串值会差一对引号（读到的分别是 {@code 1} 和 {@code "1"}），数字值则完全一致。
     * 混用两个模板读同一个字符串键是踩坑点，这里把两种情形都钉住。
     */
    @Test
    void jsonTemplateWritesPlainJson() {
        String intKey = KEY_PREFIX + "scalar:int";
        redisTemplate.opsForValue().set(intKey, 1);
        // 数字：JSON 里是裸的 1，两个模板读到的字节完全一致
        assertEquals("1", stringRedisTemplate.opsForValue().get(intKey));
        assertEquals(Integer.valueOf(1), redisTemplate.opsForValue().get(intKey));

        String textKey = KEY_PREFIX + "scalar:text";
        redisTemplate.opsForValue().set(textKey, "1");
        assertEquals("1", redisTemplate.opsForValue().get(textKey));
        // 字符串：StringRedisTemplate 拿到的是磁盘上的原始字节，也就是带引号的 JSON 字面量
        assertEquals("\"1\"", stringRedisTemplate.opsForValue().get(textKey));
    }

    /** 哈希表：字段读写、批量写、判存在、计数、删除。 */
    @Test
    void hashOperations() {
        String key = KEY_PREFIX + "hash";
        HashOperations<String, Object, Object> hash = redisTemplate.opsForHash();

        hash.put(key, "name", "测试菜");
        hash.put(key, "status", 1);
        assertEquals(2L, hash.size(key));
        assertEquals("测试菜", hash.get(key, "name"));
        assertEquals(Integer.valueOf(1), hash.get(key, "status"));
        assertTrue(hash.hasKey(key, "name"));
        assertFalse(hash.hasKey(key, "缺失的字段"));

        // 批量写
        hash.putAll(key, Map.of("description", "微辣", "categoryName", "川菜"));
        assertEquals(4L, hash.size(key));
        assertEquals("微辣", hash.get(key, "description"));

        // 字段名与值都是明文 JSON，说明 hashKey/hashValue 两个序列化器确实生效了：
        // 漏设任何一个，这里读出来的都会是 JDK 序列化的二进制乱码，而不是看得懂的文本。
        // 字符串值带引号、数字值不带，与上面 value 的情形一致（都是 JSON 语法本身）。
        assertEquals("\"测试菜\"", stringRedisTemplate.opsForHash().get(key, "name"));
        assertEquals("1", stringRedisTemplate.opsForHash().get(key, "status"));

        assertEquals(1L, hash.delete(key, "description"));
        assertEquals(3L, hash.size(key));
    }

    /** 列表：两端插入、按下标取、按区间取、弹出、按值删除。 */
    @Test
    void listOperations() {
        String key = KEY_PREFIX + "list";
        ListOperations<String, String> ops = stringRedisTemplate.opsForList();

        assertEquals(3L, ops.rightPushAll(key, "a", "b", "c"));
        assertEquals(3L, ops.size(key));
        assertEquals(List.of("a", "b", "c"), ops.range(key, 0, -1));
        assertEquals("b", ops.index(key, 1));

        ops.leftPush(key, "z");
        assertEquals(List.of("z", "a", "b", "c"), ops.range(key, 0, -1));
        assertEquals("z", ops.leftPop(key));
        assertEquals("c", ops.rightPop(key));

        // remove 的第二个参数是删除个数：正数从头删，负数从尾删，0 表示删全部
        assertEquals(1L, ops.remove(key, 1, "b"));
        assertEquals(List.of("a"), ops.range(key, 0, -1));
    }

    /** 集合：去重添加、判成员、并集/交集、删除。 */
    @Test
    void setOperations() {
        String key = KEY_PREFIX + "set";
        SetOperations<String, String> ops = stringRedisTemplate.opsForSet();

        assertEquals(3L, ops.add(key, "a", "b", "c"));
        assertEquals(1L, ops.add(key, "c", "d"));
        assertEquals(4L, ops.size(key));
        assertTrue(ops.isMember(key, "d"));

        String other = KEY_PREFIX + "set:other";
        ops.add(other, "c", "d", "e");
        assertEquals(Set.of("c", "d"), ops.intersect(key, other));
        assertEquals(Set.of("a", "b", "c", "d", "e"), ops.union(key, other));

        assertEquals(1L, ops.remove(key, "a"));
        assertFalse(ops.isMember(key, "a"));
        assertEquals(3L, ops.size(key));
    }

    /** 有序集合：带分数添加、按分数排序取区间、取分数与排名、自增分数、删除。 */
    @Test
    void zSetOperations() {
        String key = KEY_PREFIX + "zset";
        ZSetOperations<String, String> ops = stringRedisTemplate.opsForZSet();

        assertTrue(ops.add(key, "one", 1.0));
        ops.add(key, "two", 2.0);
        ops.add(key, "three", 3.0);
        // 已存在的成员再加一次只改分数，不新增
        assertFalse(ops.add(key, "two", 2.0));
        assertEquals(3L, ops.size(key));

        assertEquals(List.of("one", "two", "three"), List.copyOf(ops.range(key, 0, -1)));
        assertEquals(2.0, ops.score(key, "two"));
        assertEquals(0L, ops.rank(key, "one"));
        assertEquals(2L, ops.rank(key, "three"));
        assertEquals(List.of("three", "two"), List.copyOf(ops.reverseRange(key, 0, 1)));
        assertEquals(Set.of("two", "three"), ops.rangeByScore(key, 2.0, 3.0));

        assertEquals(3.0, ops.incrementScore(key, "one", 2.0));
        assertEquals(3L, ops.size(key));

        assertEquals(1L, ops.remove(key, "one"));
        assertEquals(2L, ops.size(key));
    }

    /** 过期时间：设 TTL、看剩余、去掉 TTL；顺带坐实 Redis 的返回值约定。 */
    @Test
    void expireOperations() {
        String key = KEY_PREFIX + "ttl";
        stringRedisTemplate.opsForValue().set(key, "1");

        assertTrue(stringRedisTemplate.expire(key, Duration.ofMinutes(5)));
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 300, "剩余 TTL 应在 (0, 300] 秒内，实际 " + ttl);

        // -1 表示键存在但没有 TTL，-2 表示键不存在（都是 Redis 的约定，不是错误）
        assertTrue(stringRedisTemplate.persist(key));
        assertEquals(-1L, stringRedisTemplate.getExpire(key, TimeUnit.SECONDS));
        assertEquals(-2L, stringRedisTemplate.getExpire(KEY_PREFIX + "missing", TimeUnit.SECONDS));
    }

    /**
     * 对象与集合的往返：这条守的是 {@link com.sky.config.RedisConfiguration}。
     * 缓存菜品列表就是这个形状（List + 实体 + BigDecimal + LocalDateTime + 子集合），
     * 而 {@code List.of} 这种不可变集合在类型 id 判据没放宽时是"写得进、读不回"的。
     */
    @Test
    void objectAndCollectionValuesRoundTrip() {
        String key = KEY_PREFIX + "dish:list";
        List<DishVO> dishes = List.of(dish(64L, "宫保鸡丁"), dish(65L, "水煮鱼"));

        redisTemplate.opsForValue().set(key, dishes);
        Object back = redisTemplate.opsForValue().get(key);

        assertNotNull(back);
        List<?> list = (List<?>) back;
        assertEquals(2, list.size());
        DishVO first = (DishVO) list.get(0);
        assertEquals(64L, first.getId());
        assertEquals("宫保鸡丁", first.getName());
        assertEquals(0, new BigDecimal("38.00").compareTo(first.getPrice()));
        assertEquals(LocalDateTime.of(2026, 9, 28, 13, 24), first.getUpdateTime());
        assertEquals(DishFlavor.class, first.getFlavors().get(0).getClass());
    }

    private static DishVO dish(Long id, String name) {
        return DishVO.builder()
                .id(id)
                .name(name)
                .categoryId(11L)
                .price(new BigDecimal("38.00"))
                .image("/uploads/2026/09/28/x.png")
                .description("微辣")
                .status(1)
                .updateTime(LocalDateTime.of(2026, 9, 28, 13, 24))
                .categoryName("川菜")
                .flavors(List.of(DishFlavor.builder().id(7L).dishId(id).name("辣度").value("[\"不辣\",\"中辣\"]").build()))
                .build();
    }
}
