package com.campus.share.service;

import com.campus.share.vo.ItemView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ItemDetailCache {
    private static final Logger log=LoggerFactory.getLogger(ItemDetailCache.class);
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
    private final ObjectMapper json;
    private final String prefix;
    private final Duration ttl;
    private final long generationMillis;
    // If invalidation fails, bypass this instance's cache until old entries expire.
    private final AtomicLong bypassUntil=new AtomicLong(System.nanoTime());

    public ItemDetailCache(StringRedisTemplate redis, ObjectMapper json,
            @Value("${app.redis.key-prefix}") String prefix,
            @Value("${app.items.cache-ttl:2m}") Duration ttl) {
        if (ttl.toMillis()<1 || ttl.compareTo(Duration.ofHours(1))>0) throw new IllegalArgumentException("Item cache TTL must be between 1 ms and 1 hour");
        this.redis=redis; this.json=json; this.prefix=prefix+":item:"; this.ttl=ttl;
        this.generationMillis=Math.max(600000,ttl.toMillis()*2);
    }
    public ItemView get(Long id, Supplier<ItemView> loader) {
        // Never expose an uncommitted write through the shared cache.
        if (TransactionSynchronizationManager.isActualTransactionActive() || bypassing()) return loader.get();
        String generation=null;
        try {
            List<?> snapshot=redis.execute(SNAPSHOT,keys(id),UUID.randomUUID().toString(),String.valueOf(generationMillis));
            if (snapshot==null || snapshot.size()!=2) throw new IllegalStateException("Missing cache snapshot");
            generation=(String)snapshot.get(0);
            String payload=(String)snapshot.get(1);
            if (!payload.isEmpty()) {
                ItemView cached=json.readValue(payload,ItemView.class);
                if (cached==null || !id.equals(cached.id())) throw new IllegalStateException("Invalid cached item");
                return cached;
            }
        } catch (DataAccessException | JsonProcessingException | IllegalStateException exception) {
            if (!(exception instanceof DataAccessException)) invalidate(id);
            failed(exception); generation=null;
        }
        ItemView item=loader.get();
        // Nulls and failed database reads are never cached.
        if (item!=null && generation!=null && !bypassing()) {
            try {
                redis.execute(FILL,keys(id),generation,json.writeValueAsString(item),String.valueOf(ttl.toMillis()));
            } catch (DataAccessException | JsonProcessingException exception) { failed(exception); }
        }
        return item;
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
    private boolean bypassing() { return System.nanoTime()<bypassUntil.get(); }
    private void failed(Exception exception) {
        bypassUntil.accumulateAndGet(System.nanoTime()+ttl.toNanos(),Math::max);
        log.warn("Item detail cache unavailable; using database temporarily",exception);
    }
}
