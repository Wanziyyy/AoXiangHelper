package com.hmdp.service.impl;
import com.hmdp.config.RabbitMQConfig;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
/*import org.springframework.amqp.rabbit.annotation.Queue;*/
import org.springframework.amqp.core.*;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1、查询blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在！");
        }

        // 查询blog有关的用户
        queryBlogUser(blog);
        /* 查询Blog是否被点赞了,更新一下blog的islike属性 */
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /*
    * 判断当前blog是否被当前用户点过赞
    * */
    private void isBlogLiked(Blog blog) {
        // 获取当前用户\
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return;
        }
        Long userId = UserHolder.getUser().getId();
        // 判断当前用户是否点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /*
    * 一人一赞
    * */
    @Override
    public Result likeBlog(Long id) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 判断当前用户是否点赞
        String key = "blog:liked:" + id;
        // .score(key, value)：根据(key, value)查询redis中是否有值
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            // 没点赞：可以赞：+1并保存到redis
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 数据库操作完成
            if(isSuccess){
                /*zset需要一个分数：用时间戳代替(需要按点赞顺序排序*/
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }else {
            // 点赞了：-1，删除redis
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return null;
    }

    /*
    *查询前五名的点赞用户
    * */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        /*.map(Long::valueOf)：对流中每个元素调用valueif，将其转换为long类型
        * collect(Collectors.toList())：将流中的元素收集到一个集合当中*/
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        /* 需要自定义查询顺序*/
        String idStr = StrUtil.join(",", ids);
        /*
        * userService.query()：数据库查询：
        * .in("id", ids):筛选出id在ids中的记录
        *
        * .last("ORDER BY FIELD(id," + idStr + ")"):last表示追加sql语句，ORDER BY FIELD(id, ...)表示按idstr的id顺序返回结果
        * ↑解决返回结果无序的问题
        *
        * .list()：将查询结果以列表返回
        * */
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()// 无序转有序：orderby
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

/*
    * 发布新笔记：推送到消息队列
    * 推送给所有的粉丝
    **/
  @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店笔记
        boolean isSaveSuccess = save(blog);
        if (!isSaveSuccess){
            return Result.fail("新增笔记失败！");
        }

        // 查询粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // redis实现消费队列：送笔记Id给粉丝
        for (Follow follow :follows) {
            Long userId = follow.getUserId();
            //* 推给每个粉丝的收件箱*//*
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());

        }
        // 返回id
        return Result.ok(blog.getId());
    }


    /*
    * 粉丝查询收件箱
    * 查询收件箱的所有笔记
    * 实现滚动分页
    * */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 查询收件箱
        // 滚动分页查询
        String key = FEED_KEY + userId;
        /*
        * stringRedisTemplate.opsForZSet()：按照分数从高到低获取区间内的元素
        * */
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key,0, max, offset, 2);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        // 解析数据blog_id,score,minTime,offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;// 找min
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            String idStr = tuple.getValue();
            ids.add(Long.valueOf(tuple.getValue()));

            /* 时间戳*/
            long time = tuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else {
                minTime = time; // 覆盖来寻找最小的时间戳
                os = 1;
            }
        }
        // 根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        /* 查询blog的基本信息 */
        for (Blog blog : blogs) {
            // 查询blog有关的用户
            queryBlogUser(blog);
            /* 查询Blog是否被点赞了,更新一下blog的islike属性 */
            isBlogLiked(blog);
        }

        // 封装返回结果
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }

    /*
    * 获取发blog的用户
    * */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
