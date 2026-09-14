package com.rappi.buyer.tools;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Every tool returns this shape, including when it fails.
 *
 * Errors are a return value, not an exception. A model that gets a 500 and a
 * stack trace learns nothing; one that gets
 * {ok:false, error:"SKU_NOT_FOUND", suggestions:[...]} can fix its own call and
 * carry on. That is the difference between an agent that recovers and one that
 * gives up halfway through a purchasing decision.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResponse<T>(boolean ok, T data, String error, String detail,
                              List<String> suggestions) {

    public static <T> ToolResponse<T> ok(T data) {
        return new ToolResponse<>(true, data, null, null, null);
    }

    public static <T> ToolResponse<T> error(String code, String detail) {
        return new ToolResponse<>(false, null, code, detail, null);
    }

    public static <T> ToolResponse<T> error(String code, String detail, List<String> suggestions) {
        return new ToolResponse<>(false, null, code, detail, suggestions);
    }
}
