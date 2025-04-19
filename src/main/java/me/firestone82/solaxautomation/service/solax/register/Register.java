package me.firestone82.solaxautomation.service.solax.register;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public abstract class Register<T> {
    protected final String name;
    protected final int address;
    protected final int count;
    protected final Class<T> tClass;
}
