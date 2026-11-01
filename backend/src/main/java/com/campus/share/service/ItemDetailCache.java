package com.campus.share.service;

import com.campus.share.vo.ItemView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.campus.share.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.redisson.api.RLock;
import org.redisson.client.RedisException;
import java.util.concurrent.TimeUnit;
@Component
public class ItemDetailCache {
    private static final Logger log=LoggerFactory.getLogger(ItemDetailCache.class);
    // 明确表示“数据库里不存在”，区别于 Redis 没有这个键
    private static final String NULL_MARKER = "__NULL__";

    // 空值缓存只保留 30 秒
    private static final Duration NULL_TTL = Duration.ofSeconds(30);
    private static final DefaultRedisScript<List> SNAPSHOT=new DefaultRedisScript<>("""
        redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2])
        return {redis.call('GET', KEYS[1]), redis.call('GET', KEYS[2]) or ''}
        """,List.class);

    private static final DefaultRedisScript<Long> FILL=new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
        redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
        return 1
        """,Long.class);

    private static final DefaultRedisScript<Long> INVALIDATE=new DefaultRedisScript<>("""
        redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
        return redis.call('DEL', KEYS[2])
        """,Long.class);
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final ObjectMapper json;
    private final String prefix;
    private final Duration ttl;
    private final long generationMillis;
    // 缓存故障后，在当前实例的保护窗口内拒绝非事务详情读取。
    private final AtomicLong cacheUnavailableUntil=new AtomicLong(System.nanoTime());

    public ItemDetailCache(StringRedisTemplate redis,
                           ObjectMapper json,
                           RedissonClient redisson,
                           @Value("${app.redis.key-prefix}") String prefix,
                           @Value("${app.items.cache-ttl:2m}") Duration ttl) {
        if (ttl.toMillis()<1 || ttl.compareTo(Duration.ofHours(1))>0)
            throw new IllegalArgumentException("Item cache TTL must be between 1 ms and 1 hour");
        this.redis=redis;
        this.json=json;
        this.prefix=prefix+":item:";
        this.ttl=ttl;
        this.redisson = redisson;
        this.generationMillis=Math.max(600000,ttl.toMillis()*2);
    }

    public ItemView get(Long id, Supplier<ItemView> loader) {
        // 事务中的读取继续走数据库，不向共享缓存暴露未提交数据。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return loader.get();
        }

        // 本次采用保护数据库的策略：
        // 缓存故障期间返回 503，不让所有请求直接回源。
        if (cacheUnavailable()) {
            throw cacheBusy();
        }

        // 第一次检查：命中缓存的请求不需要加锁。
        CacheResult first = readCache(id);
        if (first.hit()) {
            return first.item();
        }

        RLock lock = redisson.getLock(rebuildLockKey(id));
        boolean acquired = false;

        try {
            try {
                // 最多等待 1 秒获取锁。
                // 不指定 leaseTime，使用 Redisson 的 watchdog 续期机制。
                acquired = lock.tryLock(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw cacheBusy();
            } catch (RedisException exception) {
                failed(exception);
                throw cacheBusy();
            }

            if (!acquired) {
                // 超时前，其他请求可能已经重建完成，最后检查一次。
                CacheResult last = readCache(id);
                if (last.hit()) {
                    return last.item();
                }

                // 不能直接查数据库，否则仍可能造成集中回源。
                throw cacheBusy();
            }

            if (cacheUnavailable()) {
                throw cacheBusy();
            }

            // 第二次检查：等待锁期间，缓存可能已经被别人填好。
            CacheResult second = readCache(id);
            if (second.hit()) {
                return second.item();
            }

            // 只有获锁且二次检查仍未命中的请求，才能查询数据库。
            // 数据库异常直接向外传播，不能缓存为“不存在”。
            ItemView item = loader.get();

            if (!cacheUnavailable()) {
                try {
                    String payload = item == null
                            ? NULL_MARKER
                            : json.writeValueAsString(item);

                    Duration cacheTtl = item == null ? NULL_TTL : ttl;

                    redis.execute(
                            FILL,
                            keys(id),
                            second.generation(),
                            payload,
                            String.valueOf(cacheTtl.toMillis())
                    );

                } catch (DataAccessException | JsonProcessingException exception) {
                    // 当前请求已有数据库结果，可以返回；
                    // 后续请求通过 cacheUnavailable() 暂时拒绝，保护数据库。
                    failed(exception);
                }
            }

            return item;

        } finally {
            if (acquired) {
                try {
                    // 只释放当前线程仍然持有的锁。
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (RuntimeException exception) {
                    // 解锁异常不能覆盖原来的查询结果或数据库异常。
                    failed(exception);
                }
            }
        }
    }
    // hit 用于区分“缓存未命中”和“命中了空值缓存”。
    // 两种情况的 item 都可能是 null，不能只看 item。
    private record CacheResult(
            boolean hit,
            ItemView item,
            String generation) {
    }

    private CacheResult readCache(Long id){
            try {
                List<?> snapshot = redis.execute(
                        SNAPSHOT,
                        keys(id),
                        UUID.randomUUID().toString(),
                        String.valueOf(generationMillis)
                );

                if(snapshot == null || snapshot.size() != 2){
                    throw new IllegalStateException("Missing cache snapshot");
                }

                String generation = (String) snapshot.get(0);
                String payload = (String) snapshot.get(1);

                if(NULL_MARKER.equals(payload)){
                    return new CacheResult(true,null,generation);
                }

                if(!payload.isEmpty()){
                    ItemView item = json.readValue(payload, ItemView.class);
                    if (item == null || !id.equals(item.id())) {
                        throw new IllegalStateException("Invalid cached item");
                    }

                    return new CacheResult(true, item, generation);
                }
                return new CacheResult(false, null, generation);
            }catch (DataAccessException
                    | JsonProcessingException
                    | IllegalStateException exception) {

                if (!(exception instanceof DataAccessException)) {
                    invalidate(id);
                }

                failed(exception);
                throw cacheBusy();
            }


    }

    private BusinessException cacheBusy() {
        return new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_UNAVAILABLE",
                "物品详情暂时繁忙，请稍后重试"
        );
    }

    public void invalidateAfterCommit(Long id) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { invalidate(id); }
            });
        } else invalidate(id);
    }
    private void invalidate(Long id) {
        try { redis.execute(INVALIDATE,keys(id),UUID.randomUUID().toString(),String.valueOf(generationMillis)); }
        catch (DataAccessException exception) { failed(exception); }
    }
    private List<String> keys(Long id) { return List.of(prefix+"{"+id+"}:generation",prefix+"{"+id+"}:detail"); }
    private String rebuildLockKey(Long id) {
        return prefix + "{" + id + "}:rebuild-lock";
    }
    private boolean cacheUnavailable() { return System.nanoTime()<cacheUnavailableUntil.get(); }
    private void failed(Exception exception) {
        cacheUnavailableUntil.accumulateAndGet(System.nanoTime()+ttl.toNanos(),Math::max);
        log.warn(
                "Item detail cache unavailable; temporarily rejecting cached detail reads",
                exception
        );
    }
}
