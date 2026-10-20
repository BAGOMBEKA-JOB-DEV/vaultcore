package com.vaultcore.common;

import java.util.List;

public record PageResult<T>(List<T> items, int page, int size, long total) {
    public static void validate(int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100) {
            throw ApiException.badRequest("page must be 0..1000000 and size must be 1..100");
        }
    }
}
