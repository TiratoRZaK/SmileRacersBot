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
const REQUEST_TIMEOUT_MS = 15000
const WEB_AUTH_STORAGE_KEY = 'smile_racers_web_auth_v1'

const RACE_TYPE_LABELS = {
  REGULAR: 'Обычная',
  SPRINT: 'Спринт',
  MARATHON: 'Марафон'
}

const TRACK_THEMES = ['asphalt', 'grass', 'desert']

const TRACK_THEME_BACKGROUNDS = {
  asphalt: ['#394457', '#202736'],
  grass: ['#2f7d4f', '#1f5938'],
  desert: ['#c9a363', '#9d7040']
}
const TAB_ORDER = ['race', 'account', 'archive', 'battle']
const TAB_TITLES = {
  race: 'Активная гонка',
  account: 'Аккаунт',
  archive: 'Архив',
  battle: 'Батл'
}

const TOAST_AUTO_CLOSE_MS = 4500
const PERSISTENT_ACTIONS = new Set(['withdraw', 'withdraw/cancel', 'battle', 'battle/start', 'topup'])
const NOTIFICATION_ACTIONS = new Set(['notification/delete', 'notification/clear'])
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

const readTelegramContext = () => {
  const webApp = window.Telegram?.WebApp
  const user = webApp?.initDataUnsafe?.user || null
  const telegramUserId = Number(user?.id || 0)
  return {
    user,
    telegramUserId: Number.isFinite(telegramUserId) && telegramUserId > 0 ? telegramUserId : null,
    initData: webApp?.initData || ''
  }
}

