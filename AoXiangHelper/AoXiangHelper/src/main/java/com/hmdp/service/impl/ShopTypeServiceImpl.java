package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService{

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ShopTypeMapper shopTypeMapper;

    public ShopTypeServiceImpl(ShopTypeMapper shopTypeMapper) {
        this.shopTypeMapper = shopTypeMapper;
    }

    /*
    * 查询并返回店铺类型（在首页上半部分展出
    * */
    @Override
    public Result queryBySort() {

        // 从redis查询缓存
        String key = CACHE_SHOP_TYPE_KEY;
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isNotBlank(shopTypeJson)) {
            // 有：直接返回
            List<ShopType> typeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(typeList);
        }
        // reids里面没有：需要查询内存
        List<ShopType> shopTypeList = shopTypeMapper.list();
        if (shopTypeList == null) {
            // 数据库里面也没有：返回错误
            return Result.fail("查询数据为空！");
        }

        // 有：放入redis返回
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypeList));
        return Result.ok(shopTypeList);

    }
}
