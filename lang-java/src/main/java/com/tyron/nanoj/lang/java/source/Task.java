package com.tyron.nanoj.lang.java.source;

public interface Task<T> {
    void run(T t) throws Exception;
}