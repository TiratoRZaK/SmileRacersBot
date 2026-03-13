import React, { useEffect, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import './styles.css'

const API = '/api/miniapp'
const tg = window.Telegram?.WebApp

const getUserId = () => tg?.initDataUnsafe?.user?.id || Number(new URLSearchParams(location.search).get('userId') || 1)

function App() {
  const userId = useMemo(getUserId, [])
  const [data, setData] = useState(null)
  const [tab, setTab] = useState('race')
  const [message, setMessage] = useState('')
  const [spark, setSpark] = useState(null)

  const refresh = async () => {
    const res = await fetch(`${API}/bootstrap?userId=${userId}`)
    setData(await res.json())
  }

  useEffect(() => {
    tg?.ready()
    refresh()
  }, [])

  const act = async (path, body) => {
    const res = await fetch(`${API}/${path}?userId=${userId}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })
    const r = await res.json()
    setMessage(r.message)
    await refresh()
    return r
  }

  if (!data) return <div className='loading'>Загрузка…</div>

  return <div className='app'>
    <header>
      <div>Баланс: <b>{data.balance} ⭐</b></div>
      <div>Бесплатные бустеры: <b>{data.freeBoosters}</b></div>
    </header>

    <nav>
      <button className={tab==='race'?'active':''} onClick={() => setTab('race')}>Гонка</button>
      <button className={tab==='account'?'active':''} onClick={() => setTab('account')}>Аккаунт</button>
    </nav>

    {tab === 'race' && <section>
      <h2>{data.race ? `Гонка #${data.race.matchId} (${data.race.type})` : 'Нет активной гонки'}</h2>
      {data.race?.units?.map((u) => <div className='unit' key={u.playerNumber}>
        <div className='name'>{u.playerName}</div>
        <div className='score'>{u.score}</div>
        {spark===u.playerNumber && <div className='spark'>✨</div>}
        <div className='actions'>
          <button onClick={() => act('vote', { matchId: data.race.matchId, playerNumber: u.playerNumber, amount: 10 })}>Голос 10⭐</button>
          <button onClick={async () => { await act('boost', { playerNumber: u.playerNumber, type: 'BUST' }); setSpark(u.playerNumber); setTimeout(()=>setSpark(null),700)}}>🐇</button>
          <button onClick={async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SLOW' }); setSpark(u.playerNumber); setTimeout(()=>setSpark(null),700)}}>🐢</button>
          <button onClick={async () => { await act('boost', { playerNumber: u.playerNumber, type: 'SHIELD' }); setSpark(u.playerNumber); setTimeout(()=>setSpark(null),700)}}>🛡</button>
        </div>
      </div>)}
    </section>}

    {tab === 'account' && <section>
      <h2>Управление аккаунтом</h2>
      <div className='grid'>
        {data.allEmojis.map((name) => <button key={name} onClick={() => act('favorite', { playerName: name })}>{name}{data.favoriteEmoji===name?' ✅':''}</button>)}
      </div>
      <div className='row'>
        <select id='q'>{data.allEmojis.map((e) => <option key={e}>{e}</option>)}</select>
        <button onClick={() => act('queue', { playerName: document.getElementById('q').value })}>В очередь (10⭐)</button>
      </div>
      <div className='row'>
        <button onClick={() => act('topup', { amount: 100 })}>Пополнить +100⭐</button>
        <button onClick={() => act('withdraw', { amount: 50 })}>Вывести 50⭐</button>
      </div>
      <div className='row'>
        <select id='b'>{data.allEmojis.map((e) => <option key={e}>{e}</option>)}</select>
        <button onClick={() => act('battle', { playerName: document.getElementById('b').value, stake: 100 })}>Создать батл 100⭐</button>
      </div>
      <button onClick={async () => setMessage((await (await fetch(`${API}/help`)).json()).message)}>Помощь</button>
    </section>}

    {message && <footer>{message}</footer>}
  </div>
}

createRoot(document.getElementById('root')).render(<App />)
