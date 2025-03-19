package com.hmdp.config;

/*拦截器生效*/

/*import com.hmdp.utils.LoginIntercepter;*/
import com.hmdp.utils.LoginIntercepter;
import com.hmdp.utils.RefreshTokenIntercepter;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {


    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /*添加拦截器*/
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        /* 登录的前置拦截 */
        registry.addInterceptor(new LoginIntercepter())
                .excludePathPatterns(
                        "/shop/**",
                        "/shop-type/**",
                        "/vourcher/seckill",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login",
                        "/user/info",
                        "/login",
                        "/api"

                ).order(1);

        /* 维护已登录用户的登录状态 */
        registry.addInterceptor(new RefreshTokenIntercepter(stringRedisTemplate)).order(0);
    }
}
