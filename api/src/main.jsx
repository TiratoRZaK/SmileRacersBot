import React, { useEffect, useMemo, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import './styles.css'

const API = '/api/miniapp'
const tg = window.Telegram?.WebApp
const WITHDRAW_MIN = 100
const TOPUP_MIN = 1
const POLL_INTERVAL_MS = 3500
const INTERACTION_PAUSE_MS = 2500
const MIN_SPLASH_MS = 900
const DEFAULT_TRACK_LENGTH = 62

const RACE_TYPE_LABELS = {
  REGULAR: 'Обычная',
  SPRINT: 'Спринт',
  MARATHON: 'Марафон'
}

const TRACK_THEME_LABELS = {
  asphalt: 'Трасса',
  grass: 'Газон',
  desert: 'Пустыня'
}

const TRACK_THEMES = ['asphalt', 'grass', 'desert']

const TRACK_THEME_BACKGROUNDS = {
  asphalt: ['#394457', '#202736'],
  grass: ['#2f7d4f', '#1f5938'],
  desert: ['#c9a363', '#9d7040']
}

const TOAST_AUTO_CLOSE_MS = 4500
const PERSISTENT_ACTIONS = new Set(['withdraw', 'withdraw/cancel', 'battle', 'battle/start', 'topup'])
const MAX_SAVED_NOTIFICATIONS = 200

const formatStars = (value) => new Intl.NumberFormat('ru-RU').format(Number(value) || 0)

const createSeededRandom = (seed) => {
  let value = Math.max(1, Number(seed) || 1)
  return () => {
    value = (value * 1664525 + 1013904223) % 4294967296
    return value / 4294967296
  }
}

const buildTrackBackgroundImage = (theme, units, seed) => {
  const [fromColor, toColor] = TRACK_THEME_BACKGROUNDS[theme] || TRACK_THEME_BACKGROUNDS.asphalt
  const emojis = (units || []).map((u) => u.playerName).filter(Boolean)
  const random = createSeededRandom(seed)
  const emojiPool = emojis.length ? emojis : ['🏁']

  const marksCount = 90
  const minDistance = 36
  const placed = []

  const pickEmoji = (x, y) => {
    const close = placed
      .filter((m) => {
        const dx = m.x - x
        const dy = m.y - y
        return Math.sqrt(dx * dx + dy * dy) < 68
      })
      .map((m) => m.emoji)
    const available = emojiPool.filter((emoji) => !close.includes(emoji))
    const source = available.length ? available : emojiPool
    return source[Math.floor(random() * source.length)]
  }

  let attempts = 0
  while (placed.length < marksCount && attempts < marksCount * 40) {
    attempts += 1
    const x = Math.round(random() * 1160 + 20)
    const y = Math.round(random() * 280 + 20)
    const overlaps = placed.some((m) => {
      const dx = m.x - x
      const dy = m.y - y
      return Math.sqrt(dx * dx + dy * dy) < minDistance
    })
    if (overlaps) continue

    const emoji = pickEmoji(x, y)
    const size = Math.round(22 + random() * 12)
    const rotation = Math.round(random() * 24 - 12)
    const opacity = (0.14 + random() * 0.13).toFixed(2)
    placed.push({ x, y, emoji, size, rotation, opacity })
  }

  const marks = placed.map((m) => `<text x="${m.x}" y="${m.y}" font-size="${m.size}" opacity="${m.opacity}" transform="rotate(${m.rotation} ${m.x} ${m.y})">${m.emoji}</text>`).join('')

  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="320" viewBox="0 0 1200 320"><defs><linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="${fromColor}"/><stop offset="100%" stop-color="${toColor}"/></linearGradient></defs><rect width="1200" height="320" fill="url(#bg)"/>${marks}</svg>`
  return `url("data:image/svg+xml,${encodeURIComponent(svg)}")`
}

const normalizeType = (type) => String(type || '').trim().toUpperCase()
const getRaceTypeLabel = (type) => RACE_TYPE_LABELS[normalizeType(type)] || type || 'Неизвестно'
const telegramUser = tg?.initDataUnsafe?.user || null
const telegramUserId = telegramUser?.id || null

const getTelegramAccountLabel = (user) => {
  if (!user) return 'Не удалось определить Telegram-аккаунт'
  if (user.username) return `@${user.username}`
  const fullName = [user.first_name, user.last_name].filter(Boolean).join(' ').trim()
  return fullName || `ID ${user.id}`
}

const getTrackTheme = (race) => {
  if (!race) return TRACK_THEMES[0]
  const seed = Number(race.matchId || 0)
  return TRACK_THEMES[Math.abs(seed) % TRACK_THEMES.length]
}

const queryUserId = Number(new URLSearchParams(location.search).get('userId') || 0)
const getUserId = () => telegramUserId || (Number.isFinite(queryUserId) && queryUserId > 0 ? queryUserId : null)


const getBattleStateLabel = (battle) => {
  if (!battle) return ''
  if (battle.status === 'LIVE') return '🚦 Батл уже начался'
  if (battle.status === 'COMPLETED') return '🏁 Батл завершён'
  if (battle.battleStartRequested) return '⏳ Батл ждёт очереди на запуск'
  if ((battle.units?.length || 0) < 2) return 'Нужно минимум 2 участника для старта'
  return 'Готов к старту. Нажмите «Старт батла»'
}

const getBattleParticipantLabel = (unit) => {
  const owner = unit.ownerName || (unit.ownerUserId ? `ID ${unit.ownerUserId}` : 'Неизвестный игрок')
  return `${owner} (${unit.playerName})`
}

const getBattleBank = (battle) => {
  if (!battle?.units?.length) return 0
  return battle.units.reduce((sum, unit) => sum + (Number(battle.battleStake) || 0), 0)
}

function App() {
  const userId = useMemo(getUserId, [])
  const [data, setData] = useState(null)
  const [activeWithdraws, setActiveWithdraws] = useState([])
  const [historyItems, setHistoryItems] = useState([])
  const [recentResults, setRecentResults] = useState([])
  const [isHistoryLoaded, setIsHistoryLoaded] = useState(false)
  const [isRecentResultsLoaded, setIsRecentResultsLoaded] = useState(false)
  const [historyLoading, setHistoryLoading] = useState(false)
  const [recentResultsLoading, setRecentResultsLoading] = useState(false)
  const [tab, setTab] = useState('race')
  const [toasts, setToasts] = useState([])
  const [savedNotifications, setSavedNotifications] = useState([])
  const [isNotificationsOpen, setIsNotificationsOpen] = useState(false)
  const [battleEmoji, setBattleEmoji] = useState('')
  const [voteInputs, setVoteInputs] = useState({})
  const [topupAmount, setTopupAmount] = useState(100)
  const [withdrawAmount, setWithdrawAmount] = useState(100)
  const [favoriteIndex, setFavoriteIndex] = useState(0)
  const [joinBattleId, setJoinBattleId] = useState('')
  const [joinBattleEmoji, setJoinBattleEmoji] = useState('')
  const [localVotes, setLocalVotes] = useState({})
  const [isHeaderCompact, setIsHeaderCompact] = useState(false)
  const [isAppReady, setIsAppReady] = useState(false)
  const [bootProgress, setBootProgress] = useState(14)
  const [favoriteDirty, setFavoriteDirty] = useState(false)
  const [sectionOpen, setSectionOpen] = useState({
    account: true,
    favorite: true,
    payments: true,
    battles: true,
    history: false,
    recentRaces: false
  })
  const refreshInFlightRef = useRef(false)
  const interactionPauseUntilRef = useRef(0)
  const firstLoadStartedAtRef = useRef(Date.now())
  const favoriteDirtyRef = useRef(false)
  const favoriteRequestRef = useRef(null)

  const requestQuery = useMemo(() => {
    const params = new URLSearchParams()
    if (userId != null) params.set('userId', String(userId))
    return params.toString()
  }, [userId])
  const requestHeaders = useMemo(() => {
    const headers = {}
    if (telegramUserId) headers['X-Telegram-User-Id'] = String(telegramUserId)
    if (tg?.initData) headers['X-Telegram-Init-Data'] = tg.initData
    return headers
  }, [])

  useEffect(() => {
    favoriteDirtyRef.current = favoriteDirty
  }, [favoriteDirty])

  const pausePollingForInteraction = () => {
    interactionPauseUntilRef.current = Date.now() + INTERACTION_PAUSE_MS
  }

  const refresh = async (silent = false) => {
    if (refreshInFlightRef.current) return
    refreshInFlightRef.current = true

    try {
      const [bootstrapRes, withdrawsRes] = await Promise.all([
        fetch(`${API}/bootstrap${requestQuery ? `?${requestQuery}` : ''}`, { headers: requestHeaders }),
        fetch(`${API}/withdraw/active${requestQuery ? `?${requestQuery}` : ''}`, { headers: requestHeaders })
      ])
      const bootstrapData = await bootstrapRes.json()
      const withdrawData = await withdrawsRes.json()
      setData(bootstrapData)
      setActiveWithdraws(withdrawData.items || [])

      if (!silent) {
        const elapsed = Date.now() - firstLoadStartedAtRef.current
        const delay = Math.max(0, MIN_SPLASH_MS - elapsed)
        window.setTimeout(() => {
          setBootProgress(100)
          setIsAppReady(true)
        }, delay)
      }
    } finally {
      refreshInFlightRef.current = false
    }
  }

  const loadHistory = async () => {
    if (historyLoading || isHistoryLoaded) return
    setHistoryLoading(true)
    try {
      const historyRes = await fetch(`${API}/history${requestQuery ? `?${requestQuery}` : ''}`, { headers: requestHeaders })
      const historyData = await historyRes.json()
      setHistoryItems(historyData.items || [])
      setIsHistoryLoaded(true)
    } finally {
      setHistoryLoading(false)
    }
  }

  const loadRecentResults = async () => {
    if (recentResultsLoading || isRecentResultsLoaded) return
    setRecentResultsLoading(true)
    try {
      const recentRes = await fetch(`${API}/recent-results${requestQuery ? `?${requestQuery}` : ''}`, { headers: requestHeaders })
      const recentData = await recentRes.json()
      setRecentResults(recentData.items || [])
      setIsRecentResultsLoaded(true)
    } finally {
      setRecentResultsLoading(false)
    }
  }

  useEffect(() => {
    tg?.ready()
    tg?.expand?.()
    refresh().catch(() => {
      setBootProgress(100)
      setIsAppReady(true)
      notify('Не удалось загрузить данные MiniApp.', { persist: true })
    })
  }, [])

  useEffect(() => {
    if (isAppReady) return undefined

    const timer = window.setInterval(() => {
      setBootProgress((current) => Math.min(current + (current < 70 ? 11 : 4), 92))
    }, 140)

    return () => window.clearInterval(timer)
  }, [isAppReady])

  useEffect(() => {
    const timer = setInterval(() => {
      if (document.hidden) return
      if (Date.now() < interactionPauseUntilRef.current) return
      refresh(true).catch(() => null)
    }, POLL_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [userId, tab])

  useEffect(() => {
    const onScroll = () => {
      setIsHeaderCompact(window.scrollY > 24)
    }
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  useEffect(() => {
    if (!data?.allEmojis?.length) return
    setBattleEmoji((current) => current || data.allEmojis[0])
    const currentFavorite = data.favoriteEmoji || data.allEmojis[0]
    const idx = Math.max(0, data.allEmojis.indexOf(currentFavorite))
    const pendingFavorite = favoriteRequestRef.current
    const favoriteWasSaved = pendingFavorite && pendingFavorite === currentFavorite

    if (!favoriteDirtyRef.current || favoriteWasSaved) {
      setFavoriteIndex(idx)
      setFavoriteDirty(false)
      if (favoriteWasSaved) favoriteRequestRef.current = null
    }

    setJoinBattleEmoji((current) => current || data.allEmojis[0])
  }, [data])

  useEffect(() => {
    if (!data?.race?.matchId) {
      setLocalVotes({})
      return
    }

    const raceVotes = (data.race.units || []).reduce((acc, unit) => {
      acc[unit.playerNumber] = Number(unit.myVotes) || 0
      return acc
    }, {})

    setLocalVotes((current) => {
      const merged = { ...current }
      Object.entries(raceVotes).forEach(([playerNumber, backendVotes]) => {
        merged[playerNumber] = Math.max(Number(merged[playerNumber]) || 0, Number(backendVotes) || 0)
      })
      return merged
    })
  }, [data?.race?.matchId, data?.race?.units])

  useEffect(() => {
    if (!isNotificationsOpen) return
    setSavedNotifications((current) => current.map((item) => ({ ...item, read: true })))
  }, [isNotificationsOpen])

  useEffect(() => {
    const apiNotifications = data?.notifications || []
    if (!apiNotifications.length) return

    setSavedNotifications((current) => {
      const byId = new Map(current.map((item) => [String(item.id), item]))
      apiNotifications.forEach((item) => {
        if (!item?.id || !item?.text) return
        const key = String(item.id)
        const existing = byId.get(key)
        byId.set(key, {
          id: item.id,
          text: item.text,
          persist: true,
          createdAt: item.createdAtMs ? new Date(item.createdAtMs).toISOString() : new Date().toISOString(),
          read: existing?.read || false,
          source: 'server'
        })
      })

      return [...byId.values()]
        .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
        .slice(0, MAX_SAVED_NOTIFICATIONS)
    })
  }, [data?.notifications])

  const removeToast = (id) => {
    setToasts((current) => current.filter((item) => item.id !== id))
  }

  const notify = (text, options = {}) => {
    const { persist = false } = options
    if (!text) return
    const id = Date.now() + Math.floor(Math.random() * 10000)
    const notification = {
      id,
      text,
      persist,
      createdAt: new Date().toISOString(),
      read: false
    }

    setToasts((current) => [...current, notification])
    if (persist) {
      setSavedNotifications((current) => [notification, ...current].slice(0, MAX_SAVED_NOTIFICATIONS))
    }
    if (!persist) {
      setTimeout(() => removeToast(id), TOAST_AUTO_CLOSE_MS)
    }
    return id
  }

  const act = async (path, body) => {
    const res = await fetch(`${API}/${path}${requestQuery ? `?${requestQuery}` : ''}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...requestHeaders },
      body: JSON.stringify(body)
    })
    const r = await res.json()
    if (path === 'vote' && res.ok && body?.amount && body?.playerNumber) {
      const votedUnit = (data?.race?.units || []).find((u) => u.playerNumber === body.playerNumber)
      const target = votedUnit?.playerName || `участника #${body.playerNumber}`
      notify(`✅ Принято: ${formatStars(body.amount)} ⭐ за ${target}`)
    } else if (path === 'boost' && res.ok) {
      const toastId = notify(r.message)
      if (toastId != null) {
        setTimeout(() => removeToast(toastId), 250)
      }
    } else {
      notify(r.message, { persist: !res.ok || PERSISTENT_ACTIONS.has(path) })
    }
    if (r.invoiceLink) {
      tg?.openLink ? tg.openLink(r.invoiceLink) : window.open(r.invoiceLink, '_blank')
    }
    await refresh(true)
    if (path === 'vote' && res.ok) {
      setTimeout(() => {
        refresh(true).catch(() => null)
      }, 400)
    }
    return { ...r, httpOk: res.ok }
  }

  const maxWithdraw = data?.balance || WITHDRAW_MIN
  const clampedWithdraw = Math.max(WITHDRAW_MIN, Math.min(withdrawAmount || WITHDRAW_MIN, maxWithdraw))


  const requestBattleStartFromUi = async () => {
    if (!myBattle) {
      notify('Сначала создайте батл, затем можно стартовать и приглашать друзей.', { persist: true })
      return
    }
    await act('battle/start', { matchId: myBattle.matchId })
  }


  const removeBattleParticipant = async (playerNumber) => {
    if (!myBattle) return
    await act('battle/remove-participant', { matchId: myBattle.matchId, playerNumber })
  }

  const joinBattleFromUi = async () => {
    const matchId = Number(joinBattleId)
    if (!matchId || !joinBattleEmoji) {
      notify('Укажите ID батла и смайл для входа.', { persist: true })
      return
    }
    await act('battle/join', { matchId, playerName: joinBattleEmoji })
    setJoinBattleId('')
  }

  const cancelMyBattle = async () => {
    if (!myBattle?.matchId) return
    await act('battle/cancel', { matchId: myBattle.matchId })
  }

  const openBattleInvite = (battle) => {
    if (!battle?.inviteLink) {
      notify('Ссылка приглашения для батла недоступна.', { persist: true })
      return
    }
    const shareText = encodeURIComponent(`✨ Вызываю тебя на батл Emoji Race!\nБатл #${battle.matchId} уже ждёт тебя.`)
    const shareUrl = `https://t.me/share/url?url=${encodeURIComponent(battle.inviteLink)}&text=${shareText}`
    if (tg?.openLink) tg.openLink(shareUrl)
    else window.open(shareUrl, '_blank')
  }

  const openHelp = async () => {
    const response = await fetch(`${API}/help`)
    const payload = await response.json()
    const link = payload?.message
    if (link && /^https?:\/\//.test(link)) {
      tg?.openLink ? tg.openLink(link) : window.open(link, '_blank')
      return
    }
    notify('Ссылка на поддержку временно недоступна.')
  }


  const toggleSection = (key) => {
    setSectionOpen((current) => {
      const nextOpen = !current[key]
      if (nextOpen && key === 'history') {
        loadHistory().catch(() => notify('Не удалось загрузить историю операций.', { persist: true }))
      }
      if (nextOpen && key === 'recentRaces') {
        loadRecentResults().catch(() => notify('Не удалось загрузить последние гонки.', { persist: true }))
      }
      return { ...current, [key]: nextOpen }
    })
  }

  const downloadHistory = async () => {
    const response = await act('history/export')
    if (response?.httpOk) {
      notify('Файл отправлен в чат с ботом.', { persist: true })
    }
  }

  const renderSection = (key, title, content) => <div className='accordion-section'>
    <button className='accordion-header' onClick={() => toggleSection(key)}>
      <span>{title}</span>
      <span className='accordion-arrow'>{sectionOpen[key] ? '▴' : '▾'}</span>
    </button>
    {sectionOpen[key] && <div className='accordion-content'>{content}</div>}
  </div>

  const deleteSavedNotification = async (notificationId) => {
    if (!notificationId) return
    try {
      const response = await fetch(`${API}/notification/delete${requestQuery ? `?${requestQuery}` : ''}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...requestHeaders },
        body: JSON.stringify({ notificationId })
      })
      const payload = await response.json()
      if (!response.ok || !payload?.success) {
        notify(payload?.message || 'Не удалось удалить уведомление.', { persist: true })
        return
      }
      setSavedNotifications((current) => current.filter((n) => n.id !== notificationId))
    } catch (e) {
      notify('Ошибка удаления уведомления.', { persist: true })
    }
  }

  const clearSavedNotifications = async () => {
    try {
      const response = await fetch(`${API}/notification/clear${requestQuery ? `?${requestQuery}` : ''}`, {
        method: 'POST',
        headers: requestHeaders
      })
      const payload = await response.json()
      if (!response.ok || !payload?.success) {
        notify(payload?.message || 'Не удалось очистить уведомления.', { persist: true })
        return
      }
      setSavedNotifications([])
    } catch (e) {
      notify('Ошибка очистки уведомлений.', { persist: true })
    }
  }

  const raceBeforeStart = data?.race?.status === 'CREATED'
  const myBattle = data?.myBattle || null
  const myBattleCanStart = !!myBattle && myBattle.status === 'CREATED' && !myBattle.battleStartRequested
  const myBattleCanInvite = !!myBattle?.inviteLink
  const myBattleIsLive = myBattle?.status === 'LIVE'
  const boostersDisabled = !data?.race || raceBeforeStart
  const trackTheme = getTrackTheme(data?.race)
  const raceUnits = data?.race?.units || []
  const maxScore = raceUnits.reduce((max, unit) => Math.max(max, Number(unit.score) || 0), 0)
  const finishScore = Math.max(Number(data?.race?.trackLength) || DEFAULT_TRACK_LENGTH, maxScore, 1)
  const trackBackgroundStyle = useMemo(() => ({
    backgroundImage: buildTrackBackgroundImage(trackTheme, raceUnits, data?.race?.matchId || 1),
    backgroundRepeat: 'no-repeat',
    backgroundSize: 'cover',
    backgroundPosition: 'center'
  }), [trackTheme, raceUnits, data?.race?.matchId])
  const unreadCount = savedNotifications.filter((item) => !item.read).length
  const myBattleCanCancel = !!myBattle && myBattle.status === 'CREATED'

  if (!data || !isAppReady) return <div className='loading-screen'>
    <div className='loading-orb loading-orb-left' />
    <div className='loading-orb loading-orb-right' />
    <div className='loading-card'>
      <div className='loading-logo'>🏁</div>
      <h1>Smile Racers</h1>
      <p>Подготавливаем трассу, смайлы и бустеры…</p>
      <div className='loading-bar'>
        <div className='loading-bar-fill' style={{ width: `${bootProgress}%` }} />
      </div>
      <div className='loading-progress'>{bootProgress}%</div>
    </div>
  </div>

  return <div className='app'>
    <div className='aurora' />
    <div className={`sticky-header-shell${isHeaderCompact ? ' compact' : ''}`}>
      <header className='top-card'>
        <div className='top-card-main'>
          <div>
            <span className='label'>Баланс</span>
            <b>{data.balance} ⭐</b>
          </div>
          <div>
            <span className='label'>Бесплатные бустеры</span>
            <b>{data.freeBoosters}</b>
          </div>
        </div>
        <div className='top-card-actions'>
          <button className='bell-btn' onClick={() => setIsNotificationsOpen((current) => !current)}>
            🔔
            {unreadCount > 0 && <span className='bell-badge'>{unreadCount}</span>}
          </button>
        </div>
      </header>

      <nav className='tabs'>
        <button className={tab === 'race' ? 'active' : ''} onClick={() => setTab('race')}>Гонки</button>
        <button className={tab === 'account' ? 'active' : ''} onClick={() => setTab('account')}>Аккаунт</button>
      </nav>

      {isNotificationsOpen && <section className='panel notifications-panel'>
        <div className='notifications-header'>
          <h3>Уведомления</h3>
          <button
            className='chip'
            disabled={!savedNotifications.length}
            onClick={clearSavedNotifications}
          >
            Очистить все
          </button>
        </div>
        {!savedNotifications.length && <p className='subtitle'>Пока нет сохранённых уведомлений.</p>}
        {!!savedNotifications.length && <div className='notifications-list'>
          {savedNotifications.map((item) => <div key={item.id} className='notification-item'>
            <div>
              <p>{item.text}</p>
              <p className='subtitle'>{new Date(item.createdAt).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })}</p>
            </div>
            <button className='chip danger-chip' onClick={() => deleteSavedNotification(item.id)}>Удалить</button>
          </div>)}
        </div>}
      </section>}
    </div>

    {tab === 'race' && <section className={`panel race-panel race-theme-${trackTheme}`}>
      <h2>{data.race ? `Гонка #${data.race.matchId} · ${getRaceTypeLabel(data.race.type)}` : 'Нет активной гонки'}</h2>
      {!data.race && <p className='subtitle'>Скоро начнётся новая гонка или создайте батл (вкладка «Аккаунт» → блок «Батлы»).</p>}

      {!!data.race && <p className='race-theme-label'>Стиль: {TRACK_THEME_LABELS[trackTheme]}</p>}
      {!!data.race && !raceBeforeStart && <p className='race-intro'>{`🔥 Гонка в самом разгаре! 🔥
Помоги своему фавориту придти на 🏁 первым!

Используй бустеры на кнопках ниже:
 🐇 (10⭐️) - временно ускоряет выбранный смайл
 🐢 (10⭐️) - временно замедляет выбранный смайл
 🪖 (40⭐️) - позволяет защититься от 5-ти 🐢`}</p>}
      {!!data.race && !raceBeforeStart && <div className='booster-legend'>
        <span><b>🐇</b> ускорить</span>
        <span><b>🐢</b> замедлить</span>
        <span><b>🪖</b> защитить</span>
      </div>}
      {!!data.race && <div className={`track track-${trackTheme}`} style={trackBackgroundStyle}>
      {raceUnits.map((u) => {
        const score = Number(u.score) || 0
        const percent = finishScore ? Math.min(100, Math.round(score / finishScore * 100)) : 0
        const runnerLeft = `${2 + percent * 0.96}%`
        const activeBooster = String(u.activeBooster || 'NONE').toUpperCase()
        const shieldsCount = Math.max(0, Number(u.playerShields) || 0)
        const shieldSlots = 5
        const consumedShields = shieldsCount > shieldSlots ? 0 : shieldSlots - shieldsCount

        return <div className='unit lane' key={u.playerNumber}>
        <div className='unit-head'>
          <div className='score'>{percent}%</div>
        </div>
        <div className='meter'>
          <div className='meter-fill' style={{ width: `${percent}%` }} />
          <div className='runner' style={{ left: runnerLeft }}>{u.playerName}</div>
          {activeBooster === 'BUST' && <div className='runner-booster runner-booster-bust' style={{ left: runnerLeft }}>
            <span className='runner-booster-icon'>🐇</span>
            <span className='runner-booster-icon'>⚡</span>
          </div>}
          {activeBooster === 'SLOW' && <div className='runner-booster runner-booster-slow' style={{ left: runnerLeft }}>
            <span className='runner-booster-icon'>🐢</span>
            <span className='runner-booster-icon'>⛔</span>
          </div>}
          {shieldsCount > 0 && <div className='runner-shields' style={{ left: runnerLeft }}>
            {shieldsCount > shieldSlots
              ? <div className='shield-counter'>{shieldsCount}🛡️</div>
              : Array.from({ length: shieldSlots }, (_, index) => <span key={index} className={`shield-chip ${index < consumedShields ? 'used' : ''}`}>🛡️</span>)}
          </div>}
          <div className='finish-line' />
        </div>
        {raceBeforeStart && <div className='vote-inline-wrap'>
          <div className='vote-caption'>
            <span>Твой голос: {formatStars(localVotes[u.playerNumber] ?? u.myVotes)} ⭐</span>
          </div>
          <div className='vote-inline'>
          <div className='vote-field-wrap'>
            <input
              className='field vote-field'
              type='text'
              inputMode='numeric'
              placeholder={`до ${formatStars(Math.max(1, data.balance || 1))}`}
              value={voteInputs[u.playerNumber] ?? 1}
              onChange={(e) => {
                pausePollingForInteraction()
                const digits = String(e.target.value || '').replace(/\D/g, '')
                const raw = Number(digits || 1)
                const next = Math.max(1, Math.min(raw, Math.max(1, data.balance || 1)))
                setVoteInputs((current) => ({ ...current, [u.playerNumber]: next }))
              }}
              onFocus={pausePollingForInteraction}
            />
            <span className='vote-field-suffix'>⭐</span>
          </div>
          <button
            disabled={!data.balance || data.balance < 1}
            onClick={async () => {
              const amount = voteInputs[u.playerNumber] ?? 1
              const playerNumber = u.playerNumber
              const currentVote = Number(localVotes[playerNumber] ?? u.myVotes) || 0
              const response = await act('vote', { matchId: data.race.matchId, playerNumber: u.playerNumber, amount })
              if (response?.httpOk) {
                setLocalVotes((current) => ({
                  ...current,
                  [playerNumber]: Number(response.myVotes) || (currentVote + Number(amount || 0))
                }))
              }
              setVoteInputs((current) => ({ ...current, [u.playerNumber]: 1 }))
            }}
          >
            Отдать голос
          </button>
          </div>
        </div>}
        {!raceBeforeStart && <div className='booster-shell'>
          <div className='booster-actions'>
            <button className='booster booster-bust' aria-label={`Ускорить ${u.playerName}`} title={`Ускорить ${u.playerName}`} disabled={boostersDisabled} onClick={async () => { await act('boost', { playerNumber: u.playerNumber, type: 'BUST' }) }}><span>🐇</span></button>
            <button className='booster booster-slow' aria-label={`Замедлить ${u.playerName}`} title={`Замедлить ${u.playerName}`} disabled={boostersDisabled} onClick={async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SLOW' }) }}><span>🐢</span></button>
            <button className='booster booster-shield' aria-label={`Защитить ${u.playerName}`} title={`Защитить ${u.playerName}`} disabled={boostersDisabled} onClick={async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SHIELD' }) }}><span>🪖</span></button>
          </div>
        </div>}
      </div>
      })}
      </div>}

      {myBattle && <div className='my-battle-card'>
        <h3>⚔️ Ваш батл #{myBattle.matchId}</h3>
        <p className='subtitle'>Голос за победу: {myBattle.battleStake || 0} ⭐</p>
        <p className='subtitle'>Общий банк: {getBattleBank(myBattle)} ⭐</p>
        <p className='subtitle'>{getBattleStateLabel(myBattle)}</p>
        <div className='battle-participants-list'>
          {(myBattle.units || []).map((u) => <div key={u.playerNumber} className='battle-participant-row'>
            <span>{getBattleParticipantLabel(u)}</span>
            <button className='chip danger-chip' disabled={myBattleIsLive || u.playerNumber === 1} onClick={() => removeBattleParticipant(u.playerNumber)}>Исключить</button>
          </div>)}
        </div>
        <div className='row'>
          <button disabled={!myBattleCanInvite} onClick={() => openBattleInvite(myBattle)}>Пригласить друзей</button>
          <button
            disabled={!myBattleCanStart}
            onClick={requestBattleStartFromUi}
          >
            Старт батла
          </button>
        </div>
      </div>}
    </section>}

    {tab === 'race' && <section className='panel race-recent-panel'>
      {renderSection('recentRaces', 'Последние гонки', <>
        {recentResultsLoading && <p className='subtitle'>Загружаем последние гонки…</p>}
        {isRecentResultsLoaded && !recentResults.length && <p className='subtitle'>Пока нет завершённых гонок.</p>}
        {recentResults.map((result) => <div key={result.matchId} className='recent-result-card'>
          <div className='recent-result-title'>#{result.matchId} · {getRaceTypeLabel(result.type)}</div>
          <div className='subtitle'>Победитель: {result.winnerName || '—'}</div>
          <div className='subtitle'>Участники: {(result.units || []).map((u) => u.playerName).join(' · ')}</div>
        </div>)}
      </>)}
    </section>}

    {tab === 'account' && <section className='panel account-panel'>
      {renderSection('account', 'Данные об аккаунте', <>
        <p className='subtitle'>
          {getTelegramAccountLabel(telegramUser)}
          {telegramUser?.id ? ` · ID ${telegramUser.id}` : ''}
        </p>
        <p className='subtitle'>
          {data.localTestMode
            ? `Тестовый режим: баланс берётся из аккаунта владельца (ID ${data.userId}).`
            : `Баланс и операции выполняются для текущего аккаунта (ID ${data.userId}).`}
        </p>
      </>)}

      {renderSection('favorite', 'Любимый смайл', <>
        <div className='emoji-slider-wrap'>
          <input
            type='range'
            min='0'
            max={Math.max(0, data.allEmojis.length - 1)}
            value={favoriteIndex}
            onChange={(e) => { pausePollingForInteraction(); setFavoriteDirty(true); setFavoriteIndex(Number(e.target.value)) }}
            onPointerDown={pausePollingForInteraction}
            onTouchStart={pausePollingForInteraction}
            className='emoji-slider'
          />
          <div className='emoji-preview'>{data.allEmojis[favoriteIndex]}</div>
          <div className='row'>
            <button onClick={() => {
              const nextFavorite = data.allEmojis[favoriteIndex]
              favoriteRequestRef.current = nextFavorite
              setFavoriteDirty(false)
              act('favorite', { playerName: nextFavorite })
            }}>Сохранить любимый смайл</button>
          </div>
        </div>
        <p className='subtitle'>Смена любимого смайла — 150⭐ (первый выбор бесплатный).</p>
        <div className='row'>
          <button disabled={!data.favoriteEmoji} onClick={() => act('queue', { playerName: data.favoriteEmoji })}>Добавить любимый смайл в очередь (10⭐)</button>
        </div>
      </>)}

      {renderSection('payments', 'Пополнение и вывод', <>
        <div className='row'>
          <input className='field' type='number' min={TOPUP_MIN} step='1' value={topupAmount} onChange={(e) => { pausePollingForInteraction(); setTopupAmount(Math.max(TOPUP_MIN, Number(e.target.value || TOPUP_MIN))) }} onFocus={pausePollingForInteraction} />
          <button onClick={() => act('topup', { amount: topupAmount })}>Пополнить через ⭐</button>
        </div>
        <div className='row'>
          <input
            className='field'
            type='number'
            min={WITHDRAW_MIN}
            max={maxWithdraw}
            step='1'
            value={withdrawAmount}
            onChange={(e) => { pausePollingForInteraction(); setWithdrawAmount(Number(e.target.value || WITHDRAW_MIN)) }}
            onFocus={pausePollingForInteraction}
          />
          <button disabled={clampedWithdraw > maxWithdraw} onClick={() => act('withdraw', { amount: clampedWithdraw })}>Создать запрос на вывод</button>
        </div>
        <p className='subtitle'>Доступно к выводу: до {maxWithdraw} ⭐. Минимум: {WITHDRAW_MIN} ⭐.</p>
        <div className='grid'>
          {activeWithdraws.map((w) => <button key={w.id} className='chip' onClick={() => act('withdraw/cancel', { requestId: w.id })}>Отменить вывод #{w.id} ({w.amount}⭐)</button>)}
        </div>
      </>)}

      {renderSection('battles', 'Батлы', <>
        <div className='row'>
          <select value={battleEmoji} onChange={(e) => setBattleEmoji(e.target.value)}>{data.allEmojis.map((e) => <option key={e}>{e}</option>)}</select>
          <button onClick={() => act('battle', { playerName: battleEmoji, stake: 100 })}>Создать батл 100⭐</button>
        </div>
        <div className='row'>
          <button disabled={!myBattleCanInvite} onClick={() => openBattleInvite(myBattle)}>
            {myBattle ? `Пригласить друзей в батл #${myBattle.matchId}` : 'Пригласить друзей'}
          </button>
          <button disabled={!myBattleCanStart} onClick={requestBattleStartFromUi}>Старт батла</button>
          <button className='chip danger-chip' disabled={!myBattleCanCancel} onClick={cancelMyBattle}>Отменить мой батл</button>
        </div>
        {!myBattle && <p className='subtitle'>Сначала создайте батл, затем появится ссылка приглашения и станет доступен старт.</p>}
        {myBattle && <>
          <p className='subtitle'>Голос за победу: {myBattle.battleStake || 0} ⭐</p>
          <p className='subtitle'>Общий банк: {getBattleBank(myBattle)} ⭐</p>
          <p className='subtitle'>{getBattleStateLabel(myBattle)}</p>
          <div className='battle-participants-list'>
            {(myBattle.units || []).map((u) => <div key={u.playerNumber} className='battle-participant-row'>
              <span>{getBattleParticipantLabel(u)}</span>
              <button className='chip danger-chip' disabled={myBattleIsLive || u.playerNumber === 1} onClick={() => removeBattleParticipant(u.playerNumber)}>Исключить</button>
            </div>)}
          </div>
        </>}

        <div className='row'>
          <input
            className='field'
            type='number'
            min='1'
            step='1'
            placeholder='ID батла'
            value={joinBattleId}
            onChange={(e) => { pausePollingForInteraction(); setJoinBattleId(e.target.value) }}
            onFocus={pausePollingForInteraction}
          />
          <select value={joinBattleEmoji} onChange={(e) => setJoinBattleEmoji(e.target.value)}>{data.allEmojis.map((emoji) => <option key={emoji}>{emoji}</option>)}</select>
          <button onClick={joinBattleFromUi}>Присоединиться к батлу</button>
        </div>
      </>)}

      {renderSection('history', 'История операций', <>
        {historyLoading && <p className='subtitle'>Загружаем историю операций…</p>}
        {!!historyItems.length && <div className='history-table-wrap'>
          <table className='history-table'>
            <thead>
              <tr>
                <th>Дата</th>
                <th>Операция</th>
                <th>Сумма</th>
                <th>Детали</th>
              </tr>
            </thead>
            <tbody>
              {historyItems.map((item, index) => <tr key={`${item.createdAtMs || 0}-${index}`}>
                <td>{item.createdAtMs ? new Date(item.createdAtMs).toLocaleString('ru-RU') : '—'}</td>
                <td>{item.operation}</td>
                <td className={Number(item.amount) >= 0 ? 'amount-plus' : 'amount-minus'}>{formatStars(item.amount)} ⭐</td>
                <td>{item.details || '—'}</td>
              </tr>)}
            </tbody>
          </table>
        </div>}
        {isHistoryLoaded && !historyLoading && !historyItems.length && <p className='subtitle'>История пока пустая.</p>}
        <div className='row'>
          <button onClick={downloadHistory}>Скачать всю историю</button>
        </div>
      </>)}

      <button className='help-btn' onClick={openHelp}>Связаться с поддержкой</button>
    </section>}
    <div className='toasts'>
      {toasts.map((toast) => <div key={toast.id} className='toast' onClick={() => removeToast(toast.id)}>
        <span>{toast.text}</span>
        <button className='toast-close' onClick={(e) => { e.stopPropagation(); removeToast(toast.id) }}>✕</button>
      </div>)}
    </div>
  </div>
}

createRoot(document.getElementById('root')).render(<App />)
