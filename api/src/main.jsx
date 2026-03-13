import React, { useEffect, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import './styles.css'

const API = '/api/miniapp'
const tg = window.Telegram?.WebApp
const WITHDRAW_MIN = 100
const TOPUP_MIN = 1
const POLL_INTERVAL_MS = 3500
const DEFAULT_TRACK_LENGTH = 62

const RACE_TYPE_LABELS = {
  REGULAR: 'Обычная',
  SPRINT: 'Спринт',
  MARATHON: 'Марафон'
}

const TRACK_THEMES = ['asphalt', 'grass', 'desert']

const normalizeType = (type) => String(type || '').trim().toUpperCase()
const getRaceTypeLabel = (type) => RACE_TYPE_LABELS[normalizeType(type)] || type || 'Неизвестно'

const getTrackTheme = (race) => {
  if (!race) return TRACK_THEMES[0]
  const seed = Number(race.matchId || 0)
  return TRACK_THEMES[Math.abs(seed) % TRACK_THEMES.length]
}

const getUserId = () => tg?.initDataUnsafe?.user?.id || Number(new URLSearchParams(location.search).get('userId') || 1)

function App() {
  const userId = useMemo(getUserId, [])
  const [data, setData] = useState(null)
  const [activeWithdraws, setActiveWithdraws] = useState([])
  const [tab, setTab] = useState('race')
  const [message, setMessage] = useState('')
  const [spark, setSpark] = useState(null)
  const [battleEmoji, setBattleEmoji] = useState('')
  const [voteAmount, setVoteAmount] = useState(10)
  const [topupAmount, setTopupAmount] = useState(100)
  const [withdrawAmount, setWithdrawAmount] = useState(100)
  const [voteModalUnit, setVoteModalUnit] = useState(null)
  const [favoriteIndex, setFavoriteIndex] = useState(0)

  const refresh = async (silent = false) => {
    const [bootstrapRes, withdrawsRes] = await Promise.all([
      fetch(`${API}/bootstrap?userId=${userId}`),
      fetch(`${API}/withdraw/active?userId=${userId}`)
    ])
    const bootstrapData = await bootstrapRes.json()
    setData(bootstrapData)
    const withdrawData = await withdrawsRes.json()
    setActiveWithdraws(withdrawData.items || [])
    if (!silent && !bootstrapData.race && tab === 'race') {
      setMessage('Сейчас нет активной гонки. Обновим автоматически, как только стартует следующая.')
    }
  }

  useEffect(() => {
    tg?.ready()
    refresh()
  }, [])

  useEffect(() => {
    const timer = setInterval(() => {
      refresh(true).catch(() => null)
    }, POLL_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [userId, tab])

  useEffect(() => {
    if (!data?.allEmojis?.length) return
    setBattleEmoji((current) => current || data.allEmojis[0])
    const currentFavorite = data.favoriteEmoji || data.allEmojis[0]
    const idx = Math.max(0, data.allEmojis.indexOf(currentFavorite))
    setFavoriteIndex(idx)
  }, [data])

  const act = async (path, body) => {
    const res = await fetch(`${API}/${path}?userId=${userId}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })
    const r = await res.json()
    setMessage(r.message)
    if (r.invoiceLink) {
      tg?.openLink ? tg.openLink(r.invoiceLink) : window.open(r.invoiceLink, '_blank')
    }
    await refresh(true)
    return r
  }

  const maxWithdraw = data?.balance || WITHDRAW_MIN
  const clampedWithdraw = Math.max(WITHDRAW_MIN, Math.min(withdrawAmount || WITHDRAW_MIN, maxWithdraw))

  const voteAction = async () => {
    if (!data?.race || !voteModalUnit) return
    await act('vote', { matchId: data.race.matchId, playerNumber: voteModalUnit.playerNumber, amount: voteAmount })
    setVoteModalUnit(null)
  }

  const openHelp = async () => {
    const response = await fetch(`${API}/help`)
    const payload = await response.json()
    const link = payload?.message
    if (link && /^https?:\/\//.test(link)) {
      tg?.openLink ? tg.openLink(link) : window.open(link, '_blank')
      return
    }
    setMessage('Ссылка на поддержку временно недоступна.')
  }

  if (!data) return <div className='loading'>Загрузка…</div>

  const raceEnded = !data.race || data.race.status !== 'CREATED'
  const trackTheme = getTrackTheme(data.race)
  const raceUnits = data.race?.units || []
  const maxScore = raceUnits.reduce((max, unit) => Math.max(max, Number(unit.score) || 0), 0)
  const finishScore = Math.max(Number(data.race?.trackLength) || DEFAULT_TRACK_LENGTH, maxScore, 1)

  return <div className='app'>
    <div className='aurora' />
    <header className='top-card'>
      <div>
        <span className='label'>Баланс</span>
        <b>{data.balance} ⭐</b>
      </div>
      <div>
        <span className='label'>Бесплатные бустеры</span>
        <b>{data.freeBoosters}</b>
      </div>
    </header>

    <nav className='tabs'>
      <button className={tab === 'race' ? 'active' : ''} onClick={() => setTab('race')}>Гонка</button>
      <button className={tab === 'account' ? 'active' : ''} onClick={() => setTab('account')}>Аккаунт</button>
    </nav>

    {tab === 'race' && <section className='panel'>
      <h2>{data.race ? `Гонка #${data.race.matchId} · ${getRaceTypeLabel(data.race.type)}` : 'Нет активной гонки'}</h2>
      <p className='subtitle'>Голосование открыто только до старта. Экран синхронизируется каждые несколько секунд.</p>
      {!!data.race && raceEnded && <p className='badge'>Гонка уже началась или завершилась — голосование закрыто.</p>}

      <div className={`track track-${trackTheme}`}>
      {raceUnits.map((u, index) => {
        const score = Number(u.score) || 0
        const percent = finishScore ? Math.min(100, Math.round(score / finishScore * 100)) : 0

        return <div className='unit lane' key={u.playerNumber}>
        <div className='unit-head'>
          <div className='name'>{u.playerName}</div>
          <div className='score'>{percent}%</div>
        </div>
        <div className='meter'>
          <div style={{ width: `${percent}%` }} />
          <div className='finish-line' />
        </div>
        <div className='lane-index'>Полоса {index + 1}</div>
        {spark === u.playerNumber && <div className='spark'>✨</div>}
        <div className='actions'>
          {!raceEnded && <button onClick={() => setVoteModalUnit(u)}>Отдать голос</button>}
          <button className='booster booster-bust' disabled={raceEnded} onClick={async () => { await act('boost', { playerNumber: u.playerNumber, type: 'BUST' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700) }}><span>⚡</span> Рывок</button>
          <button className='booster booster-slow' disabled={raceEnded} onClick={async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SLOW' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700) }}><span>🧊</span> Лёд</button>
          <button className='booster booster-shield' disabled={raceEnded} onClick={async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SHIELD' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700) }}><span>🛡</span> Щит</button>
        </div>
      </div>
      })}
      </div>
    </section>}

    {tab === 'account' && <section className='panel'>
      <h2>Профиль и действия</h2>

      <h3>Любимый смайл</h3>
      <div className='emoji-slider-wrap'>
        <input
          type='range'
          min='0'
          max={Math.max(0, data.allEmojis.length - 1)}
          value={favoriteIndex}
          onChange={(e) => setFavoriteIndex(Number(e.target.value))}
          className='emoji-slider'
        />
        <div className='emoji-preview'>{data.allEmojis[favoriteIndex]}</div>
        <div className='row'>
          <button onClick={() => act('favorite', { playerName: data.allEmojis[favoriteIndex] })}>Сохранить любимый смайл</button>
        </div>
      </div>
      <p className='subtitle'>Смена любимого смайла — 150⭐ (первый выбор бесплатный).</p>

      <h3>Управление очередью</h3>
      <div className='row'>
        <button disabled={!data.favoriteEmoji} onClick={() => act('queue', { playerName: data.favoriteEmoji })}>Добавить любимый смайл в очередь (10⭐)</button>
      </div>

      <h3>Баланс</h3>
      <div className='row'>
        <input className='field' type='number' min={TOPUP_MIN} step='1' value={topupAmount} onChange={(e) => setTopupAmount(Math.max(TOPUP_MIN, Number(e.target.value || TOPUP_MIN)))} />
        <button onClick={() => act('topup', { amount: topupAmount })}>Пополнить через ⭐</button>
      </div>

      <h3>Вывод</h3>
      <div className='row'>
        <input
          className='field'
          type='number'
          min={WITHDRAW_MIN}
          max={maxWithdraw}
          step='1'
          value={withdrawAmount}
          onChange={(e) => setWithdrawAmount(Number(e.target.value || WITHDRAW_MIN))}
        />
        <button disabled={clampedWithdraw > maxWithdraw} onClick={() => act('withdraw', { amount: clampedWithdraw })}>Создать запрос на вывод</button>
      </div>
      <p className='subtitle'>Доступно к выводу: до {maxWithdraw} ⭐. Минимум: {WITHDRAW_MIN} ⭐.</p>
      <div className='grid'>
        {activeWithdraws.map((w) => <button key={w.id} className='chip' onClick={() => act('withdraw/cancel', { requestId: w.id })}>Отменить вывод #{w.id} ({w.amount}⭐)</button>)}
      </div>

      <h3>Батл</h3>
      <div className='row'>
        <select value={battleEmoji} onChange={(e) => setBattleEmoji(e.target.value)}>{data.allEmojis.map((e) => <option key={e}>{e}</option>)}</select>
        <button onClick={() => act('battle', { playerName: battleEmoji, stake: 100 })}>Создать батл 100⭐</button>
      </div>

      <button className='help-btn' onClick={openHelp}>Связаться с поддержкой</button>
    </section>}

    {voteModalUnit && <div className='modal-backdrop' onClick={() => setVoteModalUnit(null)}>
      <div className='modal' onClick={(e) => e.stopPropagation()}>
        <h3>Отдать голос</h3>
        <p className='subtitle'>Вы выбрали: {voteModalUnit.playerName}</p>
        <input className='field' type='number' min='1' step='1' value={voteAmount} onChange={(e) => setVoteAmount(Math.max(1, Number(e.target.value || 1)))} />
        <div className='actions'>
          <button onClick={() => setVoteModalUnit(null)}>Отмена</button>
          <button onClick={voteAction}>Подтвердить</button>
        </div>
      </div>
    </div>}

    {message && <footer>{message}</footer>}
  </div>
}

createRoot(document.getElementById('root')).render(<App />)