const readStoredWebAuth = () => {
  try {
    const raw = window.localStorage.getItem(WEB_AUTH_STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (!parsed?.authToken || !parsed?.userId) return null
    return {
      authToken: String(parsed.authToken),
      userId: Number(parsed.userId),
      accountLabel: parsed.accountLabel ? String(parsed.accountLabel) : null
    }
  } catch {
    return null
  }
}

const saveStoredWebAuth = (value) => {
  if (!value?.authToken || !value?.userId) return
  window.localStorage.setItem(WEB_AUTH_STORAGE_KEY, JSON.stringify({
    authToken: String(value.authToken),
    userId: Number(value.userId),
    accountLabel: value.accountLabel ? String(value.accountLabel) : null
  }))
}

const clearStoredWebAuth = () => {
  window.localStorage.removeItem(WEB_AUTH_STORAGE_KEY)
}

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

const searchParams = new URLSearchParams(location.search)
const queryUserId = Number(searchParams.get('userId') || 0)
const queryBattleId = Number(searchParams.get('battleId') || 0)
const queryStartApp = String(searchParams.get('startapp') || searchParams.get('tgWebAppStartParam') || '')
const initStartParam = String(tg?.initDataUnsafe?.start_param || tg?.initDataUnsafe?.startapp || '')
const deepLinkParam = (initStartParam || queryStartApp || '').trim()
const deepLinkBattleMatch = deepLinkParam.match(/join_battle_(\d+)/i)
const deepLinkBattleId = deepLinkBattleMatch ? Number(deepLinkBattleMatch[1]) : (Number.isFinite(queryBattleId) && queryBattleId > 0 ? queryBattleId : null)


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
  const [telegramContext, setTelegramContext] = useState(() => readTelegramContext())
  const [webAuth, setWebAuth] = useState(() => readStoredWebAuth())
  const [webAuthBotUsername, setWebAuthBotUsername] = useState('')
  const [webAuthError, setWebAuthError] = useState('')
  const [isWebAuthLoading, setIsWebAuthLoading] = useState(false)
  const [webAuthWidgetReady, setWebAuthWidgetReady] = useState(false)
  const telegramWidgetRef = useRef(null)
  const userId = useMemo(() => {
    if (telegramContext.telegramUserId) return telegramContext.telegramUserId
    if (webAuth?.userId) return webAuth.userId
    return Number.isFinite(queryUserId) && queryUserId > 0 ? queryUserId : null
  }, [telegramContext.telegramUserId, webAuth?.userId])
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
  const [battleStakeInput, setBattleStakeInput] = useState(100)
  const [voteInputs, setVoteInputs] = useState({})
  const [topupAmount, setTopupAmount] = useState(100)
  const [withdrawAmount, setWithdrawAmount] = useState(100)
  const [favoriteIndex, setFavoriteIndex] = useState(0)
  const [joinBattleId, setJoinBattleId] = useState('')
  const [joinBattleEmoji, setJoinBattleEmoji] = useState('')
  const [localVotes, setLocalVotes] = useState({})
  const [isAppReady, setIsAppReady] = useState(false)
  const [bootProgress, setBootProgress] = useState(14)
  const [favoriteDirty, setFavoriteDirty] = useState(false)
  const [raceScale, setRaceScale] = useState(1)
  const [battleMode, setBattleMode] = useState('idle')
  const [boosterHint, setBoosterHint] = useState(null)
  const [sectionOpen, setSectionOpen] = useState({
    account: true,
    favorite: false,
    payments: false,
    history: false,
    recentRaces: true,
    battleCreate: true,
    battleManage: false,
    battleJoin: false
  })
  const refreshInFlightRef = useRef(false)
  const interactionPauseUntilRef = useRef(0)
  const firstLoadStartedAtRef = useRef(Date.now())
  const favoriteDirtyRef = useRef(false)
  const favoriteRequestRef = useRef(null)
  const swipeStartRef = useRef(null)
  const topZoneRef = useRef(null)

  const requestQuery = useMemo(() => {
    const params = new URLSearchParams()
    if (userId != null) params.set('userId', String(userId))
    if (telegramContext.initData) params.set('tgWebAppData', telegramContext.initData)
    if (webAuth?.authToken) params.set('authToken', webAuth.authToken)
    return params.toString()
  }, [telegramContext.initData, userId, webAuth?.authToken])
  const requestHeaders = useMemo(() => {
    const headers = {}
    if (telegramContext.telegramUserId) headers['X-Telegram-User-Id'] = String(telegramContext.telegramUserId)
    if (telegramContext.initData) headers['X-Telegram-Init-Data'] = telegramContext.initData
    if (webAuth?.authToken) headers['X-Web-Auth-Token'] = webAuth.authToken
    return headers
  }, [telegramContext.initData, telegramContext.telegramUserId, webAuth?.authToken])
  const hasMiniAppAuthContext = useMemo(
    () => Boolean(userId != null || telegramContext.initData || webAuth?.authToken),
    [telegramContext.initData, userId, webAuth?.authToken]
  )

  const parseResponsePayload = async (response) => {
    const text = await response.text()
    if (!text) return {}
    try {
      return JSON.parse(text)
    } catch {
      return {
        success: response.ok,
        message: text.slice(0, 220) || `HTTP ${response.status}`
      }
    }
  }

  const getErrorMessage = (payload, fallback) => {
    if (payload?.message) return payload.message
    return fallback
  }

  const requestApi = async (path, options = {}) => {
    const { method = 'GET', body, includeQuery = true, headers = {}, fallbackErrorMessage } = options
    if (!hasMiniAppAuthContext) {
      throw new Error('Не удалось определить авторизацию Telegram. Войдите через Telegram.')
    }
    const url = `${API}/${path}${includeQuery && requestQuery ? `?${requestQuery}` : ''}`
    const controller = new AbortController()
    const timeoutId = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)

    let response
    try {
      response = await fetch(url, {
        method,
        headers: {
          ...(body != null ? { 'Content-Type': 'application/json' } : {}),
          ...requestHeaders,
          ...headers
        },
        signal: controller.signal,
        ...(body != null ? { body: JSON.stringify(body) } : {})
      })
    } catch (error) {
      if (error?.name === 'AbortError') {
        throw new Error(`Сервер не ответил за ${Math.round(REQUEST_TIMEOUT_MS / 1000)} сек. Проверьте VPN/прокси или попробуйте позже.`)
      }
      throw new Error(fallbackErrorMessage || 'Не удалось связаться с сервером. Проверьте сеть и настройки прокси.')
    } finally {
      window.clearTimeout(timeoutId)
    }

    const payload = await parseResponsePayload(response)
    if (!response.ok) {
      throw new Error(getErrorMessage(payload, fallbackErrorMessage || `Ошибка запроса (${response.status}).`))
    }

    return payload
  }

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
      const [bootstrapData, withdrawData] = await Promise.all([
        requestApi('bootstrap', { fallbackErrorMessage: 'Не удалось загрузить состояние MiniApp.' }),
        requestApi('withdraw/active', { fallbackErrorMessage: 'Не удалось загрузить активные выводы.' })
      ])
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
      const historyData = await requestApi('history', { fallbackErrorMessage: 'Не удалось загрузить историю операций.' })
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
      const recentData = await requestApi('recent-results', { fallbackErrorMessage: 'Не удалось загрузить последние гонки.' })
      setRecentResults(recentData.items || [])
      setIsRecentResultsLoaded(true)
    } finally {
      setRecentResultsLoading(false)
    }
  }

  useEffect(() => {
    tg?.ready()
    tg?.expand?.()
    const syncTelegramContext = () => {
      setTelegramContext((current) => {
        const next = readTelegramContext()
        if (
          current.telegramUserId === next.telegramUserId &&
          current.initData === next.initData &&
          current.user?.id === next.user?.id
        ) {
          return current
        }
        return next
      })
    }

    syncTelegramContext()
    const pollId = window.setInterval(() => {
      const next = readTelegramContext()
      setTelegramContext((current) => {
        if (
          current.telegramUserId === next.telegramUserId &&
          current.initData === next.initData &&
          current.user?.id === next.user?.id
        ) {
          return current
        }
        return next
      })
      if (next.telegramUserId || next.initData) {
        window.clearInterval(pollId)
      }
    }, 400)

    if (readTelegramContext().telegramUserId || readTelegramContext().initData || readStoredWebAuth()?.authToken) {
      refresh().catch((error) => {
        setBootProgress(100)
        setIsAppReady(true)
        notify(error?.message || 'Не удалось загрузить данные MiniApp.', { persist: true })
      })
    } else {
      setBootProgress(100)
      setIsAppReady(true)
    }
    return () => window.clearInterval(pollId)
  }, [])

  useEffect(() => {
    if (telegramContext.initData || telegramContext.telegramUserId || webAuth?.authToken) {
      return undefined
    }
    let cancelled = false
    fetch(`${API}/auth/config`)
      .then(async (response) => {
        const payload = await parseResponsePayload(response)
        if (!response.ok) throw new Error(payload?.message || 'Не удалось загрузить конфигурацию авторизации.')
        if (!cancelled) setWebAuthBotUsername(payload?.botUsername || '')
      })
      .catch(() => {
        if (!cancelled) setWebAuthError('Не удалось загрузить Telegram Login. Проверьте бэкенд и имя бота.')
      })
    return () => {
      cancelled = true
    }
  }, [telegramContext.initData, telegramContext.telegramUserId, webAuth?.authToken])

  useEffect(() => {
    if (!webAuthBotUsername || telegramContext.initData || telegramContext.telegramUserId || webAuth?.authToken) {
      return undefined
    }
    const target = telegramWidgetRef.current
    if (!target) return undefined
    setWebAuthWidgetReady(false)
    target.innerHTML = ''
    const script = document.createElement('script')
    script.src = 'https://telegram.org/js/telegram-widget.js?22'
    script.async = true
    script.setAttribute('data-telegram-login', webAuthBotUsername)
    script.setAttribute('data-size', 'large')
    script.setAttribute('data-radius', '14')
    script.setAttribute('data-request-access', 'write')
    script.setAttribute('data-userpic', 'false')
    script.setAttribute('data-onauth', 'window.__onTelegramWebAuth(user)')
    script.onload = () => setWebAuthWidgetReady(true)
    target.appendChild(script)
    return () => {
      target.innerHTML = ''
    }
  }, [webAuthBotUsername, telegramContext.initData, telegramContext.telegramUserId, webAuth?.authToken])

  useEffect(() => {
    window.__onTelegramWebAuth = async (telegramUser) => {
      if (!telegramUser || isWebAuthLoading) return
      setWebAuthError('')
      setIsWebAuthLoading(true)
      try {
        const response = await fetch(`${API}/auth/telegram`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(telegramUser)
        })
        const payload = await parseResponsePayload(response)
        if (!response.ok || !payload?.success || !payload?.authToken || !payload?.userId) {
          throw new Error(payload?.message || 'Telegram авторизация не прошла.')
        }
        const nextAuth = {
          authToken: String(payload.authToken),
          userId: Number(payload.userId),
          accountLabel: payload.accountLabel || getTelegramAccountLabel(telegramUser)
        }
        setWebAuth(nextAuth)
        saveStoredWebAuth(nextAuth)
        await refresh(true)
      } catch (error) {
        setWebAuthError(error?.message || 'Telegram авторизация не прошла.')
      } finally {
        setIsWebAuthLoading(false)
      }
    }
    return () => {
      delete window.__onTelegramWebAuth
    }
  }, [isWebAuthLoading])

  useEffect(() => {
    if (isAppReady) return undefined

    const timer = window.setInterval(() => {
      setBootProgress((current) => Math.min(current + (current < 70 ? 11 : 4), 92))
    }, 140)

    return () => window.clearInterval(timer)
  }, [isAppReady])

  useEffect(() => {
    if (!hasMiniAppAuthContext) return undefined
    const timer = setInterval(() => {
      if (document.hidden) return
      if (Date.now() < interactionPauseUntilRef.current) return
      refresh(true).catch(() => null)
    }, POLL_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [hasMiniAppAuthContext, userId, tab])

  useEffect(() => {
    if (userId == null) return
    if (data != null) return
    refresh(true).catch(() => null)
  }, [userId, data])

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
    if (tab === 'archive' && sectionOpen.recentRaces) {
      loadRecentResults().catch(() => notify('Не удалось загрузить последние гонки.', { persist: true }))
    }
    if (tab === 'archive' && sectionOpen.history) {
      loadHistory().catch(() => notify('Не удалось загрузить историю операций.', { persist: true }))
    }
  }, [tab, sectionOpen.recentRaces, sectionOpen.history])


  useEffect(() => {
    if (!deepLinkBattleId) return
    setTab('battle')
    setJoinBattleId(String(deepLinkBattleId))
    setSectionOpen((current) => ({ ...current, battleCreate: false, battleManage: false, battleJoin: true }))
  }, [])

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

  useEffect(() => {
    const updateRaceScale = () => {
      const viewportHeight = window.innerHeight || 0
      const topZoneHeight = topZoneRef.current?.offsetHeight || 0
      const availableHeight = viewportHeight - topZoneHeight - 24
      const nextScale = Math.max(0.72, Math.min(1, availableHeight / 680))
      setRaceScale(nextScale)
    }

    updateRaceScale()
    window.addEventListener('resize', updateRaceScale)
    return () => window.removeEventListener('resize', updateRaceScale)
  }, [tab, isNotificationsOpen])

  const removeToast = (id) => {
    setToasts((current) => current.map((item) => item.id === id ? { ...item, closing: true } : item))
    setTimeout(() => {
      setToasts((current) => current.filter((item) => item.id !== id))
    }, 280)
  }

  const notify = (text, options = {}) => {
    const { persist = false, source = 'client' } = options
    if (!text) return
    const id = Date.now() + Math.floor(Math.random() * 10000)
    const notification = {
      id,
      text,
      persist,
      createdAt: new Date().toISOString(),
      read: false,
      source
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
    try {
      const r = await requestApi(path, {
        method: 'POST',
        body,
        fallbackErrorMessage: 'Операция не выполнена. Проверьте доступность сервера.'
      })

      if (path === 'vote' && body?.amount && body?.playerNumber) {
        const votedUnit = (data?.race?.units || []).find((u) => u.playerNumber === body.playerNumber)
        const target = votedUnit?.playerName || `участника #${body.playerNumber}`
        notify(`✅ Принято: ${formatStars(body.amount)} ⭐ за ${target}`)
      } else if (path === 'boost') {
        const toastId = notify(r.message)
        if (toastId != null) {
          setTimeout(() => removeToast(toastId), 250)
        }
      } else {
        notify(r.message, {
          persist: PERSISTENT_ACTIONS.has(path),
          source: 'client'
        })
      }

      if (r.invoiceLink) {
        tg?.openLink ? tg.openLink(r.invoiceLink) : window.open(r.invoiceLink, '_blank')
      }
      await refresh(true)
      if (path === 'vote') {
        setTimeout(() => {
          refresh(true).catch(() => null)
        }, 400)
      }
      return { ...r, httpOk: true }
    } catch (error) {
      notify(error.message || 'Операция не выполнена.', {
        persist: !NOTIFICATION_ACTIONS.has(path),
        source: 'client'
      })
      return { success: false, httpOk: false, message: error.message }
    }
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
    const targetBattle = (data?.race?.matchId === matchId && data?.race?.type === 'BATTLE') ? data.race : null
    const stake = Number(targetBattle?.battleStake || 0)
    if (!window.confirm(`Подтвердить вход в батл #${matchId}${stake > 0 ? ` за ${stake} ⭐` : ''}?`)) return
    const response = await act('battle/join', { matchId, playerName: joinBattleEmoji })
    if (response?.httpOk) {
      setBattleMode('joined')
      setJoinBattleId('')
    }
  }

  const cancelMyBattle = async () => {
    if (!myBattle?.matchId) return
    const response = await act('battle/cancel', { matchId: myBattle.matchId })
    if (response?.httpOk) {
      setBattleMode('idle')
    }
  }


  const leaveJoinedBattle = async () => {
    if (!joinedBattle?.matchId) return
    const response = await act('battle/leave', { matchId: joinedBattle.matchId })
    if (response?.httpOk) {
      setBattleMode('idle')
      setJoinBattleId('')
    }
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
    try {
      const payload = await requestApi('help', {
        includeQuery: false,
        fallbackErrorMessage: 'Не удалось получить ссылку на поддержку.'
      })
      const link = payload?.message
      if (link && /^https?:\/\//.test(link)) {
        tg?.openLink ? tg.openLink(link) : window.open(link, '_blank')
        return
      }
    } catch (error) {
      notify(error.message || 'Ссылка на поддержку временно недоступна.')
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
      if (!nextOpen) {
        return { ...current, [key]: false }
      }
      const groups = [
        ['account', 'favorite', 'payments'],
        ['recentRaces', 'history'],
        ['battleCreate', 'battleManage', 'battleJoin']
      ]
      const group = groups.find((items) => items.includes(key)) || [key]
      const updated = { ...current }
      group.forEach((item) => {
        updated[item] = item === key
      })
      return updated
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

    const notification = savedNotifications.find((item) => item.id === notificationId)
    setSavedNotifications((current) => current.filter((item) => item.id !== notificationId))

    if (!notification || notification.source !== 'server') {
      return
    }

    try {
      await requestApi('notification/delete', {
        method: 'POST',
        body: { notificationId },
        fallbackErrorMessage: 'Не удалось удалить уведомление.'
      })
    } catch (error) {
      if (error.message === 'Уведомление не найдено.') {
        return
      }
      setSavedNotifications((current) => [notification, ...current].slice(0, MAX_SAVED_NOTIFICATIONS))
    }
  }

  const clearSavedNotifications = async () => {
    const serverNotifications = savedNotifications.filter((item) => item.source === 'server')
    setSavedNotifications([])

    if (!serverNotifications.length) {
      return
    }

    try {
      await requestApi('notification/clear', {
        method: 'POST',
        fallbackErrorMessage: 'Не удалось очистить уведомления.'
      })
    } catch (error) {
      setSavedNotifications((current) => [...serverNotifications, ...current].slice(0, MAX_SAVED_NOTIFICATIONS))
    }
  }

  const visibleRace = data?.race && !(data.race.type === 'BATTLE' && data.race.status !== 'LIVE') ? data.race : null
  const raceBeforeStart = visibleRace?.status === 'CREATED'
  const raceLive = visibleRace?.status === 'LIVE'
  const raceCompleted = visibleRace?.status === 'COMPLETED'
  const myBattle = data?.myBattle || null
  const isMyBattleOwner = !!myBattle && Number((myBattle.units || []).find((unit) => unit.playerNumber === 1)?.ownerUserId || 0) === Number(userId || 0)
  const myBattleCanStart = !!myBattle && myBattle.status === 'CREATED' && !myBattle.battleStartRequested
  const myBattleCanInvite = !!myBattle?.inviteLink
  const myBattleIsLive = myBattle?.status === 'LIVE'
  const boostersDisabled = !visibleRace || raceBeforeStart
  const trackTheme = getTrackTheme(visibleRace)
  const raceUnits = visibleRace?.units || []
  const maxScore = raceUnits.reduce((max, unit) => Math.max(max, Number(unit.score) || 0), 0)
  const finishScore = Math.max(Number(data?.race?.trackLength) || DEFAULT_TRACK_LENGTH, maxScore, 1)
  const trackBackgroundStyle = useMemo(() => ({
    backgroundImage: buildTrackBackgroundImage(trackTheme, raceUnits, visibleRace?.matchId || 1),
    backgroundRepeat: 'no-repeat',
    backgroundSize: 'cover',
    backgroundPosition: 'center'
  }), [trackTheme, raceUnits, visibleRace?.matchId])
  const unreadCount = savedNotifications.filter((item) => !item.read).length
  const myBattleCanCancel = !!myBattle && myBattle.status === 'CREATED'
  const canCreateBattle = !myBattle && battleMode !== 'joined'
  const canManageBattle = !!myBattle && isMyBattleOwner
  const canJoinBattle = !myBattle || (!!myBattle && !isMyBattleOwner)
  const joinedBattle = !!myBattle && !isMyBattleOwner ? myBattle : null
  const joinedBattleIsLive = joinedBattle?.status === 'LIVE'
  const canLeaveJoinedBattle = !!joinedBattle && !joinedBattleIsLive

  useEffect(() => {
    if (myBattle && isMyBattleOwner) {
      setBattleMode('owner')
      setSectionOpen((current) => ({ ...current, battleCreate: false, battleManage: true, battleJoin: false }))
      return
    }
    if (myBattle && !isMyBattleOwner) {
      setBattleMode('joined')
      return
    }
    if (battleMode === 'owner' || battleMode === 'joined') {
      setBattleMode('idle')
    }
  }, [myBattle, joinedBattle, isMyBattleOwner])
  const raceWinner = raceCompleted ? raceUnits.find((unit) => unit.place === 1) : null
  const racePayout = Number(visibleRace?.myPayout || 0)
  const raceResultTitle = racePayout > 0 ? `Вы выиграли ${formatStars(racePayout)} ⭐` : racePayout < 0 ? `Вы проиграли ${formatStars(Math.abs(racePayout))} ⭐` : 'Эта гонка без изменения баланса'

  const goToTabBySwipe = (direction) => {
    const currentIndex = TAB_ORDER.indexOf(tab)
    if (currentIndex < 0) return
    const nextIndex = currentIndex + direction
    if (nextIndex < 0 || nextIndex >= TAB_ORDER.length) return
    setTab(TAB_ORDER[nextIndex])
  }

  const onSwipeStart = (event) => {
    const touch = event.touches?.[0]
    if (!touch) return
    swipeStartRef.current = { x: touch.clientX, y: touch.clientY, at: Date.now() }
  }

  const onSwipeEnd = (event) => {
    const start = swipeStartRef.current
    swipeStartRef.current = null
    if (!start) return
    const touch = event.changedTouches?.[0]
    if (!touch) return
    const deltaX = touch.clientX - start.x
    const deltaY = touch.clientY - start.y
    const duration = Date.now() - start.at
    if (Math.abs(deltaX) < 50 || Math.abs(deltaX) < Math.abs(deltaY) || duration > 650) return
    goToTabBySwipe(deltaX < 0 ? 1 : -1)
  }

  if (!hasMiniAppAuthContext) return <div className='loading-screen'>
    <div className='loading-orb loading-orb-left' />
    <div className='loading-orb loading-orb-right' />
    <div className='loading-card auth-card'>
      <div className='loading-logo'>🔐</div>
      <h1>Вход через Telegram</h1>
      <p>Откройте Mini App в Telegram или войдите на этой странице через Telegram Login.</p>
      <div className='telegram-widget-slot' ref={telegramWidgetRef} />
      {!webAuthWidgetReady && !!webAuthBotUsername && <p className='subtitle'>Загружаем Telegram Login…</p>}
      {isWebAuthLoading && <p className='subtitle'>Проверяем Telegram-подпись…</p>}
      {!!webAuthError && <p className='auth-error'>{webAuthError}</p>}
      {!!webAuth?.authToken && <button
        className='chip danger-chip'
        onClick={() => {
          clearStoredWebAuth()
          setWebAuth(null)
          setWebAuthError('')
        }}
      >
        Сбросить web-сессию
      </button>}
    </div>
  </div>

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
    <div className='top-zone' ref={topZoneRef}>
      <div className='sticky-header-shell'>
      <header className='top-card'>
        <div className='top-card-main'>
          <div className='top-card-stat'>
            <span className='label'>Баланс</span>
            <b>{formatStars(data.balance)} ⭐</b>
          </div>
          <div className='top-card-stat'>
            <span className='label'>Бустеры</span>
            <b>{formatStars(data.freeBoosters)}</b>
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
        {TAB_ORDER.map((tabKey) => <button key={tabKey} className={tab === tabKey ? 'active' : ''} onClick={() => setTab(tabKey)}>{TAB_TITLES[tabKey]}</button>)}
      </nav>
      </div>

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

    <main className='content-zone' onTouchStart={onSwipeStart} onTouchEnd={onSwipeEnd}>
    {tab === 'race' && <section className={`panel tab-panel race-panel race-theme-${trackTheme} ${raceBeforeStart ? 'race-before-start' : ''}`}>
      <div className='race-scale-shell'>
      <div className='race-scale-content' style={{ transform: `scale(${raceScale})`, width: `${100 / raceScale}%` }}>
      <h2>{visibleRace ? `Гонка #${visibleRace.matchId} · ${getRaceTypeLabel(visibleRace.type)}` : 'Нет активной гонки'}</h2>
      {!visibleRace && <p className='subtitle'>Скоро начнётся новая гонка или создайте батл (вкладка «Батл»).</p>}

      {!!visibleRace && raceLive && <div className='booster-legend'>
        <button type='button' onClick={() => setBoosterHint('BUST')}><b>🐇</b> ускорить · 10⭐</button>
        <button type='button' onClick={() => setBoosterHint('SLOW')}><b>🐢</b> замедлить · 10⭐</button>
        <button type='button' onClick={() => setBoosterHint('SHIELD')}><b>🪖</b> защитить · 40⭐</button>
      </div>}
      {boosterHint && <div className='booster-hint-popover'>
        <div>
          {boosterHint === 'BUST' && <p><b>🐇 Ускорение:</b> выбранный смайл получает временный буст скорости и быстрее продвигается к финишу.</p>}
          {boosterHint === 'SLOW' && <p><b>🐢 Замедление:</b> выбранный смайл теряет темп на короткое время, что снижает его шанс на победу.</p>}
          {boosterHint === 'SHIELD' && <p><b>🪖 Защита:</b> даёт 5 щитов. Каждый щит блокирует одно замедление 🐢.</p>}
        </div>
        <button className='chip' onClick={() => setBoosterHint(null)}>Понятно</button>
      </div>}
      {!!visibleRace && <div className={`track track-${trackTheme}`} style={trackBackgroundStyle}>
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
              const response = await act('vote', { matchId: visibleRace.matchId, playerNumber: u.playerNumber, amount })
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
        {raceLive && <div className='booster-shell'>
          <div className='booster-actions'>
            <button className='booster booster-bust' aria-label={`Ускорить ${u.playerName}`} title={`Ускорить ${u.playerName}`} disabled={boostersDisabled} onClick={async () => { await act('boost', { playerNumber: u.playerNumber, type: 'BUST' }) }}><span>🐇</span></button>
            <button className='booster booster-slow' aria-label={`Замедлить ${u.playerName}`} title={`Замедлить ${u.playerName}`} disabled={boostersDisabled} onClick={async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SLOW' }) }}><span>🐢</span></button>
            <button className='booster booster-shield' aria-label={`Защитить ${u.playerName}`} title={`Защитить ${u.playerName}`} disabled={boostersDisabled} onClick={async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SHIELD' }) }}><span>🪖</span></button>
          </div>
        </div>}
      </div>
      })}
      </div>}

      {raceCompleted && <div className='finish-celebration'>
        <h3>Гонка завершена</h3>
        <p className='subtitle'>Победитель: {raceWinner?.playerName || '—'}</p>
        <p className='winner-name'>{raceResultTitle}</p>
      </div>}


      </div>
      </div>
    </section>}

    {tab === 'account' && <section className='panel tab-panel account-panel'>
      {renderSection('account', 'Данные об аккаунте', <>
        <p className='subtitle'>
          {getTelegramAccountLabel(telegramContext.user)}
          {telegramContext.user?.id ? ` · ID ${telegramContext.user.id}` : ''}
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

      <button className='help-btn' onClick={openHelp}>Связаться с поддержкой</button>
    </section>}

    {tab === 'archive' && <section className='panel tab-panel account-panel'>
      {renderSection('recentRaces', 'История гонок', <>
        {recentResultsLoading && <p className='subtitle'>Загружаем последние гонки…</p>}
        {isRecentResultsLoaded && !recentResults.length && <p className='subtitle'>Пока нет завершённых гонок.</p>}
        <div className='archive-results-list'>
          {recentResults.map((result) => <div key={result.matchId} className='recent-result-card'>
            <div className='recent-result-title'>#{result.matchId} · {getRaceTypeLabel(result.type)}</div>
            <div className='subtitle'>Победитель: {result.winnerName || '—'}</div>
            <div className='subtitle'>Участники: {(result.units || []).map((u) => u.playerName).join(' · ')}</div>
          </div>)}
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
    </section>}

    {tab === 'battle' && <section className='panel tab-panel account-panel'>
      <div className='battle-section'>
        <h3>Батлы</h3>
        {renderSection('battleCreate', 'Создание нового батла', <>
          {!canCreateBattle && <p className='subtitle'>Создание недоступно, пока активен ваш или уже подключённый батл.</p>}
          {canCreateBattle && <>
            <p className='subtitle'>Создайте приватный батл, выберите смайл и стоимость входа. Все участники платят одинаковую ставку, победитель забирает банк.</p>
            <div className='row battle-create-row'>
              <select value={battleEmoji} onChange={(e) => setBattleEmoji(e.target.value)}>{data.allEmojis.map((e) => <option key={e}>{e}</option>)}</select>
              <input
                className='field'
                type='number'
                min='1'
                step='1'
                value={battleStakeInput}
                onChange={(e) => setBattleStakeInput(Math.max(1, Number(e.target.value || 1)))}
              />
              <button onClick={async () => {
                const response = await act('battle', { playerName: battleEmoji, stake: Math.max(1, Number(battleStakeInput) || 1) })
                if (response?.httpOk) {
                  setBattleMode('owner')
                  setSectionOpen((current) => ({ ...current, battleCreate: false, battleManage: true, battleJoin: false }))
                }
              }}>Создать батл</button>
            </div>
          </>}
        </>)}

        {renderSection('battleManage', 'Управление моим батлом', <>
          {!canManageBattle && <p className='subtitle'>Секция доступна только после создания вашего батла.</p>}
          {canManageBattle && <div className='battle-manage-card'>
            <div className='battle-stats-grid'>
              <div className='battle-stat'><span>Батл</span><strong>#{myBattle.matchId}</strong></div>
              <div className='battle-stat'><span>Ставка</span><strong>{myBattle.battleStake || 0} ⭐</strong></div>
              <div className='battle-stat'><span>Банк</span><strong>{getBattleBank(myBattle)} ⭐</strong></div>
            </div>
            <p className='subtitle battle-state-line'>{getBattleStateLabel(myBattle)}</p>
            <div className='battle-participants-list'>
              {(myBattle.units || []).map((u) => <div key={u.playerNumber} className='battle-participant-row'>
                <span>{getBattleParticipantLabel(u)}</span>
                {Number(u.ownerUserId) !== Number(userId) && <button className='chip danger-chip' disabled={myBattleIsLive} onClick={() => removeBattleParticipant(u.playerNumber)}>Исключить</button>}
              </div>)}
            </div>
            <div className='row battle-action-row'>
              {myBattleCanInvite && <button onClick={() => openBattleInvite(myBattle)}>Пригласить друзей</button>}
              {myBattleCanStart && <button onClick={requestBattleStartFromUi}>Поставить в очередь</button>}
              {myBattleCanCancel && <button className='chip danger-chip' onClick={cancelMyBattle}>Отменить батл</button>}
            </div>
          </div>}
        </>)}

        {renderSection('battleJoin', 'Подключение к батлу', <>
          {!canJoinBattle && <p className='subtitle'>Подключение недоступно, пока у вас активен собственный батл.</p>}
          {battleMode === 'joined' && joinedBattle && <div className='battle-manage-card'>
            <div className='battle-stats-grid'>
              <div className='battle-stat'><span>Батл</span><strong>#{joinedBattle.matchId}</strong></div>
              <div className='battle-stat'><span>Ставка</span><strong>{joinedBattle.battleStake || 0} ⭐</strong></div>
              <div className='battle-stat'><span>Банк</span><strong>{getBattleBank(joinedBattle)} ⭐</strong></div>
            </div>
            <p className='subtitle battle-state-line'>{getBattleStateLabel(joinedBattle)}</p>
            <div className='battle-participants-list'>
              {(joinedBattle.units || []).map((u) => <div key={u.playerNumber} className='battle-participant-row'>
                <span>{getBattleParticipantLabel(u)}</span>
              </div>)}
            </div>
            <div className='row battle-action-row battle-action-row-single'>
              <button className='chip danger-chip' disabled={!canLeaveJoinedBattle} onClick={leaveJoinedBattle}>Сбежать (вернуть {joinedBattle.battleStake || 0} ⭐)</button>
            </div>
          </div>}
          {canJoinBattle && battleMode !== 'joined' && <div className='row battle-join-row'>
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
            <button onClick={joinBattleFromUi}>Присоединиться</button>
          </div>}
        </>)}
      </div>
    </section>}
    </main>
    <div className='toasts'>
      {toasts.map((toast) => <div key={toast.id} className={`toast ${toast.closing ? 'is-closing' : ''}`} onClick={() => removeToast(toast.id)}>
        <span>{toast.text}</span>
        <button className='toast-close' onClick={(e) => { e.stopPropagation(); removeToast(toast.id) }}>✕</button>
      </div>)}
    </div>
  </div>
}

createRoot(document.getElementById('root')).render(<App />)
