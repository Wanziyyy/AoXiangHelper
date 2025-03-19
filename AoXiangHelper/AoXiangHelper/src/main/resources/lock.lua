--[[

--获取锁


local key = KEYS[1];
local threadId = ARGV[1];
local releaseTime = ARGV[2];

if(redis.call('exist', key, threadId) == 0) then
    --不存在，搞个新锁
    redis.call('hset', key, threadId, '1');

    redis.call('expire', key, releaseTime);
    return 1;
end;

--有锁：是自己+1，不是就滚
if(redis.call('hexists', key, threadId) == 1) then
    --是自己的锁，+1
    redis.call('hincrby', key, threadId, '1');
    --设置有效期
    redis.call('expire', key, releaseTime);
    return 1;
end
return 0;
]]
local key = KEYS[1];
local threadId = ARGV[1];
local releaseTime = ARGV[2];

-- 检查锁是否存在
if (redis.call('exists', key) == 0) then
    -- 不存在，创建新锁
    redis.call('hset', key, threadId, '1');
    redis.call('expire', key, tonumber(releaseTime));
    return true;
end

-- 检查是否是自己的锁
if (redis.call('hexists', key, threadId) == 1) then
    -- 是自己的锁，增加锁的计数
    redis.call('hincrby', key, threadId, 1);
    -- 设置有效期
    redis.call('expire', key, tonumber(releaseTime));
    return true;
end

return false;