--[[
-- 锁的KEY
local key = KEYS[1]
local threadId = ARGV[1]

-- 线程标识
local id = redis.call('get' , key)

--比较
if(id == threadId) then
    return redis.call('del', key)
end
return 0]]

local key = KEYS[1];
local threadId = ARGV[1];
local releaseTime = ARGV[2];

if(redis.call('HEXISTS', key, threadId) == 0) then
    return nil;
end;


local count = redis.call('HINCRBY', key, threadId, -1);
if(count > 0) then
    redis.call('EXPIRE', key, releaseTime);
    return nil;
else
    redis.call('DEL', key);
    return nil;
end