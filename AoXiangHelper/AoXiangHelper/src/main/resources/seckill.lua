
---
---
---判断用户是否有购买资格
---判断优惠券库存是否充足
---
---如果都满足，则扣减库存保存订单
---发送消息到消息队列：用户可以从消息队列中拿数据进行购买
---
--- 优惠券,用户id，订单id
local voucherId = ARGV[1]
local userId =  ARGV[2]
local orderId =  ARGV[3]

--库存、订单key
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 判断库存是否充足
if(tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end

--判断用户是否下单
if(redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

--扣减库存，保存订单
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
-- 发送消息到消息队列，xadd 队列名 消息id 消息键值对 ...
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0