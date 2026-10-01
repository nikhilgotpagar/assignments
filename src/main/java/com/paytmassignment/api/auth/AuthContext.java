package com.paytmassignment.api.auth;

import com.paytmassignment.domain.model.User;

public final class AuthContext {

    private static final ThreadLocal<User> CURRENT = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(User user) {
        CURRENT.set(user);
    }

    public static User get() {
        return CURRENT.get();
    }

    public static User require() {
        User user = CURRENT.get();
        if (user == null) {
            throw new IllegalStateException("No authenticated user in context");
        }
        return user;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
