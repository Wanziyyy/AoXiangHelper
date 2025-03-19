package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

public class UserHolder {
    /*里面存储登录的用户信息*/

    /*处理user的线程池*/
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }

}
