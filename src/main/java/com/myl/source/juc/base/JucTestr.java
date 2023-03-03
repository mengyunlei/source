package com.myl.source.juc.base;

import java.util.concurrent.locks.ReentrantLock;

public class JucTestr {
    public static void main(String[] args) {
        ReentrantLock lock =new ReentrantLock();
        lock.lock();
        lock.unlock();
    }
}
