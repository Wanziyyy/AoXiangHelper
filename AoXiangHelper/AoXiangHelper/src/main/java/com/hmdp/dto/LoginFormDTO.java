package com.hmdp.dto;

import lombok.Data;

/*
* 注册登录使用的表单对象
* */
@Data
public class LoginFormDTO {
    private String phone;
    private String code;
    private String password;
}
