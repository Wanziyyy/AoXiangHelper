package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    private String name;    /*业务名称*/
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";


    private static final DefaultRedisScript<Long> TRYLOCK_SCRIPT;
    static {
        TRYLOCK_SCRIPT = new DefaultRedisScript<>();
        TRYLOCK_SCRIPT.setLocation(new ClassPathResource("lock.lua"));
        TRYLOCK_SCRIPT.setResultType(Long.class);
    }


    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        /*获取锁, value是当前拿到锁的线程id*/
        /*获取当前的线程标识*/
        String threadId = ID_PREFIX + Thread.currentThread().getId();
/*
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId , timeoutSec, TimeUnit.SECONDS);
*/

        /*lua脚本实现上锁*/
        Object success1 = stringRedisTemplate.execute(
                TRYLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId(),
                1200, TimeUnit.SECONDS);
        Boolean success = (Boolean) success1;

        /*避免自动拆箱的空指针异常问题*/
        return Boolean.TRUE.equals(success);
    }
    @Override
    public void unlock(){
        /*调用lua:传入脚本、key和value*/
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());

    }

/*    @Override
    public void unlock() {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if(threadId.equals(id)){
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
        // 释放锁
    }*/
}
