package com.myl.source.juc.test;

import com.myl.source.juc.base.ConcurrentHashMap;
import com.myl.source.juc.base.ReentrantReadWriteLock;
import com.myl.source.juc.base.ThreadLocal;

import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

public class JucTestr {

    private static ThreadLocal<String> loc = new ThreadLocal<String>(){
        public String initialValue(){
            return "hh";
        };
    };
    private static ThreadLocal<String> local = new ThreadLocal();
    private static ThreadLocal<String> local1 = new ThreadLocal();
    public static void main(String[] args) throws InterruptedException {
        HashMap map = new HashMap();
        map.put("111","111");
        ConcurrentHashMap cmap = new ConcurrentHashMap();
        cmap.put("11","11");
        ReentrantLock lock =new ReentrantLock();
        lock.lock();
        lock.newCondition().await();
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
        ArrayBlockingQueue<String> aq = new ArrayBlockingQueue(10);
        LinkedBlockingQueue<String> lq = new LinkedBlockingQueue<>();

    }
}
