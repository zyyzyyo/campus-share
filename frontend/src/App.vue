<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Bell, ArrowUpRight, ArrowRight, Search, Plus, Heart, Grid2X2, CalendarDays, Package, BookOpen, MapPin, ChevronDown, ChevronRight, X, Check, SlidersHorizontal, Camera, Tent, Dumbbell, Laptop, Coffee, Leaf, LogOut, Menu, CircleHelp } from 'lucide-vue-next'
import { api, hasToken, saveToken } from './api'

const items = ref([]), favorites = ref([]), orders = ref([]), user = ref(null), categoryOptions = ref([])
const popularItems = ref([]), popularLoading = ref(false), popularError = ref('')
let popularVersion = 0
async function loadPopular() {
  const version = ++popularVersion
  popularLoading.value = true; popularError.value = ''
  try {
    const result = await api('/items/popular?limit=6', { anonymous: true })
    if (version !== popularVersion) return
    popularItems.value = result.items.map(entry => ({ ...normalizeItem(entry.item), views: entry.views }))
    if (!result.available) popularError.value = '热榜暂时不可用，请稍后重试。'
  } catch (exception) {
    if (version === popularVersion) { popularItems.value = []; popularError.value = exception.message }
  } finally { if (version === popularVersion) popularLoading.value = false }
}
const notifications = ref([]), unreadCount = ref(0), renewalEnd = ref(''), cancelReason = ref('')
const renewalNames = { PENDING: '续期待审核', APPROVED: '续期已通过', REJECTED: '续期被拒绝', CANCELLED: '续期已失效' }
let notificationTimer
async function loadUnread() {
  const viewer = user.value?.id
  if (!viewer) return
  try { const count = await api('/notifications/unread-count'); if (viewer === user.value?.id) unreadCount.value = count } catch { /* The message center provides explicit retry. */ }
}
const categories = computed(() => ['全部物品', ...categoryOptions.value.map(item => item.name)])
const nav = ref('发现物品'), category = ref('全部物品'), keyword = ref(''), campus = ref('全部校区'), onlyAvailable = ref(false), sort = ref('推荐排序')
const modal = ref(null), selected = ref(null), message = ref(''), error = ref(''), mobileMenu = ref(false)
const loading = ref(true), busy = ref(false), pageError = ref(''), actionError = ref(''), page = ref(1), total = ref(0)
const pageSize = 12, totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
const authMode = ref('login'), authForm = ref({ username: '', password: '', nickname: '' })
const start = ref(''), end = ref(''), editId = ref(null)
const reservations = ref([]), reservationsLoading = ref(false), reservationsError = ref('')
let reservationsVersion = 0
const reservationConflict = computed(() => {
  const from = new Date(start.value), to = new Date(end.value)
  return from < to && reservations.value.some(slot => from < new Date(slot.endTime) && to > new Date(slot.startTime))
})
async function loadReservations() {
  const id = selected.value?.id, version = ++reservationsVersion
  if (!id) return
  reservationsLoading.value = true; reservationsError.value = ''
  try {
    const result = await api(`/items/${id}/reservations`, { anonymous: true })
    if (version === reservationsVersion && modal.value === 'detail' && selected.value?.id === id) reservations.value = result
  } catch (exception) {
    if (version === reservationsVersion) reservationsError.value = exception.message
  } finally { if (version === reservationsVersion) reservationsLoading.value = false }
}
const blankDraft = () => ({ title: '', description: '', categoryId: categoryOptions.value[0]?.id || '', campus: '东校区', pickupLocation: '', image: '', file: null })
const draft = ref(blankDraft())
let messageTimer, loadTimer, abortController, requestVersion = 0, ready = false, previewUrl = ''
const icons = [Grid2X2, Camera, Dumbbell, Tent, Laptop, Coffee]
const navigation = [{ label: '发现物品', icon: Grid2X2 }, { label: '我的借用', icon: CalendarDays }, { label: '借出管理', icon: BookOpen }, { label: '我的发布', icon: Package }, { label: '我的收藏', icon: Heart }, { label: '消息通知', icon: Bell }]
const isOrderPage = computed(() => ['我的借用', '借出管理'].includes(nav.value))
const visibleItems = computed(() => items.value)
const myOrders = computed(() => orders.value)
const pendingCount = computed(() => nav.value === '我的借用' ? orders.value.filter(order => order.status === 'PENDING_REVIEW').length : 0)
const pageTitle = computed(() => nav.value === '发现物品' ? '发现校园好物' : nav.value)
const statusNames = { PENDING_REVIEW: '待审核', AWAITING_PICKUP: '待领取', BORROWING: '借用中', RETURN_REQUESTED: '待确认归还', COMPLETED: '已完成', REJECTED: '已拒绝', CANCELLED: '已取消', EXPIRED: '已超时' }
function normalizeItem(item) { return { ...item, available: item.status === 'ON_SHELF', location: `${item.campus} · ${item.pickupLocation}`, avatar: item.owner.slice(0, 1), color: '#e1ede5' } }
function notify(text) { message.value = text; clearTimeout(messageTimer); messageTimer = setTimeout(() => message.value = '', 3500) }
function navigate(label) { nav.value = label; category.value = '全部物品'; keyword.value = ''; campus.value = '全部校区'; onlyAvailable.value = false; page.value = 1; mobileMenu.value = false }
function invalidate() { ++requestVersion; abortController?.abort(); clearTimeout(loadTimer) }
async function loadPage() {
  clearTimeout(loadTimer)
  abortController?.abort()
  const version = ++requestVersion, viewer = user.value?.id
  abortController = new AbortController()
  loading.value = true; pageError.value = ''
  try {
    if (nav.value !== '发现物品' && !user.value) { items.value = []; orders.value = []; total.value = 0; return }
    const params = new URLSearchParams({ page: String(page.value), size: String(pageSize) })
    let path
    if (nav.value === '消息通知') path = '/notifications'
    else if (isOrderPage.value) { params.set('role', nav.value === '借出管理' ? 'lent' : 'borrowed'); path = '/orders' }
    else {
      params.set('scope', nav.value === '我的发布' ? 'mine' : nav.value === '我的收藏' ? 'favorites' : 'all')
      params.set('q', keyword.value.trim()); params.set('availableOnly', String(onlyAvailable.value))
      params.set('sort', sort.value === '最新发布' ? 'newest' : 'recommended')
      if (campus.value !== '全部校区') params.set('campus', campus.value)
      const selectedCategory = categoryOptions.value.find(item => item.name === category.value)
      if (selectedCategory) params.set('categoryId', String(selectedCategory.id))
      path = '/items'
    }
    const result = await api(`${path}?${params}`, { signal: abortController.signal })
    if (version !== requestVersion || viewer !== user.value?.id) return
    total.value = result.total
    const lastPage = Math.max(1, Math.ceil(result.total / pageSize))
    if (page.value > lastPage) { page.value = lastPage; return }
    if (nav.value === '消息通知') notifications.value = result.items
    else if (isOrderPage.value) orders.value = result.items
    else items.value = result.items.map(normalizeItem)
  } catch (exception) {
    if (exception.name !== 'AbortError' && version === requestVersion) { pageError.value = exception.message; items.value = []; orders.value = []; total.value = 0 }
  } finally { if (version === requestVersion) loading.value = false }
}
function scheduleLoad() { if (!ready) return; invalidate(); loading.value = true; loadTimer = setTimeout(loadPage, 180) }
watch([nav, category, keyword, campus, onlyAvailable, sort], () => { page.value = 1; scheduleLoad() })
watch(page, scheduleLoad)
watch(nav, value => { if (ready && value === '发现物品') loadPopular() })
async function refresh() { await nextTick(); await Promise.all([loadPage(), loadUnread()]) }
async function initialize() {
  loading.value = true; pageError.value = ''
  try {
    categoryOptions.value = await api('/categories', { anonymous: true })
    if (hasToken()) {
      try { user.value = await api('/auth/me'); favorites.value = await api('/favorites') }
      catch (exception) { if (exception.status !== 401) throw exception }
    }
    ready = true
    await loadPage()
    await loadUnread()
    await loadPopular()
  } catch (exception) { pageError.value = exception.message; loading.value = false }
}
async function open(type, item = null) {
  error.value = ''; selected.value = item; modal.value = type; start.value = ''; end.value = ''
  ++reservationsVersion; reservations.value = []; reservationsError.value = ''; reservationsLoading.value = false
  if (type === 'login') { authMode.value = 'login'; authForm.value.password = '' }
  if (type === 'detail' && item) {
    loadReservations()
    try {
      const detail = await api(`/items/${item.id}`)
      if (modal.value === 'detail' && selected.value?.id === item.id) selected.value = normalizeItem(detail)
      if (nav.value === '发现物品') loadPopular()
    }
    catch (exception) { error.value = exception.message }
  }
}
async function perform(action) {
  if (busy.value) return
  busy.value = true; error.value = ''; actionError.value = ''
  try { await action() }
  catch (exception) { error.value = exception.message; actionError.value = exception.message }
  finally { busy.value = false }
}
async function login() {
  await perform(async () => {
    if (authMode.value === 'register') await api('/auth/register', { method: 'POST', body: { ...authForm.value }, anonymous: true })
    const result = await api('/auth/login', { method: 'POST', body: { username: authForm.value.username, password: authForm.value.password }, anonymous: true })
    invalidate(); saveToken(result.accessToken); user.value = result.user; authForm.value.password = ''
    modal.value = null; items.value = []; orders.value = []
    favorites.value = await api('/favorites')
    notify(`欢迎你，${user.value.nickname}`); await refresh()
  })
}
function clearIdentity() { invalidate(); saveToken(''); user.value = null; favorites.value = []; items.value = []; orders.value = []; notifications.value = []; unreadCount.value = 0; total.value = 0; navigate('发现物品') }
async function logout() {
  await perform(async () => { await api('/auth/logout', { method: 'POST' }); clearIdentity(); modal.value = null; notify('已退出登录'); await refresh() })
}
function authExpired() { clearIdentity(); modal.value = null; notify('登录已过期，请重新登录'); if (ready) scheduleLoad() }
async function toggleFavorite(id) {
  if (!user.value) { open('login'); return }
  await perform(async () => {
    const saved = favorites.value.includes(id)
    await api(`/favorites/${id}`, { method: saved ? 'DELETE' : 'PUT' })
    favorites.value = saved ? favorites.value.filter(value => value !== id) : [...favorites.value, id]
    if (nav.value === '我的收藏') await refresh()
  })
}
function clearPreview() { if (previewUrl) URL.revokeObjectURL(previewUrl); previewUrl = '' }
function publish() {
  if (!user.value) { open('login'); return }
  clearPreview(); editId.value = null; draft.value = blankDraft(); open('publish')
}
function editItem(item) {
  clearPreview(); editId.value = item.id
  draft.value = { title: item.title, description: item.description, categoryId: item.categoryId, campus: item.campus, pickupLocation: item.pickupLocation, image: item.image || '', file: null }
  open('publish')
}
function fileSelected(event) {
  const file = event.target.files?.[0]; if (!file) return
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || file.size > 1024 * 1024) { error.value = '请选择 1MB 以内的 JPG、PNG 或 WebP 图片'; event.target.value = ''; return }
  clearPreview(); previewUrl = URL.createObjectURL(file); draft.value.image = previewUrl; draft.value.file = file; error.value = ''
}
async function submitItem() {
  await perform(async () => {
    if (draft.value.file) {
      const form = new FormData(); form.append('file', draft.value.file)
      const uploaded = await api('/media', { method: 'POST', body: form })
      draft.value.image = uploaded.url; draft.value.file = null; clearPreview()
    }
    const { title, description, categoryId, campus, pickupLocation, image } = draft.value
    const updating = Boolean(editId.value)
    await api(updating ? `/items/${editId.value}` : '/items', { method: updating ? 'PUT' : 'POST', body: { title, description, categoryId, campus, pickupLocation, imageUrl: image || null } })
    modal.value = null; draft.value = blankDraft(); navigate('我的发布'); notify(updating ? '物品已修改' : '物品已发布'); await refresh()
  })
}
async function toggleStatus(item) {
  await perform(async () => {
    await api(`/items/${item.id}/status`, { method: 'PATCH', body: { status: item.available ? 'OFF_SHELF' : 'ON_SHELF' } })
    notify(item.available ? '物品已下架' : '物品已上架'); await refresh()
  })
}
async function book() {
  if (!user.value) { open('login'); return }
  await perform(async () => {
    const from = new Date(start.value), to = new Date(end.value)
    if (!start.value || !end.value || from <= new Date() || to <= from) throw new Error('请选择未来的开始时间，结束时间需晚于开始时间')
    if (reservationConflict.value) throw new Error('所选时间与已预约时段重叠，请调整借用时间')
    await api('/orders', { method: 'POST', body: { itemId: selected.value.id, startTime: from.toISOString(), endTime: to.toISOString() } })
    modal.value = null; navigate('我的借用'); notify('预约申请已提交，等待发布者审核'); await refresh()
  })
}
function orderActions(order) {
  if (nav.value === '我的借用') {
    if (['PENDING_REVIEW', 'AWAITING_PICKUP'].includes(order.status)) return [{ key: 'cancel', label: '取消申请' }]
    if (order.status === 'BORROWING') return [{ key: 'request-return', label: '申请归还' }, ...(!order.overdue && order.renewalStatus !== 'PENDING' ? [{ key: 'renew', label: '申请续期' }] : [])]
  } else {
    if (order.status === 'PENDING_REVIEW') return [{ key: 'approve', label: '同意申请' }, { key: 'reject', label: '拒绝申请' }, { key: 'owner-cancel', label: '取消并说明原因' }]
    if (order.status === 'AWAITING_PICKUP') return [{ key: 'pickup', label: '确认领取' }, { key: 'owner-cancel', label: '取消并说明原因' }]
    const actions = []
    if (order.status === 'RETURN_REQUESTED') actions.push({ key: 'complete', label: '确认归还' })
    if (order.status === 'BORROWING' && order.renewalStatus === 'PENDING') {
      if (!order.overdue) actions.push({ key: 'renewal-approve', label: '同意续期' })
      actions.push({ key: 'renewal-reject', label: '拒绝续期' })
    }
    if (order.overdue) actions.push({ key: 'remind', label: '提醒归还' })
    return actions
  }
  return []
}
async function orderAction(order, action) {
  if (action === 'renew') { renewalEnd.value = ''; await open('renew', order); return }
  if (action === 'owner-cancel') { cancelReason.value = ''; await open('owner-cancel', order); return }
  const confirmation = action === 'pickup' ? '确认已经当面检查物品并交付给借用人？' : action === 'complete' ? '确认物品及配件已归还并检查无误？' : ''
  if (confirmation && !window.confirm(confirmation)) return
  await perform(async () => {
    const path = action.startsWith('renewal-') ? `/orders/${order.id}/renewal/${action.slice(8)}` : `/orders/${order.id}/actions/${action}`
    await api(path, { method: 'PATCH' }); notify(action === 'remind' ? '已提醒归还，每天最多发送一次' : '订单已更新'); await refresh()
  })
}
async function submitOrderChange() {
  await perform(async () => {
    if (modal.value === 'renew') {
      const date = new Date(renewalEnd.value)
      if (!renewalEnd.value || !Number.isFinite(date.getTime()) || date <= new Date(selected.value.endTime)) throw new Error('请选择晚于原归还时间的续期时间')
      await api(`/orders/${selected.value.id}/renewal`, { method: 'POST', body: { endTime: date.toISOString() } })
    } else {
      if (!cancelReason.value.trim()) throw new Error('请填写取消原因')
      await api(`/orders/${selected.value.id}/actions/owner-cancel`, { method: 'PATCH', body: { reason: cancelReason.value.trim() } })
    }
    modal.value = null; notify('操作成功，已通知对方'); await refresh()
  })
}
async function readNotification(item) {
  await perform(async () => {
    const viewer = user.value?.id
    await api(`/notifications/${item.id}/read`, { method: 'PATCH' })
    if (viewer !== user.value?.id) return
    item.readAt = new Date().toISOString(); await loadUnread()
    const detail = await api(`/orders/${item.orderId}`)
    if (viewer === user.value?.id) await open('order-detail', detail)
  })
}
async function readAllNotifications() {
  await perform(async () => { await api('/notifications/read-all', { method: 'PATCH' }); await refresh() })
}
function overdueLabel(order) {
  const minutes = order.overdueMinutes
  return minutes >= 1440 ? `已逾期 ${Math.floor(minutes / 1440)} 天 ${Math.floor(minutes % 1440 / 60)} 小时` : minutes >= 60 ? `已逾期 ${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分钟` : `已逾期 ${minutes} 分钟`
}
function resetFilters() { category.value = '全部物品'; keyword.value = ''; campus.value = '全部校区'; onlyAvailable.value = false }
function formatReservationDate(value) { return new Date(value).toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }) }
function formatDate(value) { return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }) }
function fallbackImage(event) { event.target.style.display = 'none'; (event.target.closest('.card-image') || event.target.parentElement).classList.add('image-failed') }
function handleKey(event) { if (event.key === 'Escape' && !busy.value) modal.value = null }
window.addEventListener('keydown', handleKey)
window.addEventListener('campus-auth-expired', authExpired)
watch(modal, value => { document.body.style.overflow = value ? 'hidden' : ''; if (value) setTimeout(() => document.querySelector('.dialog input, .dialog .close')?.focus(), 0) })
onMounted(initialize)
onMounted(() => { notificationTimer = setInterval(loadUnread, 30000) })
onBeforeUnmount(() => { invalidate(); ++popularVersion; clearInterval(notificationTimer); clearTimeout(messageTimer); clearPreview(); window.removeEventListener('keydown', handleKey); window.removeEventListener('campus-auth-expired', authExpired); document.body.style.overflow = '' })
function trapFocus(event) {
  if (event.key !== 'Tab') return
  const nodes = [...event.currentTarget.querySelectorAll('button, input, select, textarea, [tabindex="0"]')].filter(node => !node.disabled)
  const first = nodes[0], last = nodes.at(-1)
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar" :class="{ 'mobile-open': mobileMenu }">
      <a class="brand" href="#" @click.prevent="navigate('发现物品')"><span class="brand-symbol"><BookOpen :size="23" /></span><span>同学借一下<small>CAMPUS SHARE</small></span></a>
      <div class="campus-label"><span class="live-dot"></span> 让闲置，变成刚好需要</div>
      <div class="nav-caption">我的校园</div>
      <nav aria-label="主要导航"><button v-for="entry in navigation" :key="entry.label" :class="{ active: nav === entry.label }" @click="navigate(entry.label)"><component :is="entry.icon" :size="19" /><span>{{ entry.label }}</span><span v-if="entry.label === '消息通知' && unreadCount" class="nav-badge">{{ unreadCount }}</span><span v-else-if="entry.label === '我的借用' && pendingCount" class="nav-badge">{{ pendingCount }}</span><ChevronRight v-else-if="nav === entry.label" :size="15" /></button></nav>
      <div class="sidebar-note"><span class="note-icon"><Leaf :size="22" /></span><h3>少一份闲置<br>多一种可能。</h3><p>一本书、一台相机、一顶帐篷，<br>让好物陪更多人走一程。</p><button @click="open('guide')">了解借用规则 <ArrowUpRight :size="15" /></button></div>
      <div class="sidebar-bottom"><button @click="open('guide')"><CircleHelp :size="17" /> 借用指南</button><p>在校园，分享一点美好 <span>↗</span></p></div>
    </aside>

    <div class="main-shell">
      <header class="topbar">
        <button class="mobile-toggle icon-button" aria-label="展开导航" @click="mobileMenu = !mobileMenu"><Menu :size="22" /></button>
        <div class="breadcrumb">校园共享 <ChevronRight :size="14" /><strong>{{ nav }}</strong></div>
        <div class="top-actions"><span class="demo-badge">校园共享平台</span><button class="publish-button" @click="publish"><Plus :size="17" /> 发布闲置</button><button v-if="!user" class="user-button" aria-label="登录 / 注册" @click="open('login')"><span class="avatar">同</span><span>登录 / 注册</span></button><button v-else class="user-button" @click="open('account')"><span class="avatar">{{ user.nickname.slice(0, 1) }}</span><span>{{ user.nickname }}</span><ChevronDown :size="14" /></button></div>
      </header>

      <main>
        <section v-if="nav === '发现物品'" class="hero">
          <div class="hero-copy"><div class="eyebrow"><span></span> 好物共享 · 校园同行</div><h1>需要的，借一下。<br><span>闲置的，分享吧。</span></h1><p>不用每样都拥有，也能拥有更多可能。<br>发现身边同学的闲置好物，让校园生活轻一点。</p><button class="hero-button" @click="publish">分享我的第一件好物 <ArrowUpRight :size="17" /></button><div class="hero-foot"><span class="mini-avatars"><i>林</i><i>周</i><i>陈</i></span><span>从一次分享，认识一位同学</span></div></div>
          <img class="hero-art" src="/hero.svg" alt="可供同学分享借用的相机、书本、背包和篮球插画" />
          <div class="hero-sticker"><span>物尽其用</span><Leaf :size="14" /><small>GOOD THINGS, SHARED.</small></div>
        </section>

        <section v-if="nav === '发现物品'" class="popular-section" aria-labelledby="popular-heading">
          <div class="popular-heading"><div><h2 id="popular-heading">校园今日热门<span class="heading-dot">.</span></h2><p>看看同学们今天都在关注什么 · 北京时间每日更新</p></div><button class="secondary-button" :disabled="popularLoading" @click="loadPopular">{{ popularLoading ? '正在刷新…' : '刷新热榜' }}</button></div>
          <p v-if="popularError" class="popular-notice" role="status">{{ popularError }}<button @click="loadPopular">重试</button></p>
          <p v-else-if="popularLoading && !popularItems.length" class="popular-notice" role="status">正在加载热门物品…</p>
          <div v-else-if="popularItems.length" class="popular-grid"><button v-for="(item, index) in popularItems" :key="item.id" class="popular-card" @click="open('detail', item)"><span class="popular-rank">{{ String(index + 1).padStart(2, '0') }}</span><img v-if="item.image" :src="item.image" alt="" loading="lazy" /><Package v-else :size="27" /><span class="popular-info"><strong>{{ item.title }}</strong><small>{{ item.campus }} · 今日 {{ item.views }} 次浏览</small></span><ArrowUpRight :size="16" /></button></div>
          <p v-else class="popular-notice">今天还没有热门物品，浏览感兴趣的好物，让分享被更多同学看见。</p>
          <p class="popular-hint">同一访客10分钟内重复查看只计一次；预约以实际时段和发布者审核为准。</p>
        </section>

        <section class="discovery-section">
          <div class="section-heading"><div><h2>{{ pageTitle }}<span class="heading-dot">.</span></h2><p>{{ nav === '发现物品' ? '总有一件好物，刚好是你需要的。' : nav === '我的借用' ? '每一次借用，都从一份信任开始。' : nav === '借出管理' ? '看看谁需要你的好物，管理每一次交接。' : nav === '我的发布' ? '让你的闲置，成为同学的及时雨。' : nav === '消息通知' ? '预约、续期和归还的新进展，都在这里。' : '把喜欢的好物留在这里，下次更容易找到。' }}</p></div><span class="section-aside"><Leaf :size="14" /> 免费借用，友好共享</span></div>

          <div v-if="actionError" class="request-error" role="alert">{{ actionError }}<button aria-label="关闭错误提示" @click="actionError = ''"><X :size="16" /></button></div>
          <div v-if="pageError" class="empty-state" role="alert"><CircleHelp :size="36" /><h3>暂时无法加载数据</h3><p>{{ pageError }}</p><button class="primary-button" @click="initialize">重新加载</button></div>
          <div v-else-if="loading && (isOrderPage || nav === '消息通知' || !categoryOptions.length)" class="empty-state" role="status"><p>正在从平台加载数据…</p></div>
          <div v-else-if="nav !== '发现物品' && !user" class="empty-state"><BookOpen :size="36" /><h3>登录后查看{{ nav }}</h3><p>使用账号登录，物品、收藏和预约会保存在平台。</p><button class="primary-button" @click="open('login')">登录 / 注册</button></div>
          <template v-else-if="nav === '消息通知'">
            <div class="order-toolbar"><p>共 {{ unreadCount }} 条未读消息。点击消息查看关联订单；未读数量每30秒更新。</p><div class="order-actions"><button class="secondary-button" :disabled="busy" @click="refresh">刷新消息</button><button class="secondary-button" :disabled="busy || !unreadCount" @click="readAllNotifications">全部已读</button></div></div>
            <div v-if="!notifications.length" class="empty-state"><Bell :size="38" /><h3>暂时没有消息</h3><p>预约、续期和归还的新进展都会在这里通知你。</p></div>
            <article v-for="item in notifications" :key="item.id" class="notification-card" :class="{ unread: !item.readAt }"><Bell :size="22" /><div><strong>{{ item.readAt ? '订单通知' : '未读通知' }} · 订单 {{ item.orderId }}</strong><p>{{ item.content }}</p><small>{{ formatReservationDate(item.createdAt) }}</small></div><button class="secondary-button" :disabled="busy" @click="readNotification(item)">查看订单</button></article>
          </template>
          <template v-else-if="!isOrderPage">
            <div class="search-row"><label class="search-field"><Search :size="20" /><input v-model="keyword" aria-label="搜索物品" placeholder="搜索同学发布的物品名称或描述" /><button v-if="keyword" class="icon-button" aria-label="清空搜索" @click="keyword = ''"><X :size="16" /></button><kbd>/ 好物在这里</kbd></label><label class="campus-select"><MapPin :size="17" /><select v-model="campus" aria-label="选择校区"><option>全部校区</option><option>东校区</option><option>南校区</option><option>西校区</option></select></label></div>
            <div class="category-row"><button v-for="(item, index) in categories" :key="item" :class="{ selected: category === item }" @click="category = item"><component :is="icons[index]" :size="16" />{{ item }}</button></div>
            <div class="results-toolbar"><span>共 <strong>{{ total }}</strong> 件{{ nav === '我的收藏' ? '收藏好物' : '校园好物' }}<span class="toolbar-divider"></span><span class="soft-text">找到需要，也分享所爱</span></span><div><label class="availability"><input v-model="onlyAvailable" type="checkbox" />只看可借</label><span class="toolbar-divider"></span><SlidersHorizontal :size="14" /><select v-model="sort" aria-label="物品排序"><option>推荐排序</option><option>最新发布</option></select></div></div>
            <div v-if="loading" class="empty-state" role="status"><p>正在加载物品…</p></div><div v-else-if="visibleItems.length" class="item-grid">
              <article v-for="item in visibleItems" :key="item.id" class="item-card">
                <div class="card-image" :style="{ backgroundColor: item.color }"><button class="image-link" :aria-label="`查看${item.title}`" @click="open('detail', item)"><img v-if="item.image" :src="item.image" :alt="item.title" loading="lazy" @error="fallbackImage" /><Package v-else :size="55" class="placeholder-icon" /></button><span class="status-tag" :class="{ unavailable: !item.available }"><span></span>{{ item.available ? '可申请借用' : '暂不可借' }}</span><button :disabled="busy" class="favorite-button" :class="{ saved: favorites.includes(item.id) }" :aria-pressed="favorites.includes(item.id)" :aria-label="favorites.includes(item.id) ? '取消收藏' : '收藏物品'" @click="toggleFavorite(item.id)"><Heart :size="18" /></button><span class="image-fallback"><Package :size="44" />{{ item.category }}</span></div>
                <div class="card-body"><div class="card-category">{{ item.category }}<span>免费借用</span></div><button class="card-title" @click="open('detail', item)">{{ item.title }}</button><p class="item-location"><MapPin :size="13" />{{ item.location }}</p><div class="card-footer"><span class="owner"><span class="owner-avatar" :style="{ backgroundColor: item.color }">{{ item.avatar }}</span>{{ item.owner }}</span><button v-if="nav !== '我的发布'" class="card-action" @click="open('detail', item)">去看看 <ArrowUpRight :size="15" /></button><span v-else class="owner-actions"><button class="card-action" :disabled="busy" @click="editItem(item)">修改</button><button class="card-action" :disabled="busy" @click="toggleStatus(item)">{{ item.available ? '下架' : '上架' }}</button></span></div></div>
              </article>
            </div>
            <div v-else class="empty-state"><Package :size="38" /><h3>{{ nav === '我的发布' ? '分享从第一件好物开始' : '还没有找到合适的好物' }}</h3><p>{{ nav === '我的发布' ? '把闲置物品发布出来，让需要它的同学找到你。' : '换个筛选条件，或发布平台的第一件好物。' }}</p><button class="primary-button" @click="nav === '我的发布' ? publish() : resetFilters()">{{ nav === '我的发布' ? '发布物品' : '清除筛选' }}</button></div>
            <div v-if="visibleItems.length" class="list-end"><span></span> 好物不在多，在刚好需要 <Leaf :size="13" /><span></span></div>
          </template>

          <template v-else>
            <div class="order-toolbar"><p>{{ nav === '借出管理' ? '处理自己发布物品的借用申请，领取和归还请当面确认。' : '提交申请后等待审核，可在这里查看状态并申请归还。' }}</p><button class="secondary-button" @click="refresh">刷新订单</button></div>
            <div v-if="!myOrders.length" class="empty-state"><CalendarDays :size="40" /><h3>{{ nav === '借出管理' ? '暂时没有同学申请借用' : '你的下一段借用故事，从这里开始' }}</h3><p>{{ nav === '借出管理' ? '先发布一件闲置好物，等待需要它的同学。' : '浏览同学们分享的物品，选择时间并提交申请。' }}</p><button class="primary-button" @click="nav === '借出管理' ? publish() : navigate('发现物品')">{{ nav === '借出管理' ? '发布物品' : '去发现好物' }}<ArrowRight :size="16" /></button></div>
            <article v-for="order in myOrders" :key="order.id" class="order-card"><div class="order-icon"><Package :size="28" /></div><div class="order-info"><span class="order-number">申请编号 {{ order.id }}</span><h3>{{ order.title }}</h3><p>{{ formatDate(order.startTime) }} — {{ formatDate(order.endTime) }}</p><small>{{ nav === '借出管理' ? `借用人：${order.borrower}` : `发布者：${order.owner}` }}</small><p v-if="order.overdue" class="overdue-label">{{ overdueLabel(order) }}，请尽快完成归还。</p><p v-if="order.renewalEndTime" class="renewal-label">{{ renewalNames[order.renewalStatus] }}：{{ formatReservationDate(order.renewalEndTime) }}<small v-if="order.renewalStatus === 'PENDING'">审核通过前仍须按原时间归还。</small></p><p v-if="order.cancelReason">发布者取消原因：{{ order.cancelReason }}</p></div><span class="order-status" :class="{ cancelled: ['CANCELLED','REJECTED','EXPIRED'].includes(order.status) }">{{ statusNames[order.status] }}</span><div class="order-actions"><button v-for="action in orderActions(order)" :key="action.key" class="secondary-button" :disabled="busy" @click="orderAction(order, action.key)">{{ action.label }}</button></div></article>
          </template>
          <div v-if="!loading && !pageError && totalPages > 1" class="pagination"><button class="secondary-button" :disabled="page <= 1" @click="page--">上一页</button><span>第 {{ page }} / {{ totalPages }} 页 · 共 {{ total }} 条</span><button class="secondary-button" :disabled="page >= totalPages" @click="page++">下一页</button></div>
        </section>
        <section class="bottom-banner"><span class="bottom-icon"><BookOpen :size="25" /></span><div><h3>借的是物品，传递的是信任。</h3><p>按时领取、爱惜使用、如期归还，让每一次分享都有回响。</p></div><button @click="open('guide')">看看借用指南 <ArrowRight :size="17" /></button></section>
        <footer><span>© 2026 同学借一下 · 校园物品借用与预约平台</span><span>让每一件好物，都有新的故事。<Leaf :size="13" /></span></footer>
      </main>
    </div>

    <div v-if="modal" class="modal-backdrop" @click.self="!busy && (modal = null)"><section class="dialog" :class="{ 'detail-dialog': modal === 'detail' }" role="dialog" aria-modal="true" aria-labelledby="dialog-title" @keydown="trapFocus"><button class="close icon-button" :disabled="busy" aria-label="关闭弹窗" @click="modal = null"><X :size="22" /></button>
      <template v-if="modal === 'detail' && selected"><div class="detail-image"><img v-if="selected.image" :src="selected.image" :alt="selected.title" @error="fallbackImage" /><Package v-else :size="70" /></div><span class="form-eyebrow">{{ selected.category }} · 免费借用</span><h2 id="dialog-title">{{ selected.title }}</h2><p class="detail-meta"><MapPin :size="16" />{{ selected.location }} · {{ selected.owner }}</p><p class="detail-description">{{ selected.description }}</p><section class="reservation-panel" aria-label="已预约时段"><div class="reservation-heading"><h3><CalendarDays :size="17" /> 已预约时段</h3><button type="button" class="secondary-button" :disabled="reservationsLoading" @click="loadReservations">{{ reservationsLoading ? '正在刷新…' : '刷新时段' }}</button></div><p class="reservation-hint">以下时段已被占用，请避开。待审核申请不占用时段，时间按本地时间显示。</p><p v-if="reservationsLoading" role="status">正在加载预约时段…</p><p v-else-if="reservationsError" class="error" role="alert">{{ reservationsError }}，请点击“刷新时段”重试。</p><template v-else><ul v-if="reservations.length" class="reservation-list"><li v-for="slot in reservations" :key="slot.startTime + slot.endTime"><span>{{ formatReservationDate(slot.startTime) }} — {{ formatReservationDate(slot.endTime) }}</span><small>{{ statusNames[slot.status] }}</small></li></ul><p v-else>暂无已确认的预约时段。</p></template></section><template v-if="selected.available && selected.ownerId !== user?.id"><form @submit.prevent="book"><div class="date-fields"><label>开始时间<input v-model="start" required type="datetime-local" /></label><label>结束时间<input v-model="end" required type="datetime-local" /></label></div><p v-if="reservationConflict" class="error" role="alert">所选时间与已预约时段重叠，请调整借用时间。</p><p class="form-hint">提交后等待发布者审核，审核通过才占用预约时段。预约以提交及审核时的最新结果为准。</p><p v-if="error" class="error" role="alert">{{ error }}</p><button class="primary-button full-width" :disabled="busy" type="submit">{{ user ? '提交预约申请' : '登录后预约' }}<ArrowUpRight :size="17" /></button></form></template><p v-else class="notice">{{ selected.ownerId === user?.id ? '这是你发布的物品，可以在“我的发布”管理。' : '这件物品暂不可借，可以先收藏，之后再来看看。' }}</p></template>
      <template v-else-if="modal === 'login'"><span class="form-eyebrow">HELLO, CLASSMATE</span><h2 id="dialog-title">{{ authMode === 'register' ? '注册校园共享账号' : '欢迎来到同学借一下' }}</h2><div class="auth-switch"><button :class="{ selected: authMode === 'login' }" :disabled="busy" @click="authMode = 'login'; error = ''">账号登录</button><button :class="{ selected: authMode === 'register' }" :disabled="busy" @click="authMode = 'register'; error = ''">注册账号</button></div><form @submit.prevent="login"><label>账号<input v-model="authForm.username" required pattern="[A-Za-z0-9_]{4,32}" maxlength="32" placeholder="4–32 位字母、数字或下划线" autocomplete="username" /></label><label v-if="authMode === 'register'">昵称<input v-model="authForm.nickname" required maxlength="32" placeholder="例如：林同学" autocomplete="nickname" /></label><label>密码<input v-model="authForm.password" required type="password" minlength="8" maxlength="64" placeholder="8–64 个字符" :autocomplete="authMode === 'register' ? 'new-password' : 'current-password'" /></label><p v-if="authMode === 'register'" class="form-hint">昵称可以重复，账号不可重复。注册成功后自动登录。</p><p v-if="error" class="error" role="alert">{{ error }}</p><button class="primary-button full-width" :disabled="busy" type="submit">{{ busy ? '正在处理…' : authMode === 'register' ? '注册并登录' : '登录' }}<ArrowRight :size="16" /></button></form></template>
      <template v-else-if="modal === 'publish'"><span class="form-eyebrow">SHARE SOMETHING GOOD</span><h2 id="dialog-title">{{ editId ? '修改物品信息' : '分享一件闲置好物' }}</h2><p class="form-hint">每条发布对应一件实物；第一版免费借用。</p><form class="publish-form" @submit.prevent="submitItem"><label>物品名称<input v-model="draft.title" required maxlength="100" placeholder="例如：适合周末露营的双人帐篷" /></label><div class="date-fields"><label>分类<select v-model.number="draft.categoryId" required><option v-for="item in categoryOptions" :key="item.id" :value="item.id">{{ item.name }}</option></select></label><label>校区<select v-model="draft.campus"><option>东校区</option><option>南校区</option><option>西校区</option></select></label></div><label>领取位置<input v-model="draft.pickupLocation" required maxlength="150" placeholder="例如：图书馆一楼门口" /></label><label>物品描述<textarea v-model="draft.description" required maxlength="500" rows="4" placeholder="介绍物品状态、包含的配件和使用注意事项" /></label><label>物品图片（可选，最大 1MB）<input type="file" accept="image/jpeg,image/png,image/webp" @change="fileSelected" /></label><img v-if="draft.image" class="upload-preview" :src="draft.image" alt="待发布物品预览" /><button v-if="draft.image" type="button" class="secondary-button" :disabled="busy" @click="clearPreview(); draft.image = ''; draft.file = null">移除图片</button><p v-if="error" class="error" role="alert">{{ error }}</p><button class="primary-button full-width" :disabled="busy" type="submit"><Plus :size="17" />{{ busy ? '正在保存…' : editId ? '保存修改' : '发布物品' }}</button></form></template>
      <template v-else-if="modal === 'guide'"><span class="form-eyebrow">A LITTLE TRUST GOES A LONG WAY</span><h2 id="dialog-title">校园借用小指南</h2><ol class="guide-list"><li><strong>找到需要的好物</strong><p>看看描述和领取位置，选择开始与结束时间。</p></li><li><strong>等待发布者审核</strong><p>提交申请后不占用时段，审核通过才形成有效预约。</p></li><li><strong>当面检查，按时领取</strong><p>审核通过后支持提前领取。与发布者一起检查物品及配件，由发布者确认交付。</p></li><li><strong>爱惜使用，如期归还</strong><p>借用人申请归还，发布者确认后，借用才算完成。</p></li></ol><p class="notice">第一版不收取租金或押金。请勿通过平台外链接付款。</p><button class="primary-button full-width" @click="modal = null">知道了 <Check :size="16" /></button></template>
      <template v-else-if="modal === 'renew' || modal === 'owner-cancel'"><h2 id="dialog-title">{{ modal === 'renew' ? '申请借用续期' : '取消借用订单' }}</h2><p>{{ selected.title }} · 订单 {{ selected.id }}</p><form @submit.prevent="submitOrderChange"><template v-if="modal === 'renew'"><p class="form-hint">原归还时间：{{ formatReservationDate(selected.endTime) }}。续期需发布者同意，不能与其他预约冲突。</p><label>希望归还的时间<input v-model="renewalEnd" type="datetime-local" required /></label></template><label v-else>取消原因<textarea v-model="cancelReason" required maxlength="500" rows="4" placeholder="说明无法交付的原因，我们会通知借用人" /></label><p v-if="error" class="error" role="alert">{{ error }}</p><button class="primary-button full-width" type="submit" :disabled="busy">{{ busy ? '正在提交…' : '确认提交' }}</button></form></template>
      <template v-else-if="modal === 'order-detail'"><h2 id="dialog-title">{{ selected.title }}</h2><p>订单 {{ selected.id }} · {{ statusNames[selected.status] }}</p><p>{{ formatReservationDate(selected.startTime) }} — {{ formatReservationDate(selected.endTime) }}</p><p>发布者：{{ selected.owner }} · 借用人：{{ selected.borrower }}</p><p v-if="selected.pickedUpAt">实际领取：{{ formatReservationDate(selected.pickedUpAt) }}</p><p v-if="selected.overdue" class="overdue-label">{{ overdueLabel(selected) }}</p><p v-if="selected.renewalEndTime">{{ renewalNames[selected.renewalStatus] }}：{{ formatReservationDate(selected.renewalEndTime) }}</p><p v-if="selected.cancelReason">取消原因：{{ selected.cancelReason }}</p><button class="primary-button full-width" @click="modal = null; navigate(selected.ownerId === user.id ? '借出管理' : '我的借用')">前往订单管理</button></template>
      <template v-else-if="modal === 'account'"><h2 id="dialog-title">你好，{{ user?.nickname }}</h2><p class="detail-description">账号：{{ user?.username }}。发布、收藏和借用记录已保存在平台。</p><p v-if="error" class="error" role="alert">{{ error }}</p><button class="secondary-button full-width" :disabled="busy" @click="logout"><LogOut :size="17" />{{ busy ? '正在退出…' : '退出登录' }}</button></template>
    </section></div>
    <div v-if="message" class="toast" role="status"><Check :size="17" />{{ message }}</div>
  </div>
</template>
