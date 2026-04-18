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
const BOOTSTRAP_EXTRAS_REFRESH_MS = 15000
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
const TAB_ORDER = ['race', 'battle', 'ratings', 'account', 'archive']
const TAB_TITLES = {
  race: 'Гонка',
  battle: 'Батлы',
  ratings: 'Рейтинги',
  account: 'Аккаунт',
  archive: 'Архив'
}
const UNKNOWN_AVATAR = '❔'

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

const getTrackTheme = (race) => {
  if (!race) return TRACK_THEMES[0]
  const seed = Number(race.matchId || 0)
  return TRACK_THEMES[Math.abs(seed) % TRACK_THEMES.length]
}

const searchParams = new URLSearchParams(location.search)
const queryUserId = Number(searchParams.get('userId') || 0)
const queryBattleId = Number(searchParams.get('battleId') || 0)
const queryStartApp = String(searchParams.get('startapp') || searchParams.get('tgWebAppStartParam') || '')
const initStartParam = ''
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

const parseRubyInput = (rawValue) => {
  const digits = String(rawValue ?? '').replace(/\D/g, '')
  return Number(digits || 0)
}

const RubyAmountField = ({
  value,
  min = 1,
  max = Number.MAX_SAFE_INTEGER,
  clampOnBlur = true,
  onChange,
  onFocus,
  placeholder
}) => {
  const safeMin = Math.max(1, Number.isFinite(min) ? Math.floor(min) : 1)
  const safeMax = Math.max(safeMin, Number.isFinite(max) ? Math.floor(max) : safeMin)
  const clampValue = (nextValue) => Math.max(safeMin, Math.min(nextValue, safeMax))
  const normalizedValue = clampValue(Number(value) || safeMin)
  const [draftValue, setDraftValue] = useState(String(normalizedValue))

  useEffect(() => {
    setDraftValue(String(normalizedValue))
  }, [normalizedValue])

  return <label className='ruby-input-wrap'>
    <input
      className='field ruby-input'
      type='text'
      inputMode='numeric'
      placeholder={placeholder}
      value={draftValue}
      onFocus={onFocus}
      onChange={(e) => {
        const rawValue = String(e.target.value ?? '')
        const digits = rawValue.replace(/\D/g, '')
        setDraftValue(digits)
      }}
      onBlur={() => {
        const parsed = parseRubyInput(draftValue)
        const fallbackValue = clampOnBlur ? safeMin : 0
        const next = clampOnBlur ? clampValue(parsed || safeMin) : Math.max(fallbackValue, parsed || 0)
        onChange(next)
        setDraftValue(String(next))
      }}
    />
    <span className='ruby-input-icon'>💎</span>
  </label>
}

