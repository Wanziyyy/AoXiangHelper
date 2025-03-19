package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.ReUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /*
    * 输入手机号，发送验证码
    * */
    @Override
    public Result sendCode(String phone, HttpSession session) {

        // 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 不符合：返回错误信息
            return Result.fail("手机号格式错误");
        }
        // 符合：生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 发送验证码:需要调用第三方平台（可以考虑使用邮箱实现
        log.debug("发送短信验证码成功，验证码：{}", code);
        // 返回ok
        return Result.ok();
    }

    /*
    * 根据手机号和验证码登录
    * */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1、校验手机号
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            // 不符合：返回错误信息
            return Result.fail("手机号格式错误");
        }
        String phone = loginForm.getPhone();

        // 2、比较cache和输入的验证码
        String cachecode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(cachecode == null || !cachecode.equals(code)){
            // 不一致：报错
            return Result.fail("验证码错误");
        }

        // 3、一致：根据手机号查询用户
        /*
        * .one() 方法用于执行查询操作，并返回查询结果的第一条记录。
        * 如果查询结果为空，则返回 null；如果查询结果有多条记录，会抛出 TooManyResultsException 异常
        * */
        User user = query().eq("phone", loginForm.getPhone()).one();
        if (user == null) {
            // 用户不存在：创建新用户并保存
            user = createUserWithPhone(loginForm.getPhone());
        }

        // 4、保存用户信息到redis当中
        /* 如果已经有token了，则刷新token时间，没有就生成token*/
        /* 生成用户token：用于用户登录*/
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        userDTO.setL_token(token);

        /* 将user中的部分登录信息保存到userDTO当中
        * 再将userdto转换为Map<String, Object>类型，存储到redis里面*/
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO ,
                new HashMap<>(),
                CopyOptions
                        .create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName , fieldValue) -> fieldValue.toString()));//所有字段都转成字符串
        stringRedisTemplate.opsForHash().putAll("login:token:" + token, userMap);

        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL ,TimeUnit.MINUTES);
        return Result.ok(token);
    }

    /*
    * 根据手机号创建新用户
    * */
    private User createUserWithPhone(String phone) {
        // 创建新用户
        User user = new User();
        user.setPhone(phone);
        /* 初始的随机名称 */
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 保存
        save(user);
        return user;
    }

    /*
     * 用户签到功能实现
     *  */
    @Override
    public Result sign() {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();

        // 获取日期
        LocalDateTime now = LocalDateTime.now();

        // 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;

        // 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        // 写入redis
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /*
    * 统计连续签到
    * */
    @Override
    public Result signCount() {
        // 获取本月截止的所有签到记录
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();

        // 获取本月截止今天的所有签到记录，返回一个十进制的数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if(result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num == null || num == 0){
            return Result.ok(0);
        }
        // 循环遍历
        int count = 0;
        while(true){
            //让这个数字与1做运算，得到最后一个bit位
            if((num & 1) == 0){
                // 判断bit是否为0
                // 0:未签到,结束
                break;
            }else{
                // 1:已签到，计数器+1
                count ++;
            }
            // 数字右移一位：抛弃最后一位，判断下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    /*
    * 退出登录
    * */
    @Override
    public Result logOut() {
        // 根据token把redis中的信息删除
        String lToken = UserHolder.getUser().getL_token();
        if(lToken == null){
            return Result.fail("退出失败，请检查登录状态！");
        }
        System.out.println(lToken);
        stringRedisTemplate.delete(LOGIN_USER_KEY + lToken);
        return Result.ok("退出成功");
    }


}
