package com.hmdp.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    public static final String BLOG_EXCHANGE = "blog.exchange";
    public static final String BLOG_QUEUE_PREFIX = "blog.queue";

    /*
    * bean注解：提示容器，需要接收方法返回的对象，并将其注册为容器中的bean
    * */
    @Bean
    public DirectExchange blogExchange() {
        return new DirectExchange(BLOG_EXCHANGE);
    }

    public Queue createBlogQueue(String userId) {
        return new Queue(BLOG_QUEUE_PREFIX + userId, true);
    }

    public Binding createBlogBinding(Queue queue, DirectExchange exchange, String userId) {
        return BindingBuilder.bind(queue).to(exchange).with(userId);
    }
}