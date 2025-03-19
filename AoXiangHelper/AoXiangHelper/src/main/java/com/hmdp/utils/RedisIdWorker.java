package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/*随机生成订单号*/
@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP = 1640995200;
    /*序列号位数*/
    private static final int COUNT_BITS = 32;

    /*注入redis:resource或者构造函数*/
    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /*传入业务前缀*/
    public long nextId(String keyPrefix){
        // 时间戳
        long nowSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 序列号
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 拼接并返回

        return timestamp << COUNT_BITS | count;
    }

/*    *//*记录初试时间*//*
    public static void main(String[] args){
        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
        *//*ZoneOffset是对应时区*//*
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println("second = " + second);
    }*/
}
