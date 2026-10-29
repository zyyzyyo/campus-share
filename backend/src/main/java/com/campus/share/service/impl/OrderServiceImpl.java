package com.campus.share.service.impl;

import com.campus.share.common.BusinessException;
import com.campus.share.dto.OrderRequest;
import com.campus.share.entity.*;
import com.campus.share.mapper.*;
import com.campus.share.service.OrderService;
import com.campus.share.vo.*;
import java.time.*;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/**
 * 借用订单的业务入口：Controller 接收请求后，交给这里检查权限、校验规则并操作数据库。
 *
 * 建议阅读顺序：create（提交申请）→ act（审核、领取、归还）→ 续期 → 定时处理。
 * 正常状态链：PENDING_REVIEW 待审核 → AWAITING_PICKUP 待领取 → BORROWING 借用中
 *           → RETURN_REQUESTED 待确认归还 → COMPLETED 已完成。
 * REJECTED / CANCELLED / EXPIRED 分别表示拒绝、取消、审核或领取超时。
 *
 * 并发控制：同一件物品的订单变更，先锁同一条物品记录，再重新检查订单和预约冲突。
 * 两个申请是两条不同订单，只锁各自订单不能避免同一物品被重复预约。
 * 注意：事务保证一组数据库操作一起提交或回滚，并不自动让两个请求排队；排队依靠行锁。
 */
