package com.myl.source.juc.test;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class 顺序打印 {
    public static void main(String[] args) {
        MylTasks mylTasks =new MylTasks();
        mylTasks.taskA();
        mylTasks.taskB();
        mylTasks.taskC();
    }
}

class MylTasks{
    ReentrantLock lock = new ReentrantLock();
    Condition conditionA = lock.newCondition();
    Condition conditionB = lock.newCondition();
    Condition conditionC = lock.newCondition();
    int n = 1;

    public void taskA() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    lock.lock();
                    for(int i=0;i<10;i++){
                        if(n%3!=1){
                            conditionA.await();
                        }
                        for(int j=0;j<3;j++){
                            System.out.println(Thread.currentThread().getName()+"_"+"AAA");
                        }
                        n++;
                        conditionB.signal();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    lock.unlock();
                }
            }
        }, "线程A").start();
    }

    public void taskB() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    lock.lock();
                    for(int i=0;i<10;i++){
                        if(n%3!=2){
                            conditionB.await();
                        }
                        System.out.println(Thread.currentThread().getName()+"_"+"BBB");
                        n++;
                        conditionC.signal();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    lock.unlock();
                }
            }
        }, "线程B").start();
    }

    public void taskC() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    lock.lock();
                    for(int i=0;i<10;i++){
                        if(n%3!=0){
                            conditionC.await();
                        }
                        System.out.println(Thread.currentThread().getName()+"_"+"CCC");
                        n++;
                        conditionA.signal();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    lock.unlock();
                }
            }
        }, "线程C").start();
    }



}
