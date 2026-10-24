<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ArrowUpRight, ArrowRight, Search, Plus, Heart, Grid2X2, CalendarDays, Package, BookOpen, MapPin, ChevronDown, ChevronRight, X, Check, SlidersHorizontal, Camera, Tent, Dumbbell, Laptop, Coffee, Leaf, LogOut, Menu, CircleHelp, Clock3 } from 'lucide-vue-next'
import { categories, initialItems } from './data'

function restore(key, fallback) {
  try { const value = JSON.parse(localStorage.getItem(key)); return Array.isArray(fallback) ? (Array.isArray(value) ? value : fallback) : (typeof value === 'string' ? value : fallback) } catch { return fallback }
}
const items = ref(restore('campus-items-v1', initialItems))
const favorites = ref(restore('campus-favorites-v1', []))
const orders = ref(restore('campus-orders-v1', []))
const user = ref(restore('campus-user-v1', ''))
const storageWarning = ref(false)
for (const [key, state] of [['campus-items-v1', items], ['campus-favorites-v1', favorites], ['campus-orders-v1', orders], ['campus-user-v1', user]]) {
  watch(state, value => { try { localStorage.setItem(key, JSON.stringify(value)) } catch { storageWarning.value = true } }, { deep: true })
}
const nav = ref('发现物品'), category = ref('全部物品'), keyword = ref(''), campus = ref('全部校区'), onlyAvailable = ref(false), sort = ref('推荐排序')
const modal = ref(null), selected = ref(null), message = ref(''), error = ref(''), mobileMenu = ref(false)
const loginName = ref(''), start = ref(''), end = ref('')
const draft = ref({ title: '', description: '', category: '数码摄影', location: '东校区', image: '' })
let messageTimer
const icons = [Grid2X2, Camera, Dumbbell, Tent, Laptop, Coffee]
const navigation = [{ label: '发现物品', icon: Grid2X2 }, { label: '我的借用', icon: CalendarDays }, { label: '我的发布', icon: Package }, { label: '我的收藏', icon: Heart }]
const visibleItems = computed(() => {
  let list = items.value.filter(item =>
    (nav.value !== '我的收藏' || favorites.value.includes(item.id)) &&
    (nav.value !== '我的发布' || item.owner === user.value) &&
    (category.value === '全部物品' || item.category === category.value) &&
    (campus.value === '全部校区' || item.location.includes(campus.value)) &&
    (!onlyAvailable.value || item.available) &&
    `${item.title} ${item.description} ${item.category}`.toLowerCase().includes(keyword.value.trim().toLowerCase()))
  return [...list].sort((a, b) => sort.value === '最新发布' ? b.date - a.date : Number(b.available) - Number(a.available) || b.date - a.date)
})
const myOrders = computed(() => orders.value.filter(order => order.borrower === user.value))
const pendingCount = computed(() => myOrders.value.filter(order => order.status === '待审核').length)
const pageTitle = computed(() => nav.value === '发现物品' ? '发现校园好物' : nav.value)
function notify(text) { message.value = text; clearTimeout(messageTimer); messageTimer = setTimeout(() => message.value = '', 3500) }
function open(type, item = null) { error.value = ''; selected.value = item; modal.value = type; start.value = ''; end.value = '' }
function navigate(label) { nav.value = label; category.value = '全部物品'; keyword.value = ''; mobileMenu.value = false }
function toggleFavorite(id) { favorites.value = favorites.value.includes(id) ? favorites.value.filter(value => value !== id) : [...favorites.value, id] }
function login() {
  if (!loginName.value.trim()) return
  user.value = loginName.value.trim(); modal.value = null; notify(`欢迎你，${user.value}`)
}
function publish() {
  if (!user.value) { open('login'); return }
  open('publish')
}
function fileSelected(event) {
  const file = event.target.files?.[0]; if (!file) return
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || file.size > 1024 * 1024) { error.value = '请选择 1MB 以内的 JPG、PNG 或 WebP 图片'; event.target.value = ''; return }
  const reader = new FileReader(); reader.onload = () => { draft.value.image = reader.result; error.value = '' }; reader.readAsDataURL(file)
}
function submitItem() {
  if (!draft.value.title.trim() || !draft.value.description.trim()) { error.value = '请填写物品名称和描述'; return }
  items.value.unshift({ ...draft.value, title: draft.value.title.trim(), id: Date.now(), date: Date.now(), owner: user.value, avatar: user.value.slice(0, 1), color: '#e1ede5', available: true })
  draft.value = { title: '', description: '', category: '数码摄影', location: '东校区', image: '' }; modal.value = null; navigate('我的发布'); notify('物品已发布到当前浏览器')
}
function book() {
  if (!user.value) { open('login'); return }
  const from = new Date(start.value), to = new Date(end.value)
  if (!start.value || !end.value || from <= new Date() || to <= from) { error.value = '请选择未来的开始时间，结束时间需晚于开始时间'; return }
  if (!selected.value.available || selected.value.owner === user.value) { error.value = '该物品当前无法申请借用'; return }
  if (orders.value.some(order => order.itemId === selected.value.id && order.borrower === user.value && order.status === '待审核' && new Date(order.start) < to && new Date(order.end) > from)) { error.value = '你已提交重叠时段的申请，可在“我的借用”查看'; return }
  orders.value.unshift({ id: Date.now(), itemId: selected.value.id, title: selected.value.title, image: selected.value.image, borrower: user.value, owner: selected.value.owner, start: start.value, end: end.value, status: '待审核' })
  modal.value = null; notify('预约申请已记录，等待发布者审核'); navigate('我的借用')
}
function resetFilters() { category.value = '全部物品'; keyword.value = ''; campus.value = '全部校区'; onlyAvailable.value = false }
function formatDate(value) { return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }) }
function fallbackImage(event) { event.target.style.display = 'none'; (event.target.closest('.card-image') || event.target.parentElement).classList.add('image-failed') }
function handleKey(event) { if (event.key === 'Escape') modal.value = null }
window.addEventListener('keydown', handleKey)
watch(modal, value => { document.body.style.overflow = value ? 'hidden' : ''; if (value) setTimeout(() => document.querySelector('.dialog input, .dialog .close')?.focus(), 0) })
onBeforeUnmount(() => { clearTimeout(messageTimer); window.removeEventListener('keydown', handleKey); document.body.style.overflow = '' })
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
      <nav aria-label="主要导航"><button v-for="entry in navigation" :key="entry.label" :class="{ active: nav === entry.label }" @click="navigate(entry.label)"><component :is="entry.icon" :size="19" /><span>{{ entry.label }}</span><span v-if="entry.label === '我的借用' && pendingCount" class="nav-badge">{{ pendingCount }}</span><ChevronRight v-else-if="nav === entry.label" :size="15" /></button></nav>
      <div class="sidebar-note"><span class="note-icon"><Leaf :size="22" /></span><h3>少一份闲置<br>多一种可能。</h3><p>一本书、一台相机、一顶帐篷，<br>让好物陪更多人走一程。</p><button @click="open('guide')">了解借用规则 <ArrowUpRight :size="15" /></button></div>
      <div class="sidebar-bottom"><button @click="open('guide')"><CircleHelp :size="17" /> 借用指南</button><p>在校园，分享一点美好 <span>↗</span></p></div>
    </aside>

    <div class="main-shell">
      <header class="topbar">
        <button class="mobile-toggle icon-button" aria-label="展开导航" @click="mobileMenu = !mobileMenu"><Menu :size="22" /></button>
        <div class="breadcrumb">校园共享 <ChevronRight :size="14" /><strong>{{ nav }}</strong></div>
        <div class="top-actions"><span class="demo-badge">前端体验版</span><button class="publish-button" @click="publish"><Plus :size="17" /> 发布闲置</button><button v-if="!user" class="user-button" aria-label="登录 / 注册" @click="open('login')"><span class="avatar">同</span><span>登录 / 注册</span></button><button v-else class="user-button" @click="open('account')"><span class="avatar">{{ user.slice(0, 1) }}</span><span>{{ user }}</span><ChevronDown :size="14" /></button></div>
      </header>

      <main>
        <section v-if="nav === '发现物品'" class="hero">
          <div class="hero-copy"><div class="eyebrow"><span></span> 好物共享 · 校园同行</div><h1>需要的，借一下。<br><span>闲置的，分享吧。</span></h1><p>不用每样都拥有，也能拥有更多可能。<br>发现身边同学的闲置好物，让校园生活轻一点。</p><button class="hero-button" @click="publish">分享我的第一件好物 <ArrowUpRight :size="17" /></button><div class="hero-foot"><span class="mini-avatars"><i>林</i><i>周</i><i>陈</i></span><span>从一次分享，认识一位同学</span></div></div>
          <img class="hero-art" src="/hero.svg" alt="可供同学分享借用的相机、书本、背包和篮球插画" />
          <div class="hero-sticker"><span>物尽其用</span><Leaf :size="14" /><small>GOOD THINGS, SHARED.</small></div>
        </section>

        <section class="discovery-section">
          <div class="section-heading"><div><h2>{{ pageTitle }}<span class="heading-dot">.</span></h2><p>{{ nav === '发现物品' ? '总有一件好物，刚好是你需要的。' : nav === '我的借用' ? '每一次借用，都从一份信任开始。' : nav === '我的发布' ? '让你的闲置，成为同学的及时雨。' : '把喜欢的好物留在这里，下次更容易找到。' }}</p></div><span class="section-aside"><Leaf :size="14" /> 免费借用，友好共享</span></div>

          <template v-if="nav !== '我的借用'">
            <div class="search-row"><label class="search-field"><Search :size="20" /><input v-model="keyword" aria-label="搜索物品" placeholder="试试搜索「相机」「羽毛球拍」「帐篷」" /><button v-if="keyword" class="icon-button" aria-label="清空搜索" @click="keyword = ''"><X :size="16" /></button><kbd>/ 好物在这里</kbd></label><label class="campus-select"><MapPin :size="17" /><select v-model="campus" aria-label="选择校区"><option>全部校区</option><option>东校区</option><option>南校区</option><option>西校区</option></select></label></div>
            <div class="category-row"><button v-for="(item, index) in categories" :key="item" :class="{ selected: category === item }" @click="category = item"><component :is="icons[index]" :size="16" />{{ item }}</button></div>
            <div class="results-toolbar"><span>共 <strong>{{ visibleItems.length }}</strong> 件{{ nav === '我的收藏' ? '收藏好物' : '校园好物' }}<span class="toolbar-divider"></span><span class="soft-text">找到需要，也分享所爱</span></span><div><label class="availability"><input v-model="onlyAvailable" type="checkbox" />只看可借</label><span class="toolbar-divider"></span><SlidersHorizontal :size="14" /><select v-model="sort" aria-label="物品排序"><option>推荐排序</option><option>最新发布</option></select></div></div>
            <div v-if="visibleItems.length" class="item-grid">
              <article v-for="item in visibleItems" :key="item.id" class="item-card">
                <div class="card-image" :style="{ backgroundColor: item.color }"><button class="image-link" :aria-label="`查看${item.title}`" @click="open('detail', item)"><img v-if="item.image" :src="item.image" :alt="item.title" loading="lazy" @error="fallbackImage" /><Package v-else :size="55" class="placeholder-icon" /></button><span class="status-tag" :class="{ unavailable: !item.available }"><span></span>{{ item.available ? '可申请借用' : '暂不可借' }}</span><button class="favorite-button" :class="{ saved: favorites.includes(item.id) }" :aria-pressed="favorites.includes(item.id)" :aria-label="favorites.includes(item.id) ? '取消收藏' : '收藏物品'" @click="toggleFavorite(item.id)"><Heart :size="18" /></button><span class="image-fallback"><Package :size="44" />{{ item.category }}</span></div>
                <div class="card-body"><div class="card-category">{{ item.category }}<span>免费借用</span></div><button class="card-title" @click="open('detail', item)">{{ item.title }}</button><p class="item-location"><MapPin :size="13" />{{ item.location }}</p><div class="card-footer"><span class="owner"><span class="owner-avatar" :style="{ backgroundColor: item.color }">{{ item.avatar }}</span>{{ item.owner }}</span><button v-if="nav !== '我的发布'" class="card-action" @click="open('detail', item)">去看看 <ArrowUpRight :size="15" /></button><button v-else class="card-action" @click="item.available = !item.available; notify(item.available ? '物品已上架' : '物品已下架')">{{ item.available ? '下架' : '上架' }}</button></div></div>
              </article>
            </div>
            <div v-else class="empty-state"><Package :size="38" /><h3>{{ nav === '我的发布' ? '分享从第一件好物开始' : '还没有找到合适的好物' }}</h3><p>{{ nav === '我的发布' ? '把闲置物品发布出来，让需要它的同学找到你。' : '换一个关键词，或者看看其他分类。' }}</p><button class="primary-button" @click="nav === '我的发布' ? publish() : resetFilters()">{{ nav === '我的发布' ? '发布物品' : '清除筛选' }}</button></div>
            <div v-if="visibleItems.length" class="list-end"><span></span> 好物不在多，在刚好需要 <Leaf :size="13" /><span></span></div>
          </template>

          <template v-else><div v-if="!myOrders.length" class="empty-state"><CalendarDays :size="40" /><h3>你的下一段借用故事，从这里开始</h3><p>浏览同学们分享的物品，选择需要的时间并提交申请。</p><button class="primary-button" @click="navigate('发现物品')">去发现好物 <ArrowRight :size="16" /></button></div><article v-for="order in myOrders" :key="order.id" class="order-card"><div class="order-icon"><Package :size="28" /></div><div><span class="order-number">申请编号 {{ order.id }}</span><h3>{{ order.title }}</h3><p>{{ formatDate(order.start) }} — {{ formatDate(order.end) }}</p><small>发布者：{{ order.owner }}</small></div><span class="order-status" :class="{ cancelled: order.status === '已取消' }">{{ order.status }}</span><button v-if="order.status === '待审核'" class="secondary-button" @click="order.status = '已取消'; notify('预约申请已取消')">取消申请</button></article><p class="demo-explanation">本页为本地预约演示。真实审核、时间冲突检查与领取归还流程将在接入后端后启用。</p></template>
        </section>
        <section class="bottom-banner"><span class="bottom-icon"><BookOpen :size="25" /></span><div><h3>借的是物品，传递的是信任。</h3><p>按时领取、爱惜使用、如期归还，让每一次分享都有回响。</p></div><button @click="open('guide')">看看借用指南 <ArrowRight :size="17" /></button></section>
        <footer><span>© 2026 同学借一下 · 校园物品借用与预约平台</span><span>让每一件好物，都有新的故事。<Leaf :size="13" /></span></footer>
      </main>
    </div>

    <div v-if="modal" class="modal-backdrop" @click.self="modal = null"><section class="dialog" :class="{ 'detail-dialog': modal === 'detail' }" role="dialog" aria-modal="true" aria-labelledby="dialog-title" @keydown="trapFocus"><button class="close icon-button" aria-label="关闭弹窗" @click="modal = null"><X :size="22" /></button>
      <template v-if="modal === 'detail' && selected"><div class="detail-image"><img v-if="selected.image" :src="selected.image" :alt="selected.title" @error="fallbackImage" /><Package v-else :size="70" /></div><span class="form-eyebrow">{{ selected.category }} · 免费借用</span><h2 id="dialog-title">{{ selected.title }}</h2><p class="detail-meta"><MapPin :size="16" />{{ selected.location }} · {{ selected.owner }}</p><p class="detail-description">{{ selected.description }}</p><template v-if="selected.available && selected.owner !== user"><form @submit.prevent="book"><div class="date-fields"><label>开始时间<input v-model="start" required type="datetime-local" /></label><label>结束时间<input v-model="end" required type="datetime-local" /></label></div><p class="form-hint">提交后等待发布者审核，审核通过才占用预约时段。</p><p v-if="error" class="error" role="alert">{{ error }}</p><button class="primary-button full-width" type="submit">{{ user ? '提交预约申请' : '登录后预约' }}<ArrowUpRight :size="17" /></button></form></template><p v-else class="notice">{{ selected.owner === user ? '这是你发布的物品，可以在“我的发布”管理。' : '这件物品暂不可借，可以先收藏，之后再来看看。' }}</p></template>
      <template v-else-if="modal === 'login'"><span class="form-eyebrow">HELLO, CLASSMATE</span><h2 id="dialog-title">欢迎来到同学借一下</h2><p class="detail-description">先取一个昵称，开始体验校园共享。</p><form @submit.prevent="login"><label>你的昵称<input v-model="loginName" required maxlength="16" placeholder="例如：林同学" autocomplete="nickname" /></label><p class="notice">当前是前端演示，昵称仅用于本机体验，不代表真实账号登录。请勿输入密码或个人敏感信息。</p><button class="primary-button full-width" type="submit">进入体验 <ArrowRight :size="16" /></button></form></template>
      <template v-else-if="modal === 'publish'"><span class="form-eyebrow">SHARE SOMETHING GOOD</span><h2 id="dialog-title">分享一件闲置好物</h2><p class="form-hint">每条发布对应一件实物；第一版免费借用。</p><form class="publish-form" @submit.prevent="submitItem"><label>物品名称<input v-model="draft.title" required maxlength="60" placeholder="例如：适合周末露营的双人帐篷" /></label><div class="date-fields"><label>分类<select v-model="draft.category"><option v-for="item in categories.slice(1)" :key="item">{{ item }}</option></select></label><label>领取位置<input v-model="draft.location" required maxlength="60" placeholder="校区 · 具体位置" /></label></div><label>物品描述<textarea v-model="draft.description" required maxlength="500" rows="4" placeholder="介绍物品状态、包含的配件和使用注意事项" /></label><label>物品图片（可选）<input type="file" accept="image/jpeg,image/png,image/webp" @change="fileSelected" /></label><img v-if="draft.image" class="upload-preview" :src="draft.image" alt="待发布物品预览" /><p v-if="error" class="error" role="alert">{{ error }}</p><button class="primary-button full-width" type="submit"><Plus :size="17" />发布物品</button></form></template>
      <template v-else-if="modal === 'guide'"><span class="form-eyebrow">A LITTLE TRUST GOES A LONG WAY</span><h2 id="dialog-title">校园借用小指南</h2><ol class="guide-list"><li><strong>找到需要的好物</strong><p>看看描述和领取位置，选择开始与结束时间。</p></li><li><strong>等待发布者审核</strong><p>提交申请后不占用时段，审核通过才形成有效预约。</p></li><li><strong>当面检查，按时领取</strong><p>与发布者一起检查物品及配件，由发布者确认交付。</p></li><li><strong>爱惜使用，如期归还</strong><p>借用人申请归还，发布者确认后，借用才算完成。</p></li></ol><p class="notice">第一版不收取租金或押金。请勿通过平台外链接付款。</p><button class="primary-button full-width" @click="modal = null">知道了 <Check :size="16" /></button></template>
      <template v-else-if="modal === 'account'"><h2 id="dialog-title">你好，{{ user }}</h2><p class="detail-description">你正在使用本地演示身份。不同昵称的借用记录分别展示。</p><button class="secondary-button full-width" @click="user = ''; modal = null; navigate('发现物品'); notify('已退出当前体验身份')"><LogOut :size="17" />退出体验身份</button></template>
    </section></div>
    <div v-if="message" class="toast" role="status"><Check :size="17" />{{ message }}</div>
    <div v-if="storageWarning" class="storage-warning" role="alert">浏览器存储空间不足，当前更改可能无法在刷新后保留。</div>
  </div>
</template>
