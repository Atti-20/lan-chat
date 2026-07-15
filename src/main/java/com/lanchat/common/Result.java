package com.lanchat.common;

import lombok.Data;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 统一返回格式：{"code":200, "data":{}, "msg":"success", "requestId":"req_xxx"}
 */
@Data
public class Result<T> {
    private Integer code;
    private String msg;
    private T data;
    private String requestId;

    public static <T> Result<T> success(T data) {
        Result<T> result = create();
        result.code = 200;
        result.msg = "success";
        result.data = data;
        return result;
    }

    public static <T> Result<T> success() {
        Result<T> result = create();
        result.code = 200;
        result.msg = "success";
        return result;
    }

    public static <T> Result<T> error(String msg) {
        Result<T> result = create();
        result.code = 500;
        result.msg = msg;
        return result;
    }

    public static <T> Result<T> error(Integer code, String msg) {
        Result<T> result = create();
        result.code = code;
        result.msg = msg;
        return result;
    }

    public static <T> Result<T> unauthorized(String msg) {
        Result<T> result = create();
        result.code = 401;
        result.msg = msg;
        return result;
    }

    public static <T> Result<T> forbidden(String msg) {
        Result<T> result = create();
        result.code = 403;
        result.msg = msg;
        return result;
    }

    private static <T> Result<T> create() {
        Result<T> result = new Result<>();
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            Object value = attributes.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                    RequestAttributes.SCOPE_REQUEST);
            if (value != null) result.requestId = String.valueOf(value);
        }
        return result;
    }
}
