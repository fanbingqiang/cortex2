package com.cortex.lab.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 简易内存限流器
 * - 每用户：30 次/分钟
 * - 全局：100 并发请求
 */
@Slf4j
@Service
public class RateLimiter {

    private static final int PER_USER_LIMIT = 30;       // 每用户每分钟最多请求数
    private static final long WINDOW_MS = 60_000;       // 时间窗口 1 分钟
    private static final int GLOBAL_CONCURRENCY = 100;   // 全局最大并发

    // 用户请求计数：userId -> (windowStart, count)
    private final Map<String, WindowCounter> userCounters = new ConcurrentHashMap<>();
    // 全局并发计数
    private final AtomicInteger globalConcurrency = new AtomicInteger(0);

    /**
     * 尝试获取许可。返回 true 表示允许，false 表示被限流。
     */
    public boolean tryAcquire(String userId) {
        // 1. 全局并发检查
        if (globalConcurrency.incrementAndGet() > GLOBAL_CONCURRENCY) {
            globalConcurrency.decrementAndGet();
            log.warn("全局并发超限: {}", globalConcurrency.get());
            return false;
        }

        // 2. 用户频率检查
        long now = System.currentTimeMillis();
        WindowCounter counter = userCounters.computeIfAbsent(userId, k -> new WindowCounter(now));

        synchronized (counter) {
            if (now - counter.windowStart > WINDOW_MS) {
                // 新窗口
                counter.windowStart = now;
                counter.count = 0;
            }
            if (counter.count >= PER_USER_LIMIT) {
                globalConcurrency.decrementAndGet();
                log.warn("用户 {} 请求频率超限: {} 次/分钟", userId, counter.count);
                return false;
            }
            counter.count++;
        }
        return true;
    }

    /** 释放一个并发槽位 */
    public void release() {
        globalConcurrency.decrementAndGet();
    }

    /** 获取当前全局并发数 */
    public int getGlobalConcurrency() {
        return globalConcurrency.get();
    }

    private static class WindowCounter {
        long windowStart;
        int count;

        WindowCounter(long start) {
            this.windowStart = start;
            this.count = 0;
        }
    }
}