@Service
public class OrderServiceImpl implements OrderService {
    // Mapper 执行 SQL：items 访问物品表，orders 访问订单及操作日志等数据。
    private final ItemMapper items;
    private final OrderMapper orders;
    // 统一从 Clock 取时间，测试中可以替换它来模拟到期，无需真的等待数小时。
    private final Clock clock;
    // 站内消息写数据库，随订单事务一起提交或回滚；不是发送短信或向 Redis 推消息。
    private final com.campus.share.service.NotificationService notifications;
    public OrderServiceImpl(ItemMapper items, OrderMapper orders, Clock clock, com.campus.share.service.NotificationService notifications) {
        this.items=items; this.orders=orders; this.clock=clock; this.notifications=notifications;
    }
    /** 查询一张订单；userId 来自后端认证身份，只有发布者和借用人能查看。 */
    public OrderView detail(Long id, Long userId) {
        OrderRow order=requireOrder(id);
        if (!order.ownerId().equals(userId) && !order.borrowerId().equals(userId))
            throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","无权查看该订单");
        return view(order);
    }
    /** 物品详情页公开展示的已预约时段，只返回时间和状态，不暴露借用人的身份。 */
    public java.util.List<ReservationView> reservations(Long itemId) {
        if (items.findById(itemId) == null)
            throw new BusinessException(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "物品不存在");
        // Mapper 筛出结束时间尚未过去的有效预约；待审核申请不占时段，不在结果中。
        // 数据库存 UTC 的 LocalDateTime，返回时转成带明确时间含义的 Instant。
        return orders.reservations(itemId, now()).stream()
                .map(slot -> new ReservationView(
                        slot.startTime().toInstant(ZoneOffset.UTC),
                        slot.endTime().toInstant(ZoneOffset.UTC),
                        slot.status())).toList();
    }
    /** 我的借用 borrowed / 借出管理 lent；Mapper 根据当前用户 ID 限定数据范围。 */
    public PageResult<OrderView> list(Long userId, String role, int page, int size) {
        if (!Set.of("borrowed", "lent").contains(role) || page < 1 || page > 100000 || size < 1 || size > 50)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "订单查询参数不合法");
        // 页码从 1 开始：第 1 页跳过 0 条，第 2 页跳过 size 条；同时返回总条数供前端分页。
        return new PageResult<>(orders.findPage(userId, role, (page-1)*size, size).stream().map(this::view).toList(),
                orders.count(userId, role), page, size);
    }
    /**
     * 提交借用申请。这里只生成“待审核”订单，发布者同意后才真正占用预约时段。
     * READ_COMMITTED：每次普通查询读取执行时已提交的数据，避免等到锁后仍用旧快照查冲突。
     * 事务中任一数据库写入抛出运行时异常，订单、操作日志、通知一起回滚。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderView create(OrderRequest request, Long userId) {
        // 1. 前端传 Instant，统一转换为 UTC 后落库；时间必须为未来且先开始、后结束。
        LocalDateTime start=LocalDateTime.ofInstant(request.startTime(), ZoneOffset.UTC);
        LocalDateTime end=LocalDateTime.ofInstant(request.endTime(), ZoneOffset.UTC);
        LocalDateTime now=now();
        if (!start.isAfter(now) || !end.isAfter(start))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "开始时间必须在未来，结束时间必须晚于开始时间");
        // 2. SELECT ... FOR UPDATE 锁住物品记录，锁一直持有到本事务提交或回滚。
        // 锁住后再检查物品状态，避免与发布者下架、其他审核或交付并发时绕过规则。
        ItemLock item=lockItem(request.itemId());
        if (item.ownerId().equals(userId))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SELF_BOOKING", "不能借用自己发布的物品");
        requireOnShelf(item);
        // 3. 不能借自己的物品、不能申请下架物品，有逾期未归还记录也不能再申请。
        if (orders.countOverdue(userId,now())>0)
            throw conflict("你有逾期未归还的物品，请完成归还后再预约");
        // 4. 检查已通过/已领取且未归还完成的预约是否冲突，0L 表示无需排除任何已有订单。
        // 时段按 [start, end) 判断：已有开始 < 本次结束，且已有结束 > 本次开始，才算重叠。
        // 因此 10:00–11:00 和 11:00–12:00 可以衔接。
        if (orders.countConflicts(item.id(), 0L, start, end)>0) throw conflict("该时段已有有效预约，请选择其他时间");
        // 别人的待审核申请不占用时段，但同一人不能反复提交重叠的待审核申请。
        if (orders.countOwnPending(item.id(), userId, start, end)>0) throw conflict("你已提交过重叠时段的申请");
        // 5. 创建订单实体；审核最晚截止于“提交后24小时”和“预约开始”中较早的时间。
        BorrowOrderEntity order=new BorrowOrderEntity();
        order.setItemId(item.id()); order.setBorrowerId(userId); order.setStartTime(start); order.setEndTime(end);
        order.setReviewExpiresAt(earlier(start, now.plusHours(24))); order.setStatus("PENDING_REVIEW");
        order.setCreatedAt(now); order.setUpdatedAt(now);
        // insert 后会回填自增订单 ID，再用这个 ID 写日志、通知，最后查询完整展示信息。
        orders.insert(order);
        orders.log(order.getId(), userId, "USER", null, "PENDING_REVIEW", "提交预约", now);
        notifications.send(item.ownerId(),order.getId(),"created","收到新的借用申请，请前往借出管理审核");
        return view(orders.findById(order.getId()));
    }
    /** 无取消原因时的调用入口；仍需标注事务，因为同类内部调用不会再次经过 Spring 代理。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderView act(Long id, String action, Long userId) {
        return act(id,action,userId,null);
    }
    /**
     * 处理订单动作。借用人可 cancel / request-return；其余动作由发布者操作。
     * reason 只在发布者 owner-cancel 时必填，其他动作通常传 null。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderView act(Long id, String action, Long userId, String reason) {
        // 1. 第一次查询只为找到物品 ID；等待物品锁期间，订单可能已经被别人修改。
        OrderRow snapshot=requireOrder(id);
        ItemLock item=lockItem(snapshot.itemId());
        // 拿到物品锁后必须重查订单，后续不能拿加锁前的 snapshot 做状态判断。
        OrderRow order=requireOrder(id);
        // 2. 先验证动作是否合法，再验证操作者身份；不能靠前端隐藏按钮来保证权限。
        boolean borrowerAction=Set.of("cancel", "request-return").contains(action);
        if (!Set.of("cancel", "owner-cancel", "remind", "request-return", "approve", "reject", "pickup", "complete").contains(action))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "不支持的订单操作");
        if (!(borrowerAction ? order.borrowerId() : order.ownerId()).equals(userId))
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权操作该订单");
        // 催还不改变订单状态：只给逾期且尚未完成归还的订单发消息。
        // 事件键含北京时间日期，通知表按接收人和事件键去重，实现同一订单每天最多一次。
        if (action.equals("remind")) {
            if (!held(order) || order.endTime().isAfter(now())) throw conflict("仅逾期未归还的订单可以催还");
            notifications.send(order.borrowerId(),id,"urge:"+clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(),"发布者提醒你尽快归还："+order.title());
            return view(order);
        }
        if (action.equals("owner-cancel") && (reason==null || reason.isBlank() || reason.strip().length()>500))
            throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_ARGUMENT","发布者取消订单必须填写500字以内的原因");
        // 3. next 是动作成功后的目标状态；到达同一目标状态的重复请求直接返回，避免重复日志/通知。
        String next=switch(action) {
            case "approve" -> "AWAITING_PICKUP"; case "reject" -> "REJECTED"; case "cancel", "owner-cancel" -> "CANCELLED";
            case "pickup" -> "BORROWING"; case "request-return" -> "RETURN_REQUESTED"; default -> "COMPLETED";
        };
        if (order.status().equals(next)) return view(order);
        // expected 是动作允许的起始状态，例如只有“待领取”才能变成“借用中”。
        // 取消可从两种状态发起，因此随后用集合单独检查。
        String expected=switch(action) {
            case "approve", "reject" -> "PENDING_REVIEW"; case "pickup" -> "AWAITING_PICKUP";
            case "request-return" -> "BORROWING"; case "complete" -> "RETURN_REQUESTED"; default -> order.status();
        };
        if (!order.status().equals(expected) || (action.equals("cancel") && !Set.of("PENDING_REVIEW", "AWAITING_PICKUP").contains(order.status())))
            throw conflict("订单状态已变化，请刷新后重试");
        if (action.equals("owner-cancel") && !Set.of("PENDING_REVIEW","AWAITING_PICKUP").contains(order.status()))
            throw conflict("只能取消尚未领取的订单，已领取请走归还流程");
        // 4. 即使每分钟执行的定时任务还没处理，已到截止时间的申请也不能继续审核或领取。
        LocalDateTime now=now(), pickupExpiry=order.pickupExpiresAt();
        if ((order.status().equals("PENDING_REVIEW") && !order.reviewExpiresAt().isAfter(now))
                || (order.status().equals("AWAITING_PICKUP") && !order.pickupExpiresAt().isAfter(now)))
            throw conflict("订单已超过处理时间，请刷新后重试");
        // 5. 审核通过时再次检查：提交申请后，物品可能被下架、借用人可能逾期、时段可能被占用。
        if (action.equals("approve")) {
            requireOnShelf(item);
            if (orders.countOverdue(order.borrowerId(),now)>0) throw conflict("借用人有逾期未归还物品，暂不能通过新预约");
            if (orders.countConflicts(item.id(), id, order.startTime(), order.endTime())>0) throw conflict("该时段与已通过的预约冲突");
            // 最晚领取时间是“预约开始后2小时”和“预约结束”中较早的一个。
            pickupExpiry=earlier(order.startTime().plusHours(2), order.endTime());
        }
        // 6. 领取由发布者当面交付后确认，不是借用人自己确认。
        if (action.equals("pickup")) {
            if (orders.countOverdue(order.borrowerId(),now)>0) throw conflict("借用人有逾期未归还物品，暂不能领取");
            // 预约结束不代表实物已归还：上一单仍“借用中/待确认归还”就不能再次交付。
            if (orders.countHolding(item.id(), id)>0) throw conflict("上一位同学尚未归还物品，暂时无法领取");
            // 支持提前领取，但实际占用将从现在开始，必须检查 [现在, 预约结束) 是否影响其他预约。
            if (orders.countConflicts(item.id(),id,now,order.endTime())>0)
                throw conflict("提前领取会占用其他已通过的预约时段，请按约定时间领取");
        }
        // 7. 条件更新：SQL 的 WHERE 同时限定 ID 和旧状态，返回1才说明确实更新了一条订单。
        // 行锁负责同物品操作的串行检查，这个旧状态条件再防止不符合预期的更新。
        if (orders.changeStatus(id, order.status(), next, pickupExpiry, now)!=1) throw conflict("订单状态已变化，请刷新后重试");
        // 实际领取时间另存，不覆盖原预约开始时间；取消原因也保留在订单中供双方查看。
        if (action.equals("pickup")) orders.recordPickup(id,now);
        if (action.equals("owner-cancel")) orders.cancellation(id,reason.strip());
        // 离开原借用流程时，不再让未审核的续期申请继续生效（例如借用人已申请归还）。
        if (order.renewalStatus().equals("PENDING")) orders.renewal(id,"CANCELLED",order.renewalEndTime(),now);
        orders.log(id, userId, "USER", order.status(), next, action.equals("owner-cancel") ? "发布者取消，原因见订单取消记录" : action, now);
        // 8. 日志和发给对方的站内通知都在当前事务中；写入失败不会只留下“改了一半”的订单。
        String text=switch(action) {
            case "approve" -> "借用申请已通过，请按时领取";
            case "reject" -> "借用申请未通过";
            case "cancel" -> "借用人已取消申请";
            case "owner-cancel" -> "发布者已取消订单，原因："+reason.strip();
            case "pickup" -> "物品已交付，请按时归还";
            case "request-return" -> "借用人申请归还，请检查物品并确认";
            default -> "归还已确认，订单已完成";
        };
        notifications.send(borrowerAction ? order.ownerId() : order.borrowerId(),id,action,text);
        return view(requireOrder(id));
    }
    /** 申请续期：只登记希望延长到的时间，审核通过前不改变原归还时间。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderView requestRenewal(Long id, Instant endTime, Long userId) {
        // 与普通订单动作相同，先定位物品、锁物品，再获取最新订单。
        OrderRow snapshot=requireOrder(id); lockItem(snapshot.itemId()); OrderRow order=requireOrder(id);
        if (!order.borrowerId().equals(userId))
            throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","仅借用人可申请续期");
        LocalDateTime now=now(), end=LocalDateTime.ofInstant(endTime,ZoneOffset.UTC);
        if (!order.status().equals("BORROWING") || !order.endTime().isAfter(now))
            throw conflict("仅未到期的借用中订单可申请续期");
        if (!end.isAfter(order.endTime()))
            throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_ARGUMENT","续期时间必须晚于原归还时间");
        // 同一时间只允许一轮待审核申请；重复提交相同时间直接返回，提交不同时间则提示等待。
        if (order.renewalStatus().equals("PENDING")) {
            if (end.equals(order.renewalEndTime())) return view(order);
            throw conflict("已有待审核续期申请，请等待发布者处理");
        }
        // 原时段已属于这张订单，只需检查新增的 [原结束时间, 新结束时间) 是否与其他单冲突。
        if (orders.countConflicts(order.itemId(),id,order.endTime(),end)>0) throw conflict("续期时段与其他预约冲突");
        // 续期序号每轮递增，使“拒绝后再次申请”能产生新通知，而不是被上一轮事件键去重。
        orders.nextRenewal(id);
        orders.renewal(id,"PENDING",end,now);
        orders.log(id,userId,"USER",order.status(),order.status(),"申请续期至 "+end+" UTC",now);
        notifications.send(order.ownerId(),id,"renewal-request:"+(order.renewalSequence()+1),"收到续期申请，请前往借出管理审核");
        return view(requireOrder(id));
    }
    /** 发布者审核续期；与新预约审核共用物品锁，防止两者同时抢到同一个延长时段。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderView reviewRenewal(Long id, boolean approve, Long userId) {
        OrderRow snapshot=requireOrder(id); lockItem(snapshot.itemId()); OrderRow order=requireOrder(id);
        if (!order.ownerId().equals(userId)) throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","仅发布者可审核续期");
        String result=approve ? "APPROVED" : "REJECTED";
        // 当前结果已与本次操作相同，直接返回；只有待审核且仍在借用中的申请才可继续处理。
        if (order.renewalStatus().equals(result)) return view(order);
        if (!order.status().equals("BORROWING") || !order.renewalStatus().equals("PENDING")) throw conflict("没有可审核的续期申请");
        LocalDateTime now=now();
        // 申请时检查过不代表现在仍可续期：等待期间可能到期，或新的预约已经通过。
        if (approve) {
            if (!order.endTime().isAfter(now)) throw conflict("原归还时间已到，续期已失效，请先归还物品");
            if (orders.countConflicts(order.itemId(),id,order.endTime(),order.renewalEndTime())>0) throw conflict("续期时段已有其他预约，无法通过");
            // 只有同意才真正延长订单 end_time；拒绝不会改变原归还时间。
            orders.extend(id,order.renewalEndTime(),now);
        }
        orders.renewal(id,result,order.renewalEndTime(),now);
        orders.log(id,userId,"USER",order.status(),order.status(),approve ? "同意续期" : "拒绝续期",now);
        notifications.send(order.borrowerId(),id,"renewal-review:"+order.renewalSequence(),approve ? "续期申请已通过，归还时间已更新" : "续期申请被拒绝，请按原时间归还");
        return view(requireOrder(id));
    }
    /**
     * OrderExpiryTask 每分钟调用：关闭审核/领取超时订单，发送到期/逾期提醒。
     * “审核/领取超时”和“已借出但归还逾期”不同：后者绝不能自动完成或释放实物。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void expireOverdue() {
        LocalDateTime now=now();
        // 每类候选最多100条，合并去重，再统一按物品ID、订单ID排序。
        // 同一事务可能持有多把物品锁；顺序一致，避免不同批次反向拿锁导致死锁。
        var candidates=java.util.stream.Stream.concat(orders.overdueIds(now).stream(),orders.reminderIds(now,now.plusHours(24)).stream())
                .distinct().map(this::requireOrder)
                .sorted(java.util.Comparator.comparing(OrderRow::itemId).thenComparing(OrderRow::id)).toList();
        for (OrderRow candidate: candidates) {
            // 候选查询只用于筛选，加锁后还要复查，防止用户刚刚领取或归还后仍被按旧状态处理。
            Long id=candidate.id(); lockItem(candidate.itemId()); OrderRow order=requireOrder(id);
            boolean expired=(order.status().equals("PENDING_REVIEW") && !order.reviewExpiresAt().isAfter(now))
                    || (order.status().equals("AWAITING_PICKUP") && !order.pickupExpiresAt().isAfter(now));
            // 只有未审核/未领取的订单会变为 EXPIRED；写系统日志，operator_id 为空。
            if (expired && orders.changeStatus(id, order.status(), "EXPIRED", order.pickupExpiresAt(), now)==1) {
                orders.log(id, null, "SYSTEM", order.status(), "EXPIRED", "自动超时", now);
                notifications.send(order.borrowerId(),id,"expired","订单因审核或领取超时自动关闭");
                notifications.send(order.ownerId(),id,"expired","订单因审核或领取超时自动关闭");
            }
            // 已领取且未完成归还的订单，进入结束前24小时后提醒；超过结束时间则属于逾期。
            if (!held(order) || order.endTime().isAfter(now.plusHours(24))) continue;
            boolean overdue=!order.endTime().isAfter(now);
            // 事件键含对应归还时间，同一次截止时间的重复扫描不会反复发消息。
            // 续期通过后结束时间改变，可以为新截止时间再次提醒。
            String event=(overdue ? "overdue:" : "due:")+orders.endKey(id);
            notifications.send(order.borrowerId(),id,event,overdue ? "物品已逾期，请尽快归还："+order.title() : "物品将在24小时内到期，请按时归还："+order.title());
            if (overdue) {
                notifications.send(order.ownerId(),id,event,"借出的物品已逾期，可前往借出管理催还："+order.title());
                // 到期还没审核的续期不能继续批准，标记为失效；订单仍保留原借用/待归还状态。
                if (order.renewalStatus().equals("PENDING")) orders.renewal(id,"CANCELLED",order.renewalEndTime(),now);
            }
        }
    }
    /** 通过 Mapper 执行 FOR UPDATE；必须在事务中调用，锁才会保持到整段业务结束。 */
    private ItemLock lockItem(Long id) {
        ItemLock item=items.lockById(id);
        if (item==null) throw new BusinessException(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "物品不存在");
        return item;
    }
    /** 读取含物品和双方信息的订单查询结果；不存在时统一返回404业务异常。 */
    private OrderRow requireOrder(Long id) {
        OrderRow order=orders.findById(id);
        if (order==null) throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在");
        return order;
    }
    // 下架禁止新申请/审核通过，但已经借出的物品仍应允许正常归还。
    private void requireOnShelf(ItemLock item) { if (!item.status().equals("ON_SHELF")) throw conflict("物品已下架，暂不能接受新预约"); }
    // 项目注入的 Clock 使用 UTC，数据库 LocalDateTime 也按 UTC 解释。
    private LocalDateTime now() { return LocalDateTime.now(clock); }
    // 返回两个时间中较早的一个，避免审核或领取期限超过实际预约允许的边界。
    private LocalDateTime earlier(LocalDateTime a, LocalDateTime b) { return a.isBefore(b)?a:b; }
    // HTTP 409 表示当前业务状态/时段不允许执行，前端通常应刷新数据后重试。
    private BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT, "ORDER_CONFLICT", message); }
    /** 将数据库查询行转换为前端响应；逾期标识实时计算，不依赖定时任务是否已经运行。 */
    private OrderView view(OrderRow row) {
        boolean overdue=held(row) && !row.endTime().isAfter(now());
        return new OrderView(row.id(), row.itemId(), row.borrowerId(), row.ownerId(), row.status(),
                row.startTime().toInstant(ZoneOffset.UTC), row.endTime().toInstant(ZoneOffset.UTC), row.title(), row.owner(), row.borrower(), row.image(),
                row.pickedUpAt()==null ? null : row.pickedUpAt().toInstant(ZoneOffset.UTC),
                row.renewalEndTime()==null ? null : row.renewalEndTime().toInstant(ZoneOffset.UTC),row.renewalStatus(),row.cancelReason(),
                // 已到期但不足一分钟也显示1分钟，未逾期或已完成订单显示0。
                overdue,overdue ? Math.max(1,Duration.between(row.endTime(),now()).toMinutes()) : 0);
    }
    // “申请归还”还不等于“确认归还”：两种状态都视为尚未完成交接，不能再次交付实物。
    private boolean held(OrderRow order) { return Set.of("BORROWING","RETURN_REQUESTED").contains(order.status()); }
}
