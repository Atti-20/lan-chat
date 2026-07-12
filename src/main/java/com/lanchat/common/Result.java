package com.lanchat.common;

import lombok.Data;

/**
 * 统一返回格式：{"code":200, "data":{}, "msg":"success"}
 */
@Data
public class Result<T> {
    private Integer code;
    private String msg;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.code = 200;
        result.msg = "success";
        result.data = data;
        return result;
    }

    public static <T> Result<T> success() {
        Result<T> result = new Result<>();
        result.code = 200;
        result.msg = "success";
        return result;
    }

    public static <T> Result<T> error(String msg) {
        Result<T> result = new Result<>();
        result.code = 500;
        result.msg = msg;
        return result;
    }

    public static <T> Result<T> error(Integer code, String msg) {
        Result<T> result = new Result<>();
        result.code = code;
        result.msg = msg;
        return result;
    }

    public static <T> Result<T> unauthorized(String msg) {
        Result<T> result = new Result<>();
        result.code = 401;
        result.msg = msg;
        return result;
    }

    public static <T> Result<T> forbidden(String msg) {
        Result<T> result = new Result<>();
        result.code = 403;
        result.msg = msg;
        return result;
    }
}
