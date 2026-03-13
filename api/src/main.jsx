import React, { useEffect, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import './styles.css'

const API = '/api/miniapp'
const tg = window.Telegram?.WebApp

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

  const refresh = async () => {
    const [bootstrapRes, withdrawsRes] = await Promise.all([
      fetch(`${API}/bootstrap?userId=${userId}`),
      fetch(`${API}/withdraw/active?userId=${userId}`)
    ])
    setData(await bootstrapRes.json())
    const withdrawData = await withdrawsRes.json()
    setActiveWithdraws(withdrawData.items || [])
  }

  useEffect(() => {
    tg?.ready()
    refresh()
  }, [])

  useEffect(() => {
    if (!data?.allEmojis?.length) return
    setBattleEmoji((current) => current || data.allEmojis[0])
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
    await refresh()
    return r
  }

  if (!data) return <div className='loading'>Загрузка…</div>

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
      <h2>{data.race ? `Гонка #${data.race.matchId} · ${data.race.type}` : 'Нет активной гонки'}</h2>
      <p className='subtitle'>Голос доступен при достаточном балансе. Сумму можно менять.</p>
      <div className='row'>
        <input type='number' min='1' value={voteAmount} onChange={(e) => setVoteAmount(Number(e.target.value || 1))} />
      </div>
      {data.race?.units?.map((u) => <div className='unit' key={u.playerNumber}>
        <div className='unit-head'>
          <div className='name'>{u.playerName}</div>
          <div className='score'>{u.score}</div>
        </div>
        <div className='meter'>
          <div style={{ width: `${Math.min(100, u.score)}%` }} />
        </div>
        {spark === u.playerNumber && <div className='spark'>✨</div>}
        <div className='actions'>
          <button onClick={() => act('vote', { matchId: data.race.matchId, playerNumber: u.playerNumber, amount: voteAmount })}>Голос</button>
          <button onClick={async () => { await act('boost', { playerNumber: u.playerNumber, type: 'BUST' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700) }}>🐇</button>
          <button onClick={async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SLOW' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700) }}>🐢</button>
          <button onClick={async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SHIELD' }); setSpark(u.playerNumber); setTimeout(() => setSpark(null), 700) }}>🛡</button>
        </div>
      </div>)}
    </section>}

    {tab === 'account' && <section className='panel'>
      <h2>Профиль и действия</h2>

      <h3>Любимый эмодзи</h3>
      <div className='grid'>
        {data.allEmojis.map((name) => <button key={name} className={data.favoriteEmoji === name ? 'chip selected' : 'chip'} onClick={() => act('favorite', { playerName: name })}>{name}{data.favoriteEmoji === name ? ' ✓' : ''}</button>)}
      </div>
      <p className='subtitle'>Смена любимого смайла — 150⭐ (первый выбор бесплатный).</p>

      <h3>Очередь</h3>
      <div className='row'>
        <button disabled={!data.favoriteEmoji} onClick={() => act('queue', { playerName: data.favoriteEmoji })}>В очередь любимый смайл (10⭐)</button>
      </div>

      <h3>Баланс</h3>
      <div className='row'>
        <input type='number' min='1' value={topupAmount} onChange={(e) => setTopupAmount(Number(e.target.value || 1))} />
        <button onClick={() => act('topup', { amount: topupAmount })}>Пополнить через ⭐</button>
      </div>

      <h3>Вывод</h3>
      <div className='row'>
        <input type='number' min='100' value={withdrawAmount} onChange={(e) => setWithdrawAmount(Number(e.target.value || 100))} />
        <button onClick={() => act('withdraw', { amount: withdrawAmount })}>Создать запрос на вывод</button>
      </div>
      <div className='grid'>
        {activeWithdraws.map((w) => <button key={w.id} className='chip' onClick={() => act('withdraw/cancel', { requestId: w.id })}>Отменить вывод #{w.id} ({w.amount}⭐)</button>)}
      </div>

      <h3>Батл</h3>
      <div className='row'>
        <select value={battleEmoji} onChange={(e) => setBattleEmoji(e.target.value)}>{data.allEmojis.map((e) => <option key={e}>{e}</option>)}</select>
        <button onClick={() => act('battle', { playerName: battleEmoji, stake: 100 })}>Создать батл 100⭐</button>
      </div>

      <button className='help-btn' onClick={async () => setMessage((await (await fetch(`${API}/help`)).json()).message)}>Помощь</button>
    </section>}

    {message && <footer>{message}</footer>}
  </div>
}

createRoot(document.getElementById('root')).render(<App />)
