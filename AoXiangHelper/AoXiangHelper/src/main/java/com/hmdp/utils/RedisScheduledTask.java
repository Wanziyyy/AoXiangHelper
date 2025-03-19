package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

@Component
public class RedisScheduledTask {
    /*
    * 实现定时加载刷新redis内容(1min一次
    * */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ShopTypeMapper shopTypeMapper;


    public void executeRedisScheduledTask(){
        // 从redis查询缓存
        String key = CACHE_SHOP_TYPE_KEY;

        List<ShopType> shopTypeList = shopTypeMapper.list();
        if(shopTypeList == null || shopTypeList.size() == 0){
            return;
        }
        // 更新redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypeList));

        System.out.println("executeScheduledTask定时刷新店铺类型列表任务，线程ID=" +Thread.currentThread().getId() +
                ",线程名=" + Thread.currentThread().getName());

    }
}
