package com.myl.source.juc.base;

import java.util.concurrent.locks.ReentrantLock;

public class JucTestr {

    private static ThreadLocal<String> loc = new ThreadLocal<String>(){
        public String initialValue(){
            return "hh";
        };
    };
    private static ThreadLocal<String> local = new ThreadLocal();
    private static ThreadLocal<String> local1 = new ThreadLocal();
    public static void main(String[] args) {
        ReentrantLock lock =new ReentrantLock();
        lock.lock();
        lock.unlock();
        ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
        rwl.readLock().lock();
        rwl.readLock().unlock();
        rwl.writeLock().lock();
        rwl.writeLock().unlock();
        local.set("hahaha");
        local.set("hahaha1");
        System.out.println(local.get());
        System.out.println(local1.get());
        System.out.println(loc.get());
    }
}
