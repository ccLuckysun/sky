package com.sky.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.sky.json.JacksonObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 模板配置。
 * <p>
 * 键一律用字符串序列化，值用 JSON。这样 {@code redis-cli} 里看到的是人读得懂的键和值：
 * 默认的 JDK 序列化会把键写成 {@code \xac\xed\x00\x05t\x00...} 这种二进制前缀，
 * 缓存出问题时连"键到底叫什么"都读不出来，排查成本极高。
 * <p>
 * 两个模板分工：纯字符串值（例如店铺营业状态那种 "1"/"0"）用自动装配的
 * {@code StringRedisTemplate} 就够；对象和集合用这里这个 {@code redisTemplate}。
 * <p>
 * 这里的 bean 名必须叫 redisTemplate：Boot 的 RedisAutoConfiguration 上有
 * {@code @ConditionalOnMissingBean(name = "redisTemplate")}，同名 bean 存在时它会退让，
 * 两边不会打架（StringRedisTemplate 不受影响，照旧自动装配）。
 */
@Configuration
@Slf4j
public class RedisConfiguration {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        RedisSerializer<Object> valueSerializer = jsonValueSerializer();

        redisTemplate.setKeySerializer(keySerializer);
        redisTemplate.setValueSerializer(valueSerializer);
        // hash 的键值要一起设：只设 key/value 的话，opsForHash 会悄悄退回 JDK 序列化，
        // 于是同一个 Redis 里躺着两种格式的值，排查时更乱。
        redisTemplate.setHashKeySerializer(keySerializer);
        redisTemplate.setHashValueSerializer(valueSerializer);

        log.info("创建 RedisTemplate：键 StringRedisSerializer，值 GenericJackson2JsonRedisSerializer");
        return redisTemplate;
    }

    /**
     * 值为 JSON 的序列化器。
     * <p>
     * 不直接用 {@code new GenericJackson2JsonRedisSerializer()}：它内部自己 new 一个裸的
     * ObjectMapper（已用 javap 核对 spring-data-redis 2.7.2 的字节码），没有注册
     * JavaTimeModule，缓存 DishVO 这种带 LocalDateTime 的对象会直接抛
     * InvalidDefinitionException（"Java 8 date/time type ... not supported by default"）。
     * <p>
     * 改用项目自己的 JacksonObjectMapper，好处有两个：日期格式（yyyy-MM-dd HH:mm）与接口输出
     * 完全一致；它关掉了 FAIL_ON_UNKNOWN_PROPERTIES —— 缓存里存的旧结构在实体增删字段之后仍然
     * 反序列化得动，否则加一个字段就会让存量缓存全部变成异常。这里 new 的是新实例，不影响
     * WebMvcConfiguration 里给消息转换器用的那个。
     * <p>
     * 注意日期格式不带秒：缓存里的 updateTime 读回来是 xx:xx:00。这与接口输出本来就一致
     * （JacksonObjectMapper 就是这么定的），但"读回来的对象与原对象逐字段相等"不成立，
     * 别拿缓存里的时间去做等值比较。
     */
    private static RedisSerializer<Object> jsonValueSerializer() {
        JacksonObjectMapper mapper = new JacksonObjectMapper();

        // null 值写成 {"@class":"java.lang.Object"}。这一步来自无参构造器的实现
        // （它在开默认类型信息之前调用了同一个静态方法），少了它集合里的 null 元素读不回来。
        GenericJackson2JsonRedisSerializer.registerNullValueSerializer(mapper, null);

        // 必须显式开默认类型信息：上面那个 registerNullValueSerializer 之外，带 ObjectMapper 的
        // 构造器什么都不做（无参构造器才会自己开）。不开的话写出去的 JSON 没有 @class，
        // 读回来一律是 LinkedHashMap，调用方 (DishVO) 强转就 ClassCastException。
        // LaissezFaireSubTypeValidator 等于"允许反序列化成任意类"，与 Jackson 默认行为一致，
        // 代价是能往这个 Redis 写入的人可以构造反序列化 gadget：本项目 Redis 与使用方一对一、
        // 内容只由本应用写入，所以按默认放行；哪天它变成共享或对外可达，就换成
        // BasicPolymorphicTypeValidator 白名单（allowIfSubType("com.sky.") 之类）。
        RedisTypeResolverBuilder typer = new RedisTypeResolverBuilder(LaissezFaireSubTypeValidator.instance);
        typer.init(JsonTypeInfo.Id.CLASS, null).inclusion(JsonTypeInfo.As.PROPERTY);
        mapper.setDefaultTyping(typer);

        return new GenericJackson2JsonRedisSerializer(mapper);
    }

    /**
     * 决定哪些类型要写 {@code @class} 类型 id：除了标量，其余都写。
     * <p>
     * 为什么不能直接用 {@code ObjectMapper.DefaultTyping.NON_FINAL}（大多数教程的写法）：
     * 它只给非 final 类写类型 id，而 JDK 的不可变集合全是 final —— {@code List.of(...)} 的实际
     * 类型是 {@code java.util.ImmutableCollections$List12}。于是缓存一个 List.of 会写出**没有类型
     * id** 的 JSON，写入不报错，读的时候才抛
     * {@code SerializationException: need JSON String that contains type id}。
     * 菜品缓存正是 set(key, 列表)，这种"写得进、读不回"的坑必须堵住，所以判据放宽到"除了标量都写"，
     * 实测 List.of / Set.of / Map.of / ArrayList 四种根值都能原样读回（前三种读回来是
     * ArrayList/UnmodifiableSet/UnmodifiableMap，只读不写，无影响）。
     * <p>
     * 为什么反过来要把标量排除掉：不排除时字段上的价格是 {@code ["java.math.BigDecimal",38.00]}、
     * 时间是 {@code ["java.time.LocalDateTime","2026-09-28 13:24"]}，在 redis-cli 里多一层噪音、
     * 白占空间。排除并不会丢还原能力 —— 类型 id 是按**声明类型**判断的，值放进
     * {@code Map<String,Object>} 这类声明为 Object 的位置时照样会写，实测其中的 BigDecimal 读回来
     * 仍是 BigDecimal（统计数据那类缓存正是这种形状）。
     */
    private static class RedisTypeResolverBuilder extends ObjectMapper.DefaultTypeResolverBuilder {

        RedisTypeResolverBuilder(PolymorphicTypeValidator ptv) {
            super(ObjectMapper.DefaultTyping.EVERYTHING, ptv);
        }

        @Override
        public boolean useForType(JavaType type) {
            Class<?> raw = type.getRawClass();
            boolean scalar = raw == String.class
                    || raw == Boolean.class
                    || raw == Character.class
                    || Number.class.isAssignableFrom(raw)
                    || raw.isEnum()
                    || java.time.temporal.Temporal.class.isAssignableFrom(raw);
            return !scalar;
        }
    }
}
