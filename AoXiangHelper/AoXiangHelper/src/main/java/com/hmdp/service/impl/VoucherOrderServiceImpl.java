package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */


/*
* redis中创建消费者组与消息队列： XGROUP CREATE stream.orders g1 0 MKSTREAM
 * */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    /*创建消费者组和流*/
    String queueName = "stream.orders"; // 注意这里要跟lua脚本对应（或者传参进去写成活的（但没必要
    String group_name = "g1";
    String consumer_name = "c1";
    public VoucherOrderServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        initStreamAndGroup();
    }

    /*
    * 创建消费者组和流
    * */
    private void initStreamAndGroup() {
        StreamOperations<String, Object, Object> streamOps = stringRedisTemplate.opsForStream();
        try {
            // 创建消费者组
            streamOps.createGroup(queueName, ReadOffset.from("0"), group_name);
        }catch (Exception e){
            // 如果消费者组已存在，则抛出异常
        }
    }

    //线程池
    private static ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /* @PostConstruct：在当前类初始化完毕后立刻执行 */
    @PostConstruct
    private void init(){
            SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /*
    * 获取消息队列的订单信息(Jvm线程读取消息
    * */
    private SimpleRedisLock lock = new SimpleRedisLock("voucher", stringRedisTemplate);


    /*
    * 消费者：
    * 从消息队列中拿取订单信息，实现runnable接口，在初始化开始后立刻执行
    * 由init调用
    * */
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                lock.tryLock(20);
                try {
                    // 获取消息队列的订单信息
                    /*
                    * 根据group_name和consumer_name从stream流中读信息
                    * 一次读一条，如果是空的话就阻塞2s
                    * 指定读取的流名称和偏移量
                    * */
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(group_name, consumer_name),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())// 最近消费，‘>’
                    );
                    // 获取失败：下一次循环
                    if(list == null || list.isEmpty()){
                        // 获取失败了
                        continue;
                    }
                    
                    // 获取成功订单信息：下单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);// 处理订单函数
                    
                    // ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, group_name, record.getId());
                    
                } catch (Exception e) {
                    /*
                    * 异常
                    * */
                    log.error("处理订单异常", e);
                    handlePendingList(); // 处理异常订单函数
                }finally {
                    lock.unlock();
                }
            }
        }

        private void handlePendingList() {
            while(true){
                lock.tryLock(20);
                try {
                    // 获取pending-list的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))// 最近消费，‘>’
                    );
                    // 获取失败：下一次循环
                    if(list == null || list.isEmpty()){
                        // pending中没有异常
                        break;
                    }

                    // 获取成功：下单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);

                    // ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    /*
                     * 异常:直接结束就好
                     * */
                    log.error("处理pending-list订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }finally {
                    lock.unlock();
                }
            }
        }
    }

    /*
    * 拿到订单了：判断能否处理，如果能够的话，判断一人一单
    * */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //处理订单
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        boolean isLock = lock.tryLock();

        if(!isLock){
            log.error("不允许重复下单");
            return;
        }
        try {
            // 获取代理对象
            proxy.save(voucherOrder); // 调用一人一单业务判断下单
            proxy.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherOrder.getVoucherId())
                    // .eq("stock", voucher.getStock()) voucher中的stock是刚刚从数据库中取出的，“stock是现在数据库中的
                    .gt("stock", 0)
                    .update();
        } finally {
            lock.unlock();
        }
    }


    private IVoucherOrderService proxy;

    /*
    * 生产者：
    * 创建订单，发送到消息队列
    * */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 执行lua脚本:（创建订单，发送创建的订单（包括用户、优惠券、订单id到消息队列）
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),
                String.valueOf(orderId)
        );
        // 判断结果是否为0
        int r = result.intValue();
        if(r != 0){
            // 不为0：没有资格购买
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok();
    }

/*
    * 一人一单（lua脚本解决了一人一单和超卖问题
    * */
/*    @Transactional
    public void CreateVoucherOrder(VoucherOrder voucherOrder) {
         // 一人一单
        Long userId = voucherOrder.getUserId();
         // 为同一个用户加悲观锁，实现一人一锁, intern

            int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
            if (count > 0) {
                log.error("用户已经购买了一次");
                return ;
            }

            // 扣减库存
            // 乐观锁：解决超卖
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherOrder.getVoucherId())
                    // .eq("stock", voucher.getStock()) voucher中的stock是刚刚从数据库中取出的，“stock是现在数据库中的
                    .gt("stock", 0)
                    .update();
            if (!success) {
                log.error("库存不足！");
                return ;
            }

            save(voucherOrder);

    }*/
}