function App() {
  const [webAuth, setWebAuth] = useState(() => readStoredWebAuth())
  const [webAuthError, setWebAuthError] = useState('')
  const [isWebAuthLoading, setIsWebAuthLoading] = useState(false)
  const [authMode, setAuthMode] = useState('login')
  const [authUsername, setAuthUsername] = useState('')
  const [authPassword, setAuthPassword] = useState('')
  const [authPasswordConfirm, setAuthPasswordConfirm] = useState('')
  const userId = useMemo(() => {
    if (webAuth?.userId) return webAuth.userId
    return Number.isFinite(queryUserId) && queryUserId > 0 ? queryUserId : null
  }, [webAuth?.userId])
  const [data, setData] = useState(null)
  const [activeWithdraws, setActiveWithdraws] = useState([])
  const [historyItems, setHistoryItems] = useState([])
  const [recentResults, setRecentResults] = useState([])
  const [isHistoryLoaded, setIsHistoryLoaded] = useState(false)
  const [isRecentResultsLoaded, setIsRecentResultsLoaded] = useState(false)
  const [historyLoading, setHistoryLoading] = useState(false)
  const [recentResultsLoading, setRecentResultsLoading] = useState(false)
  const [leaderboards, setLeaderboards] = useState(null)
  const [leaderboardsLoading, setLeaderboardsLoading] = useState(false)
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
  const [bootError, setBootError] = useState('')
  const [raceScale, setRaceScale] = useState(1)
  const [battleMode, setBattleMode] = useState('idle')
  const [boosterHint, setBoosterHint] = useState(null)
  const [balancePulse, setBalancePulse] = useState(false)
  const [boosterPulse, setBoosterPulse] = useState(false)
  const [balanceFloatingGain, setBalanceFloatingGain] = useState(null)
  const [adminWithdraws, setAdminWithdraws] = useState([])
  const [adminUsername, setAdminUsername] = useState('')
  const [adminAmount, setAdminAmount] = useState(100)
  const [adminUserOptions, setAdminUserOptions] = useState([])
  const [sectionOpen, setSectionOpen] = useState({
    ratingsEmojis: false,
    ratingsPlayersAll: false,
    ratingsPlayersWeekly: true,
    account: true,
    favorite: false,
    payments: false,
    history: false,
    recentRaces: true,
    battleCreate: true,
    battleManage: false,
    battleJoin: false,
    adminWithdraws: false,
    adminBalance: false
  })
  const refreshInFlightRef = useRef(false)
  const bootstrapExtrasInFlightRef = useRef(false)
  const bootstrapExtrasLastLoadedAtRef = useRef(0)
  const interactionPauseUntilRef = useRef(0)
  const firstLoadStartedAtRef = useRef(Date.now())
  const favoriteDirtyRef = useRef(false)
  const favoriteRequestRef = useRef(null)
  const swipeStartRef = useRef(null)
  const topZoneRef = useRef(null)
  const previousTabRef = useRef(tab)
  const resourcesPrevRef = useRef({
    balance: null,
    freeBoosters: null
  })
  const authLogoText = 'EMOJI RACE'

  const requestQuery = useMemo(() => {
    const params = new URLSearchParams()
    if (userId != null) params.set('userId', String(userId))
    if (webAuth?.authToken) params.set('authToken', webAuth.authToken)
    return params.toString()
  }, [userId, webAuth?.authToken])
  const requestHeaders = useMemo(() => {
    const headers = {}
    if (webAuth?.authToken) headers['X-Web-Auth-Token'] = webAuth.authToken
    return headers
  }, [webAuth?.authToken])
  const hasMiniAppAuthContext = useMemo(
    () => Boolean(userId != null && webAuth?.authToken),
    [userId, webAuth?.authToken]
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
      throw new Error('Вы не авторизованы. Войдите по логину и паролю.')
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
      const error = new Error(getErrorMessage(payload, fallbackErrorMessage || `Ошибка запроса (${response.status}).`))
      error.status = response.status
      throw error
    }

    return payload
  }

  const isIdentityResolutionError = (error) => {
    const message = String(error?.message || '').toLowerCase()
    return Number(error?.status) === 401 && (
      message.includes('не удалось определить user id') ||
      message.includes('выполните вход')
    )
  }

  const resetWebAuthSession = () => {
    clearStoredWebAuth()
    setWebAuth(null)
  }

  useEffect(() => {
    favoriteDirtyRef.current = favoriteDirty
  }, [favoriteDirty])

  useEffect(() => {
    if (!data) return
    const previous = resourcesPrevRef.current
    const nextBalance = Number(data.balance) || 0
    const nextBoosters = Number(data.freeBoosters) || 0

    if (previous.balance != null && previous.balance !== nextBalance) {
      setBalancePulse(true)
      window.setTimeout(() => setBalancePulse(false), 520)
      const gain = nextBalance - previous.balance
      if (gain > 0) {
        const floatingId = Date.now()
        setBalanceFloatingGain({ id: floatingId, text: `+${formatStars(gain)} 💎` })
        window.setTimeout(() => {
          setBalanceFloatingGain((current) => (current?.id === floatingId ? null : current))
        }, 1450)
      }
    }

    if (previous.freeBoosters != null && previous.freeBoosters !== nextBoosters) {
      setBoosterPulse(true)
      window.setTimeout(() => setBoosterPulse(false), 420)
    }

    resourcesPrevRef.current = {
      balance: nextBalance,
      freeBoosters: nextBoosters
    }
  }, [data?.balance, data?.freeBoosters, data])

  const pausePollingForInteraction = () => {
    interactionPauseUntilRef.current = Date.now() + INTERACTION_PAUSE_MS
  }

  const withBootstrapDefaults = (next, previous = null) => ({
    ...next,
    allEmojis: Array.isArray(next?.allEmojis)
      ? next.allEmojis
      : (Array.isArray(previous?.allEmojis) ? previous.allEmojis : []),
    notifications: Array.isArray(next?.notifications)
      ? next.notifications
      : (Array.isArray(previous?.notifications) ? previous.notifications : []),
    adminUsernames: Array.isArray(next?.adminUsernames)
      ? next.adminUsernames
      : (Array.isArray(previous?.adminUsernames) ? previous.adminUsernames : [])
  })

  const loadBootstrapExtras = async ({ force = false } = {}) => {
    const now = Date.now()
    if (bootstrapExtrasInFlightRef.current) return
    if (!force && now - bootstrapExtrasLastLoadedAtRef.current < BOOTSTRAP_EXTRAS_REFRESH_MS) return

    bootstrapExtrasInFlightRef.current = true
    try {
      const extras = await requestApi('bootstrap/extras', {
        fallbackErrorMessage: 'Не удалось загрузить дополнительные данные MiniApp.'
      })
      setData((current) => current ? withBootstrapDefaults({ ...current, ...extras }, current) : current)
      bootstrapExtrasLastLoadedAtRef.current = Date.now()
    } finally {
      bootstrapExtrasInFlightRef.current = false
    }
  }

  const refresh = async (silent = false) => {
    if (refreshInFlightRef.current) return
    refreshInFlightRef.current = true

    try {
      const [bootstrapData, withdrawData] = await Promise.all([
        requestApi('bootstrap', { fallbackErrorMessage: 'Не удалось загрузить состояние MiniApp.' }),
        requestApi('withdraw/active', { fallbackErrorMessage: 'Не удалось загрузить активные выводы.' })
      ])
      setData((current) => withBootstrapDefaults(bootstrapData, current))
      setActiveWithdraws(withdrawData.items || [])
      setBootError('')

      const shouldForceExtras = !silent || !bootstrapExtrasLastLoadedAtRef.current
      loadBootstrapExtras({ force: shouldForceExtras }).catch(() => null)

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

  const loadLeaderboards = async () => {
    if (leaderboardsLoading || leaderboards != null) return
    setLeaderboardsLoading(true)
    try {
      const payload = await requestApi('leaderboards', { fallbackErrorMessage: 'Не удалось загрузить рейтинги.' })
      setLeaderboards(payload)
    } finally {
      setLeaderboardsLoading(false)
    }
  }

  const loadAdminWithdraws = async () => {
    if (!data?.isAdmin) return
    const payload = await requestApi('admin/withdraws', { fallbackErrorMessage: 'Не удалось загрузить админ-выводы.' })
    setAdminWithdraws(payload.items || [])
  }

  const adminWithdrawAction = async (requestId, action) => {
    const endpoint = action === 'pay' ? 'admin/withdraw/pay' : 'admin/withdraw/cancel'
    const result = await requestApi(endpoint, {
      method: 'POST',
      body: { requestId },
      fallbackErrorMessage: 'Не удалось выполнить действие по выводу.'
    })
    notify(result?.message || 'Операция выполнена.')
    await Promise.all([refresh(true), loadAdminWithdraws()])
  }

  const adminAdjustBalance = async (mode) => {
    const username = String(adminUsername || '').trim().replace(/^@/, '')
    const amount = Number(adminAmount || 0)
    if (!username || !Number.isFinite(amount) || amount <= 0) {
      notify('Введите корректный логин и сумму > 0', { persist: true })
      return
    }
    const endpoint = mode === 'add' ? 'admin/balance/add' : 'admin/balance/subtract'
    const result = await requestApi(endpoint, {
      method: 'POST',
      body: { username, amount: Math.round(amount) },
      fallbackErrorMessage: 'Не удалось изменить баланс.'
    })
    notify(result?.message || 'Баланс обновлён.')
    await refresh(true)
  }

  useEffect(() => {
    if (readStoredWebAuth()?.authToken) {
      refresh().catch((error) => {
        if (isIdentityResolutionError(error)) {
          resetWebAuthSession()
          setBootError('')
          setData(null)
          setBootProgress(100)
          setIsAppReady(true)
          notify('Сессия недействительна. Выполните вход заново.', { persist: true })
          return
        }
        const errorMessage = error?.message || 'Не удалось загрузить данные MiniApp.'
        setBootError(errorMessage)
        setBootProgress(100)
        setIsAppReady(true)
        notify(errorMessage, { persist: true })
      })
    } else {
      setBootProgress(100)
      setIsAppReady(true)
    }
  }, [])

  const submitAuth = async (mode) => {
    if (isWebAuthLoading) return
    const username = String(authUsername || '').trim().replace(/^@/, '')
    const password = String(authPassword || '')
    if (!username || !password) {
      setWebAuthError('Введите логин и пароль.')
      return
    }
    if (mode !== 'login' && password !== authPasswordConfirm) {
      setWebAuthError('Пароли не совпадают.')
      return
    }

    setWebAuthError('')
    setIsWebAuthLoading(true)
    try {
      const response = await fetch(`${API}/auth/${mode === 'register' ? 'register' : mode === 'setup' ? 'password/setup' : 'login'}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password })
      })
      const payload = await parseResponsePayload(response)
      if (!response.ok || !payload?.success || !payload?.authToken || !payload?.userId) {
        throw new Error(payload?.message || 'Авторизация не прошла.')
      }
      const nextAuth = {
        authToken: String(payload.authToken),
        userId: Number(payload.userId),
        accountLabel: payload.accountLabel || username
      }
      setWebAuth(nextAuth)
      saveStoredWebAuth(nextAuth)
      setAuthPassword('')
      setAuthPasswordConfirm('')
      await refresh(true)
    } catch (error) {
      const message = error?.message || 'Авторизация не прошла.'
      setWebAuthError(message)
      if (mode === 'login' && message.toLowerCase().includes('пароль ещё не задан')) {
        setAuthMode('setup')
      }
    } finally {
      setIsWebAuthLoading(false)
    }
  }

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
    if (tab !== 'account' || !data?.isAdmin) return
    loadAdminWithdraws().catch(() => null)
  }, [tab, data?.isAdmin])

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
    if (!isNotificationsOpen || savedNotifications.length) return
    setIsNotificationsOpen(false)
  }, [isNotificationsOpen, savedNotifications.length])

  useEffect(() => {
    if (!data?.isAdmin) {
      setAdminUserOptions([])
      return
    }
    setAdminUserOptions(Array.isArray(data?.adminUsernames) ? data.adminUsernames : [])
  }, [data?.isAdmin, data?.adminUsernames])

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
    if (tab === 'ratings') {
      loadLeaderboards().catch(() => notify('Не удалось загрузить рейтинги.', { persist: true }))
    }
    if (tab === 'archive' && sectionOpen.recentRaces) {
      loadRecentResults().catch(() => notify('Не удалось загрузить последние гонки.', { persist: true }))
    }
    if (tab === 'archive' && sectionOpen.history) {
      loadHistory().catch(() => notify('Не удалось загрузить историю операций.', { persist: true }))
    }
  }, [tab, sectionOpen.recentRaces, sectionOpen.history])

  useEffect(() => {
    if (!hasMiniAppAuthContext || leaderboards != null) return
    loadLeaderboards().catch(() => null)
  }, [hasMiniAppAuthContext, leaderboards])


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
        notify(`✅ Принято: ${formatStars(body.amount)} 💎 за ${target}`)
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

  const maxWithdraw = Math.max(0, Number(data?.balance) || 0)


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
    if (!window.confirm(`Подтвердить вход в батл #${matchId}${stake > 0 ? ` за ${stake} 💎` : ''}?`)) return
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

  const requestWithdrawFromUi = async () => {
    const amount = Math.round(Number(withdrawAmount || 0))
    const isOutOfRange = !Number.isFinite(amount) || amount < WITHDRAW_MIN || amount > maxWithdraw
    if (isOutOfRange) {
      notify(`Сумма вывода должна быть от ${WITHDRAW_MIN} до ${formatStars(maxWithdraw)} 💎.`, { persist: true })
      return
    }
    await act('withdraw', { amount })
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
      const updated = { ...current }
      Object.keys(updated).forEach((item) => {
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

  const formatPeriodDate = (value) => {
    if (!value) return '—'
    const date = new Date(value)
    return date.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric' })
  }

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
  const raceParticipationActive = raceLive && raceUnits.some((unit) => (Number(unit.myVotes) || 0) > 0)
  const battleAttentionActive = !!myBattle && myBattle.status === 'CREATED'
  const weeklyTopPlace = useMemo(() => {
    if (!leaderboards?.playerWinnersWeekly?.length || !userId) return null
    const index = leaderboards.playerWinnersWeekly.findIndex((item) => Number(item.userId || 0) === Number(userId))
    if (index < 0 || index > 9) return null
    return index + 1
  }, [leaderboards?.playerWinnersWeekly, userId])
  const favoriteEmojiPlace = useMemo(() => {
    if (!leaderboards?.emojiWinners?.length || !data?.favoriteEmoji) return null
    const index = leaderboards.emojiWinners.findIndex((item) => item.emoji === data.favoriteEmoji)
    return index >= 0 ? index + 1 : null
  }, [leaderboards?.emojiWinners, data?.favoriteEmoji])
  const accountAvatar = data?.favoriteEmoji || UNKNOWN_AVATAR
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
  const raceResultTitle = racePayout > 0 ? `Вы выиграли ${formatStars(racePayout)} 💎` : racePayout < 0 ? `Вы проиграли ${formatStars(Math.abs(racePayout))} 💎` : 'Эта гонка без изменения баланса'

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

  useEffect(() => {
    if (previousTabRef.current === tab) return
    previousTabRef.current = tab
    const defaultsByTab = {
      ratings: ['ratingsPlayersWeekly', 'ratingsEmojis', 'ratingsPlayersAll'],
      account: ['account', 'favorite', 'payments'],
      archive: ['recentRaces', 'history'],
      battle: ['battleCreate', 'battleManage', 'battleJoin']
    }
    const keys = defaultsByTab[tab]
    if (!keys) return
    setSectionOpen((current) => {
      if (keys.some((key) => current[key])) return current
      return { ...current, [keys[0]]: true }
    })
  }, [tab])

  if (!hasMiniAppAuthContext) return <div className='loading-screen'>
    <div className='loading-orb loading-orb-left' />
    <div className='loading-orb loading-orb-right' />
    <form
      className='loading-card auth-card'
      onSubmit={(event) => {
        event.preventDefault()
        submitAuth(authMode)
      }}
    >
      <div className='loading-logo'>🔐</div>
      <h1>{authMode === 'register' ? 'Регистрация' : authMode === 'setup' ? 'Установка пароля' : 'Вход'}</h1>
      <p>Введите логин и пароль для входа в веб-версию.</p>
      <div className='auth-brand'>
        <div className='auth-brand-logo'>
          <span>{authLogoText}</span>
        </div>
        <div>
          <strong>Smile Racers</strong>
          <p>Гонки смайлов с динамичными батлами.</p>
        </div>
      </div>
      <input className='field' placeholder='Логин' value={authUsername} onChange={(e) => setAuthUsername(e.target.value)} />
      <input className='field' placeholder='Пароль' type='password' value={authPassword} onChange={(e) => setAuthPassword(e.target.value)} />
      {authMode !== 'login' && <input className='field' placeholder='Повторите пароль' type='password' value={authPasswordConfirm} onChange={(e) => setAuthPasswordConfirm(e.target.value)} />}
      <button type='submit' disabled={isWebAuthLoading}>
        {isWebAuthLoading ? 'Проверяем…' : authMode === 'register' ? 'Зарегистрироваться' : authMode === 'setup' ? 'Сохранить пароль и войти' : 'Войти'}
      </button>
      <div className='auth-switches'>
        {authMode === 'login'
          ? <button className='chip' onClick={() => setAuthMode('register')} type='button'>Регистрация</button>
          : <button className='chip' onClick={() => setAuthMode('login')} type='button'>У меня есть аккаунт</button>}
      </div>
      {!!webAuthError && <p className='auth-error'>{webAuthError}</p>}
    </form>
  </div>

  if (isAppReady && !data && bootError) return <div className='loading-screen'>
    <div className='loading-orb loading-orb-left' />
    <div className='loading-orb loading-orb-right' />
    <div className='loading-card auth-card'>
      <div className='loading-logo'>⚠️</div>
      <h2>MiniApp не загрузился</h2>
      <p className='subtitle'>{bootError}</p>
      <button
        type='button'
        className='danger-outline'
        onClick={() => {
          setBootError('')
          setBootProgress(26)
          setIsAppReady(false)
          refresh().catch((error) => {
            if (isIdentityResolutionError(error)) {
              resetWebAuthSession()
              setBootError('')
              setData(null)
              setBootProgress(100)
              setIsAppReady(true)
              notify('Сессия MiniApp недействительна. Войдите заново.', { persist: true })
              return
            }
            const errorMessage = error?.message || 'Не удалось загрузить данные MiniApp.'
            setBootError(errorMessage)
            setBootProgress(100)
            setIsAppReady(true)
            notify(errorMessage, { persist: true })
          })
        }}
      >
        Повторить загрузку
      </button>
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
        <div className='top-card-user-row'>
          <div className='top-card-stat top-card-user'>
            <div className='user-row'>
              <button
                className='avatar-emoji-btn'
                type='button'
                onClick={() => {
                  setTab('account')
                  setSectionOpen((current) => ({ ...current, account: false, favorite: true, payments: false }))
                }}
              >
                <span>{accountAvatar}</span>
              </button>
              <button
                className='user-link-btn'
                type='button'
                onClick={() => setTab('account')}
              >
                <b>{String(webAuth?.accountLabel || `ID ${data.userId}`).replace(/^@/, '')}</b>
              </button>
              <button
                className='chip icon-btn logout-btn'
                aria-label='Выйти'
                title='Выйти'
                onClick={() => {
                  resetWebAuthSession()
                  setData(null)
                  setLeaderboards(null)
                  setHistoryItems([])
                  setRecentResults([])
                  setIsHistoryLoaded(false)
                  setIsRecentResultsLoaded(false)
                  setTab('race')
                }}
              >
                ↪
              </button>
            </div>
          </div>
          <div className='top-card-actions'>
            <button className='bell-btn icon-btn' title='Уведомления' aria-label='Уведомления' onClick={() => setIsNotificationsOpen((current) => !current)}>
              🔔
              {unreadCount > 0 && <span className='bell-badge'>{unreadCount}</span>}
            </button>
          </div>
        </div>
        <div className='top-card-main'>
          <div className='top-card-stat top-card-resources'>
            <div className='resource-line'>
              <div className={`balance-display ${balancePulse ? 'balance-display-pulse' : ''}`}>
                <span className='resource-icon'>💎</span>
                <b>{formatStars(data.balance)}</b>
                {balanceFloatingGain && <span key={balanceFloatingGain.id} className='balance-floating-gain'>{balanceFloatingGain.text}</span>}
              </div>
              <div
                className={`booster-display booster-display-tooltip ${boosterPulse ? 'booster-display-pulse' : ''} ${(Number(data.freeBoosters) || 0) > 0 ? 'booster-display-active' : 'booster-display-empty'}`}
                data-tooltip='Бустеры — бесплатные усилители в гонке: ускоряют, замедляют соперников или защищают выбранный смайл.'
                tabIndex={0}
              >
                <span className='resource-icon'>⚡</span>
                <span>{formatStars(data.freeBoosters)}</span>
              </div>
            </div>
            <div className='balance-actions'>
              <button
                className='cta-btn cta-btn-primary'
                title='Пополнить баланс'
                aria-label='Пополнить баланс'
                onClick={() => { setTab('account'); setSectionOpen((current) => ({ ...current, account: false, favorite: false, payments: true })) }}
              >
                💳 Пополнить
              </button>
              <button
                className='cta-btn cta-btn-secondary'
                title='Вывести средства'
                aria-label='Вывести средства'
                onClick={() => { setTab('account'); setSectionOpen((current) => ({ ...current, account: false, favorite: false, payments: true })) }}
              >
                💸 Вывести
              </button>
            </div>
          </div>
        </div>
      </header>

      <nav className='tabs'>
        {TAB_ORDER.map((tabKey) => {
          const isRace = tabKey === 'race'
          const isBattle = tabKey === 'battle'
          const isRatings = tabKey === 'ratings'
          const raceBadge = isRace && raceParticipationActive
          const battleBadge = isBattle && battleAttentionActive
          const ratingBadge = isRatings && weeklyTopPlace
          return <button key={tabKey} className={tab === tabKey ? 'active' : ''} onClick={() => setTab(tabKey)}>
            <span>{TAB_TITLES[tabKey]}</span>
            {raceBadge && <span className='tab-dot tab-dot-glow' />}
            {battleBadge && <span className='tab-dot tab-dot-glow' />}
            {ratingBadge && <span className='tab-rank-badge'>{weeklyTopPlace}</span>}
          </button>
        })}
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
        <button type='button' onClick={() => setBoosterHint('BUST')}><b>🐇</b> ускорить · 10💎</button>
        <button type='button' onClick={() => setBoosterHint('SLOW')}><b>🐢</b> замедлить · 10💎</button>
        <button type='button' onClick={() => setBoosterHint('SHIELD')}><b>🪖</b> защитить · 40💎</button>
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
            <span>Твой голос: {formatStars(localVotes[u.playerNumber] ?? u.myVotes)}</span>
          </div>
          <div className='vote-inline'>
          <RubyAmountField
            value={voteInputs[u.playerNumber] ?? 1}
            min={1}
            max={Math.max(1, data.balance || 1)}
            placeholder={`до ${formatStars(Math.max(1, data.balance || 1))}`}
            onFocus={pausePollingForInteraction}
            onChange={(next) => {
              pausePollingForInteraction()
              setVoteInputs((current) => ({ ...current, [u.playerNumber]: next }))
            }}
          />
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
          <div className='booster-vote-info'>Твоя ставка: {formatStars(localVotes[u.playerNumber] ?? u.myVotes)} 💎</div>
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

    {tab === 'ratings' && <section className='panel tab-panel account-panel'>
      <div className='ratings-prize-banner'>
        <h3>Недельный рейтинг 🏆</h3>
        <p className='subtitle'>Ежедневный приз начисляется по этому рейтингу: игрок №1 получает <b>100 💎</b> и <b>5 бесплатных бустеров</b>.</p>
        <p className='subtitle'>
          Текущая неделя: {formatPeriodDate(leaderboards?.weeklyPeriodStart)} — {formatPeriodDate(leaderboards?.weeklyPeriodEnd)}
        </p>
      </div>
      {renderSection('ratingsPlayersWeekly', 'Недельный топ игроков', <>
        {leaderboardsLoading && <p className='subtitle'>Загружаем недельный рейтинг…</p>}
        {!!leaderboards?.playerWinnersWeekly?.length && <div className='rating-list'>
          {leaderboards.playerWinnersWeekly.map((item, index) => <div key={`${item.userId}-${index}`} className='rating-row'>
            <span>#{index + 1} {item.displayName}</span>
            <strong>{formatStars(item.wonVotesSum)} 💎</strong>
          </div>)}
        </div>}
      </>)}
      {renderSection('ratingsEmojis', 'Топ смайлов', <>
        {leaderboardsLoading && <p className='subtitle'>Загружаем рейтинг смайлов…</p>}
        {data.favoriteEmoji && <p className='subtitle'>
          Любимый смайл: <b>{data.favoriteEmoji}</b>{favoriteEmojiPlace ? ` · место #${favoriteEmojiPlace}` : ' · пока вне таблицы'}
        </p>}
        {!!leaderboards?.emojiWinners?.length && <div className='rating-list'>
          {leaderboards.emojiWinners.map((item, index) => <div key={`${item.emoji}-${index}`} className='rating-row'>
            <span>#{index + 1} {item.emoji}</span>
            <strong>{formatStars(item.wins)} побед</strong>
          </div>)}
        </div>}
      </>)}
      {renderSection('ratingsPlayersAll', 'Топ игроков за всё время', <>
        {leaderboardsLoading && <p className='subtitle'>Загружаем общий рейтинг игроков…</p>}
        {!!leaderboards?.playerWinnersAllTime?.length && <div className='rating-list'>
          {leaderboards.playerWinnersAllTime.map((item, index) => <div key={`${item.userId}-${index}`} className='rating-row'>
            <span>#{index + 1} {item.displayName}</span>
            <strong>{formatStars(item.wonVotesSum)} 💎</strong>
          </div>)}
        </div>}
      </>)}
    </section>}

    {tab === 'account' && <section className='panel tab-panel account-panel'>
      {renderSection('account', 'Данные об аккаунте', <>
        <p className='subtitle'>
          {webAuth?.accountLabel || 'Веб-аккаунт'}
        </p>
        <p className='subtitle'>
          {data.localTestMode
            ? `Тестовый режим: баланс берётся из аккаунта владельца (ID ${data.userId}).`
            : `Баланс и операции выполняются для текущего аккаунта (ID ${data.userId}).`}
        </p>
      </>)}

      {!!data?.isAdmin && renderSection('adminWithdraws', 'Админ: выводы', <>
        {!adminWithdraws.length && <p className='subtitle'>Активных запросов на вывод нет.</p>}
        {!!adminWithdraws.length && <div className='admin-list'>
          {adminWithdraws.map((item) => <div key={item.id} className='unit'>
            <div className='unit-row'>
              <strong>#{item.id}</strong> <span>{item.username}</span> <span>{formatStars(item.amount)} 💎</span>
            </div>
            <div className='unit-row'>
              <button className='chip' onClick={() => adminWithdrawAction(item.id, 'pay')}>Подтвердить</button>
              <button className='chip danger-chip' onClick={() => adminWithdrawAction(item.id, 'cancel')}>Отменить</button>
            </div>
          </div>)}
        </div>}
      </>)}

      {!!data?.isAdmin && renderSection('adminBalance', 'Админ: баланс пользователя', <>
        <input className='field admin-login-field' list='admin-usernames' placeholder='логин' value={adminUsername} onChange={(e) => setAdminUsername(e.target.value)} />
        <datalist id='admin-usernames'>
          {adminUserOptions.map((username) => <option key={username} value={username} />)}
        </datalist>
        <RubyAmountField value={adminAmount} min={1} max={Math.max(1, data.balance || 1)} onChange={setAdminAmount} />
        <div className='action-row'>
          <button className='chip admin-balance-btn' onClick={() => adminAdjustBalance('add')}>Зачислить</button>
          <button className='chip danger-chip admin-balance-btn' onClick={() => adminAdjustBalance('subtract')}>Списать</button>
        </div>
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
        <p className='subtitle'>Смена любимого смайла — 150💎 (первый выбор бесплатный).</p>
        <div className='row'>
          <button disabled={!data.favoriteEmoji} onClick={() => act('queue', { playerName: data.favoriteEmoji })}>Добавить любимый смайл в очередь (10💎)</button>
        </div>
      </>)}

      {renderSection('payments', 'Пополнение и вывод', <>
        <div className='row'>
          <RubyAmountField value={topupAmount} min={TOPUP_MIN} max={Math.max(1, data.balance || 1)} onChange={(next) => { pausePollingForInteraction(); setTopupAmount(next) }} onFocus={pausePollingForInteraction} />
          <button onClick={() => act('topup', { amount: topupAmount })}>Пополнить</button>
        </div>
        <div className='row'>
          <RubyAmountField value={withdrawAmount} min={WITHDRAW_MIN} max={Math.max(WITHDRAW_MIN, maxWithdraw)} clampOnBlur={false} onChange={(next) => { pausePollingForInteraction(); setWithdrawAmount(next) }} onFocus={pausePollingForInteraction} />
          <button onClick={requestWithdrawFromUi}>Создать запрос на вывод</button>
        </div>
        <p className='subtitle'>Доступно к выводу: до {maxWithdraw} 💎. Минимум: {WITHDRAW_MIN} 💎.</p>
        <div className='grid'>
          {activeWithdraws.map((w) => <button key={w.id} className='chip' onClick={() => act('withdraw/cancel', { requestId: w.id })}>Отменить вывод #{w.id} ({w.amount}💎)</button>)}
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
                <td className={Number(item.amount) >= 0 ? 'amount-plus' : 'amount-minus'}>{formatStars(item.amount)} 💎</td>
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
              <RubyAmountField value={battleStakeInput} min={1} max={Math.max(1, data.balance || 1)} onChange={setBattleStakeInput} />
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
              <div className='battle-stat'><span>Ставка</span><strong>{myBattle.battleStake || 0} 💎</strong></div>
              <div className='battle-stat'><span>Банк</span><strong>{getBattleBank(myBattle)} 💎</strong></div>
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
              <div className='battle-stat'><span>Ставка</span><strong>{joinedBattle.battleStake || 0} 💎</strong></div>
              <div className='battle-stat'><span>Банк</span><strong>{getBattleBank(joinedBattle)} 💎</strong></div>
            </div>
            <p className='subtitle battle-state-line'>{getBattleStateLabel(joinedBattle)}</p>
            <div className='battle-participants-list'>
              {(joinedBattle.units || []).map((u) => <div key={u.playerNumber} className='battle-participant-row'>
                <span>{getBattleParticipantLabel(u)}</span>
              </div>)}
            </div>
            <div className='row battle-action-row battle-action-row-single'>
              <button className='chip danger-chip' disabled={!canLeaveJoinedBattle} onClick={leaveJoinedBattle}>Сбежать (вернуть {joinedBattle.battleStake || 0} 💎)</button>
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
