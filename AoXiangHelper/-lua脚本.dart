-- 锁的KEY
local key = KEYS[1]
local threadId = ARGV[1]

-- 线程标识
local id = redis.call('get' , key)

--比较
if(id == threadId) then
    return redis.call('del', key)
end
return 0