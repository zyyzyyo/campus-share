package com.campus.share.service;

import com.campus.share.common.BusinessException;
import com.campus.share.mapper.ItemMapper;
import com.campus.share.vo.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ItemPopularityService {
    private static final Logger log=LoggerFactory.getLogger(ItemPopularityService.class);
    private static final DefaultRedisScript<Long> RECORD=new DefaultRedisScript<>("""
        if not redis.call('SET', KEYS[2], '1', 'NX', 'PX', ARGV[2]) then return 0 end
        redis.call('ZINCRBY', KEYS[1], 1, ARGV[1])
        redis.call('EXPIRE', KEYS[1], 172800)
        return 1
        """,Long.class);
    private final StringRedisTemplate redis;
    private final ItemMapper items;
    private final Clock clock;
    private final String prefix;
    private final Duration window;
    public ItemPopularityService(StringRedisTemplate redis, ItemMapper items, Clock clock,
            @Value("${app.redis.key-prefix}") String prefix,
            @Value("${app.items.popularity-window:10m}") Duration window) {
        if (window.toMillis()<1 || window.compareTo(Duration.ofDays(1))>0) throw new IllegalArgumentException("Invalid popularity deduplication window");
        this.redis=redis; this.items=items; this.clock=clock; this.prefix=prefix; this.window=window;
    }
    public void record(ItemView item, Long viewerId, String remoteAddress) {
        if (!item.status().equals("ON_SHELF") || item.ownerId().equals(viewerId)) return;
        String identity=viewerId==null ? "ip:"+remoteAddress : "user:"+viewerId;
        String base=base();
        try {
            redis.execute(RECORD,List.of(base+":ranking",base+":seen:"+item.id()+":"+hash(identity)),
                    item.id().toString(),String.valueOf(window.toMillis()));
        } catch (DataAccessException exception) { log.warn("Could not record item popularity",exception); }
    }
    public PopularItemsView popular(int limit) {
        if (limit<1 || limit>20) throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_ARGUMENT","热门物品数量需为1至20");
        List<PopularItemView> result=new ArrayList<>();
        String key=base()+":ranking";
        try {
            // Read current database metadata in batches; never publish a cached off-shelf item.
            for (long offset=0; result.size()<limit; offset+=100) {
                var scores=redis.opsForZSet().reverseRangeWithScores(key,offset,offset+99);
                if (scores==null || scores.isEmpty()) break;
                List<Long> ids=new ArrayList<>();
                for (var score:scores) {
                    try { ids.add(Long.valueOf(score.getValue())); }
                    catch (NumberFormatException ignored) { /* Skip a malformed member. */ }
                }
                Map<Long,ItemView> visible=new HashMap<>();
                if (!ids.isEmpty()) for (ItemView item:items.findPublishedByIds(ids)) visible.put(item.id(),item);
                for (var score:scores) {
                    Long id;
                    try { id=Long.valueOf(score.getValue()); } catch (NumberFormatException ignored) { continue; }
                    ItemView item=visible.get(id);
                    if (item!=null && score.getScore()!=null && score.getScore()>0) {
                        result.add(new PopularItemView(item,score.getScore().longValue()));
                        if (result.size()==limit) break;
                    }
                }
                if (scores.size()<100) break;
            }
        } catch (DataAccessException exception) {
            log.warn("Item popularity unavailable",exception);
            return new PopularItemsView(false,List.of());
        }
        return new PopularItemsView(true,List.copyOf(result));
    }
    private String base() { return prefix+":popular:{"+clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()+"}"; }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
